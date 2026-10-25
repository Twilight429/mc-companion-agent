package com.dwinovo.numen.plugins.sceneadapt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 一个世界、一位玩家的协作资料。模型只提议，只有玩家入口能确认长期记忆和分工。 */
public final class PlayerStore {
    public static final int SCHEMA = 1;
    private static final int MAX_FACTS = 64;
    private static final int MAX_PROPOSALS = 16;
    private static final int MAX_CONTRACTS = 16;
    private static final long MAX_BYTES = 262_144;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9_.-]{12,}|(?:api[_ -]?key|password|密码|密钥)\\s*[:=：]\\s*\\S+)");

    public enum Kind { PREFERENCE, CONTRACT }

    public record Fact(String key, String value, String evidence, String source, long revision, long at) {}
    public record Contract(UUID companion, String goal, String playerRole, String npcRole, long revision, long at) {}
    public record Proposal(String id, Kind kind, UUID companion, String key, String value, String evidence,
                           String playerRole, String npcRole, long baseRevision, long at) {}
    public record Feedback(long helpful, long intrusive) {
        public double posteriorMean() { return (2.0 + helpful) / (4.0 + helpful + intrusive); }
    }
    public record View(int schema, UUID owner, long revision, List<Fact> preferences, Contract contract,
                       List<Proposal> pending, Feedback feedback) {}
    private record Document(int schema, UUID owner, long revision, Map<String, Fact> facts,
                            Map<UUID, Contract> contracts, Map<String, Proposal> proposals, Feedback feedback) {}

    private final Path file;
    private final UUID owner;
    private final Clock clock;
    private Document data;

    public PlayerStore(Path worldDirectory, UUID owner) { this(worldDirectory, owner, Clock.systemUTC()); }

    PlayerStore(Path worldDirectory, UUID owner, Clock clock) {
        this.owner = java.util.Objects.requireNonNull(owner);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.file = worldDirectory.resolve("sceneadapt").resolve(owner + ".json");
        this.data = load();
    }

    public synchronized View view(UUID companion, String query) {
        String q = text(query == null ? "" : query, 240, true).toLowerCase(Locale.ROOT);
        List<Fact> facts = new ArrayList<>(data.facts.values());
        facts.sort(Comparator.<Fact>comparingInt(f -> relevance(f, q)).reversed()
                .thenComparing(Comparator.comparingLong(Fact::revision).reversed()));
        return new View(SCHEMA, owner, data.revision, List.copyOf(facts), companion == null ? null : data.contracts.get(companion),
                data.proposals.values().stream().filter(p -> companion == null || p.companion.equals(companion)).toList(), data.feedback);
    }

    private static int relevance(Fact f, String query) {
        if (query.isEmpty()) return 0;
        String haystack = (f.key + " " + f.value).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String word : query.split("\\s+")) if (haystack.contains(word)) score++;
        return score;
    }

    public synchronized Fact remember(String key, String value) {
        String k = key(key);
        Map<String, Fact> facts = new LinkedHashMap<>(data.facts);
        if (!facts.containsKey(k) && facts.size() >= MAX_FACTS) throw invalid("记忆已满，请先删除不再需要的条目");
        Fact f = new Fact(k, text(value, 600, false), "玩家直接设置", "owner", next(), now());
        facts.put(k, f);
        commit(facts, data.contracts, withoutTarget(Kind.PREFERENCE, k, null), data.feedback);
        return f;
    }

    public synchronized void forget(String key) {
        String k = key(key);
        Map<String, Fact> facts = new LinkedHashMap<>(data.facts);
        facts.remove(k);
        commit(facts, data.contracts, withoutTarget(Kind.PREFERENCE, k, null), data.feedback);
    }

    /** 清除包括提议、分工和反馈在内的全部扩展数据，不触碰 Numen 自己的会话与札记。 */
    public synchronized void clear() { commit(Map.of(), Map.of(), Map.of(), new Feedback(0, 0)); }

    public synchronized Proposal proposeMemory(UUID companion, String key, String value, String evidence) {
        String k = key(key);
        String v = text(value, 600, false);
        String e = text(evidence, 600, false);
        long base = data.facts.containsKey(k) ? data.facts.get(k).revision : 0;
        return propose(new Proposal(id(), Kind.PREFERENCE, companion, k, v, e, "", "", base, now()));
    }

    public synchronized Proposal proposeContract(UUID companion, String goal, String playerRole, String npcRole) {
        Contract current = data.contracts.get(companion);
        return propose(new Proposal(id(), Kind.CONTRACT, companion, "", text(goal, 600, false),
                "模型提议，尚未经玩家确认", text(playerRole, 300, false), text(npcRole, 300, false),
                current == null ? 0 : current.revision, now()));
    }

    private Proposal propose(Proposal p) {
        java.util.Objects.requireNonNull(p.companion);
        for (Proposal old : data.proposals.values()) {
            if (old.kind == p.kind && old.companion.equals(p.companion) && old.key.equals(p.key)
                    && old.value.equals(p.value) && old.playerRole.equals(p.playerRole) && old.npcRole.equals(p.npcRole)) {
                return old;
            }
        }
        if (data.proposals.size() >= MAX_PROPOSALS) throw invalid("待确认提议已满，请先处理已有提议");
        Map<String, Proposal> proposals = new LinkedHashMap<>(data.proposals);
        proposals.put(p.id, p);
        commit(data.facts, data.contracts, proposals, data.feedback);
        return p;
    }

    public synchronized Proposal accept(String id) {
        Proposal p = pending(id);
        Map<String, Fact> facts = new LinkedHashMap<>(data.facts);
        Map<UUID, Contract> contracts = new LinkedHashMap<>(data.contracts);
        if (p.kind == Kind.PREFERENCE) {
            Fact old = facts.get(p.key);
            if ((old == null ? 0 : old.revision) != p.baseRevision) throw invalid("提议已过期，请重新提出");
            if (old == null && facts.size() >= MAX_FACTS) throw invalid("记忆已满");
            facts.put(p.key, new Fact(p.key, p.value, p.evidence, "owner_confirmed", next(), now()));
        } else {
            Contract old = contracts.get(p.companion);
            if ((old == null ? 0 : old.revision) != p.baseRevision) throw invalid("分工已改变，请重新提出");
            if (old == null && contracts.size() >= MAX_CONTRACTS) throw invalid("分工已满，请先结束旧分工");
            contracts.put(p.companion, new Contract(p.companion, p.value, p.playerRole, p.npcRole, next(), now()));
        }
        commit(facts, contracts, withoutTarget(p.kind, p.key, p.companion), data.feedback);
        return p;
    }

    public synchronized Proposal reject(String id) {
        Proposal p = pending(id);
        Map<String, Proposal> proposals = new LinkedHashMap<>(data.proposals);
        proposals.remove(id);
        commit(data.facts, data.contracts, proposals, data.feedback);
        return p;
    }

    public synchronized void finishContract(UUID companion) {
        Map<UUID, Contract> contracts = new LinkedHashMap<>(data.contracts);
        contracts.remove(companion);
        commit(data.facts, contracts, withoutTarget(Kind.CONTRACT, "", companion), data.feedback);
    }

    public synchronized Feedback feedback(boolean helpful) {
        Feedback old = data.feedback;
        Feedback result = new Feedback(Math.addExact(old.helpful, helpful ? 1 : 0),
                Math.addExact(old.intrusive, helpful ? 0 : 1));
        commit(data.facts, data.contracts, data.proposals, result);
        return result;
    }

    private Map<String, Proposal> withoutTarget(Kind kind, String key, UUID companion) {
        Map<String, Proposal> result = new LinkedHashMap<>(data.proposals);
        result.values().removeIf(p -> p.kind == kind && (kind == Kind.PREFERENCE
                ? p.key.equals(key) : p.companion.equals(companion)));
        return result;
    }

    private Proposal pending(String id) {
        Proposal p = data.proposals.get(id);
        if (p == null) throw invalid("提议不存在、已处理或已被新设置取代");
        return p;
    }

    private Document load() {
        if (!Files.exists(file)) return new Document(SCHEMA, owner, 0, Map.of(), Map.of(), Map.of(), new Feedback(0, 0));
        try {
            if (Files.size(file) > MAX_BYTES) throw invalid("玩家资料文件过大，未加载");
            Document d = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Document.class);
            validate(d);
            return copy(d);
        } catch (JsonParseException e) { throw invalid("玩家资料不是有效的 JSON，未加载且未覆盖原文件"); }
        catch (IOException e) { throw new UncheckedIOException("无法读取玩家协作资料", e); }
    }

    private void validate(Document d) {
        if (d == null || d.schema != SCHEMA || !owner.equals(d.owner) || d.revision < 0
                || d.facts == null || d.contracts == null || d.proposals == null || d.feedback == null
                || d.facts.size() > MAX_FACTS || d.contracts.size() > MAX_CONTRACTS || d.proposals.size() > MAX_PROPOSALS
                || d.feedback.helpful < 0 || d.feedback.intrusive < 0) throw invalid("玩家资料格式或所属玩家不符，未加载");
        d.facts.forEach((k, f) -> {
            if (f == null || !k.equals(key(k)) || !k.equals(f.key) || f.revision < 1 || f.revision > d.revision
                    || !("owner".equals(f.source) || "owner_confirmed".equals(f.source))) throw invalid("记忆条目损坏");
            text(f.value, 600, false); text(f.evidence, 600, false);
        });
        d.contracts.forEach((id, c) -> {
            if (c == null || !id.equals(c.companion) || c.revision < 1 || c.revision > d.revision) throw invalid("分工条目损坏");
            text(c.goal, 600, false); text(c.playerRole, 300, false); text(c.npcRole, 300, false);
        });
        d.proposals.forEach((id, p) -> {
            if (p == null || !id.equals(p.id) || !id.matches("[a-f0-9]{32}") || p.kind == null || p.companion == null
                    || p.baseRevision < 0 || p.baseRevision > d.revision) throw invalid("提议条目损坏");
            text(p.value, 600, false); text(p.evidence, 600, false);
            if (p.kind == Kind.PREFERENCE) key(p.key);
            else { text(p.playerRole, 300, false); text(p.npcRole, 300, false); }
        });
    }

    private static Document copy(Document d) {
        return new Document(d.schema, d.owner, d.revision,
                Collections.unmodifiableMap(new LinkedHashMap<>(d.facts)),
                Collections.unmodifiableMap(new LinkedHashMap<>(d.contracts)),
                Collections.unmodifiableMap(new LinkedHashMap<>(d.proposals)), d.feedback);
    }

    private void commit(Map<String, Fact> facts, Map<UUID, Contract> contracts, Map<String, Proposal> proposals, Feedback feedback) {
        Document next = copy(new Document(SCHEMA, owner, next(), facts, contracts, proposals, feedback));
        validate(next);
        byte[] bytes = JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw invalid("玩家资料总量超过限制");
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), owner + "-", ".tmp");
            Files.write(temp, bytes);
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            data = next;
        } catch (IOException e) { throw new UncheckedIOException("玩家协作资料未能保存，修改未生效", e); }
        finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); }
                catch (IOException e) { throw new UncheckedIOException("无法清理协作资料临时文件", e); }
            }
        }
    }

    private long next() { return Math.addExact(data.revision, 1); }
    private long now() { return clock.millis(); }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static String key(String value) {
        String k = text(value, 80, false);
        if (!k.matches("[\\p{L}\\p{N}_-]+")) throw invalid("记忆名称只允许文字、数字、下划线或短横线");
        return k;
    }
    private static String text(String value, int max, boolean empty) {
        if (value == null) throw invalid("缺少必要内容");
        String result = value.strip();
        if ((!empty && result.isEmpty()) || result.length() > max || result.chars().anyMatch(c -> Character.isISOControl(c))) {
            throw invalid("内容为空、过长或包含控制字符");
        }
        if (SECRET.matcher(result).find()) throw invalid("不要在玩家记忆或分工中保存密钥与密码");
        return result;
    }
}
