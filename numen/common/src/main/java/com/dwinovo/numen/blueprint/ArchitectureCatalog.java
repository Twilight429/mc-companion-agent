package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.tools.BuildOps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/** 本地作品目录。原文件不改写,数据库不打包作品;作者、作品链接、授权说明与校验值同行。 */
public final class ArchitectureCatalog {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String INDEX = "architecture-index.json";
    private ArchitectureCatalog() {}

    public record Entry(String id, String title, String author, String sourceUrl, String license,
                        String description, List<String> tags, int dataVersion, List<Integer> size,
                        int cells, Map<String, Integer> blocks, Map<String, Integer> materials,
                        int notBuilt, boolean compatible, List<String> issues, String fingerprint) {}
    public record Refresh(int indexed, int remaining, List<String> errors) {}
    public record Cell(BlockPos pos, BlockState state, boolean unknown) {}

    public static List<Entry> entries(MinecraftServer server) {
        Path index = BlueprintStore.dir(server).resolve(INDEX);
        if (!Files.exists(index)) return List.of();
        try {
            if (Files.size(index) > 16L * 1024 * 1024 || !index.toRealPath().startsWith(BlueprintStore.dir(server).toRealPath()))
                throw new IllegalArgumentException("invalid architecture index size or location");
            Entry[] entries = JSON.fromJson(Files.readString(index), Entry[].class);
            if (entries == null) throw new IllegalArgumentException("architecture index must be an array");
            return List.of(entries);
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    public static boolean managed(MinecraftServer server, String name) {
        return entries(server).stream().anyMatch(e -> e.id().equals(name));
    }

    public static Entry get(MinecraftServer server, String name) {
        return entries(server).stream().filter(e -> e.id().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("建筑尚未导入: " + name + "; 请在建筑库刷新"));
    }

    /** Index a newly validated local design directly; it must not wait behind the import batch limit. */
    static Entry index(ServerLevel level, String name) {
        MinecraftServer server = level.getServer();
        String fingerprint = fingerprint(server, name);
        Entry entry = inspect(name, BlueprintStore.readTag(server, name), metadata(server, name), fingerprint);
        if (!entry.compatible()) throw new IllegalArgumentException(String.join("; ", entry.issues()));
        var layout = BlueprintStore.load(level, name, BlockPos.ZERO, 0);
        entry = new Entry(entry.id(), entry.title(), entry.author(), entry.sourceUrl(), entry.license(),
                entry.description(), entry.tags(), entry.dataVersion(), entry.size(), entry.cells(), entry.blocks(),
                BuildOps.materials(layout, level.registryAccess()), layout.dropped(), true, entry.issues(), fingerprint);
        Map<String, Entry> all = new TreeMap<>();
        for (Entry old : entries(server)) all.put(old.id(), old);
        all.put(name, entry);
        write(server, all.values());
        return entry;
    }

    /** 每次最多解码八个变更文件,没有变更的只核对散列;大库分批导入,不在每轮 LLM 请求重复解码。 */
    public static Refresh refresh(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Map<String, Entry> saved = new TreeMap<>();
        for (Entry entry : entries(server)) saved.put(entry.id(), entry);
        Set<String> present = new HashSet<>(BlueprintStore.list(server));
        saved.keySet().retainAll(present);
        int indexed = 0, remaining = 0;
        List<String> errors = new ArrayList<>();
        for (String name : new TreeSet<>(present)) {
            try {
                String fingerprint = fingerprint(server, name);
                Entry old = saved.get(name);
                if (old != null && old.fingerprint().equals(fingerprint)) continue;
                // 旧数据即使重导失败也不能留成“可施工”。
                saved.remove(name);
                if (indexed >= 8) { remaining++; continue; }
                Entry entry = inspect(name, BlueprintStore.readTag(server, name), metadata(server, name), fingerprint);
                Map<String, Integer> bill = Map.of();
                int dropped = 0;
                if (entry.compatible()) {
                    var layout = BlueprintStore.load(level, name, BlockPos.ZERO, 0);
                    bill = BuildOps.materials(layout, level.registryAccess());
                    dropped = layout.dropped();
                }
                saved.put(name, new Entry(entry.id(), entry.title(), entry.author(), entry.sourceUrl(), entry.license(),
                        entry.description(), entry.tags(), entry.dataVersion(), entry.size(), entry.cells(), entry.blocks(),
                        bill, dropped, entry.compatible(), entry.issues(), fingerprint));
                indexed++;
            } catch (IllegalArgumentException | java.io.UncheckedIOException | com.google.gson.JsonParseException e) {
                errors.add(name + ": " + e.getMessage());
            }
        }
        write(server, saved.values());
        return new Refresh(indexed, remaining, List.copyOf(errors));
    }

    static Entry inspect(String name, CompoundTag structure, JsonObject metadata, String fingerprint) {
        if (name.length() > 120) throw new IllegalArgumentException("blueprint name exceeds 120 characters");
        ListTag dimensions = structure.getList("size", Tag.TAG_INT);
        if (dimensions.size() != 3) throw new IllegalArgumentException("blueprint needs three dimensions");
        List<Integer> size = List.of(dimensions.getInt(0), dimensions.getInt(1), dimensions.getInt(2));
        if (size.stream().anyMatch(n -> n < 1 || n > 256)) throw new IllegalArgumentException("each dimension must be 1..256");
        List<String> issues = new ArrayList<>();
        int version = structure.getInt("DataVersion");
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (version <= 0) issues.add("缺少数据版本，尚未确认是否适配 1.21.1");
        else if (version > current) issues.add("源数据版本 " + version + " 高于当前游戏 " + current + "，请降版导出");
        Map<String, Integer> counts = new TreeMap<>();
        ListTag palette = palette(structure);
        List<RawCell> cells = rawCells(structure);
        for (var cell : cells) {
            String id = palette.getCompound(cell.palette()).getString("Name");
            if (!air(id)) counts.merge(id, 1, Integer::sum);
        }
        Set<Integer> used = new HashSet<>();
        for (var cell : cells) used.add(cell.palette());
        for (int index : new TreeSet<>(used)) {
            String invalid = invalidState(palette.getCompound(index));
            if (invalid != null && !issues.contains(invalid)) issues.add(invalid);
        }
        List<String> tags = new ArrayList<>();
        if (metadata.has("tags") && !metadata.get("tags").isJsonArray()) throw new IllegalArgumentException("tags must be an array");
        if (metadata.has("tags")) for (var tag : metadata.getAsJsonArray("tags")) {
            if (tags.size() >= 32) throw new IllegalArgumentException("at most 32 tags");
            if (!tag.isJsonPrimitive() || !tag.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("tags must be strings");
            tags.add(shortText(tag.getAsString(), 80));
        }
        String author = text(metadata, "author", structure.getCompound("Metadata").getString("Author"), 160);
        return new Entry(name, text(metadata, "title", name, 160), author.isBlank() ? "作者待核实" : author,
                text(metadata, "sourceUrl", "作品链接待补充", 600),
                text(metadata, "license", "授权说明待补充；不默认允许再分发", 600),
                text(metadata, "description", "", 1200), List.copyOf(tags), version, size,
                counts.values().stream().mapToInt(Integer::intValue).sum(), Map.copyOf(counts), Map.of(), 0,
                issues.isEmpty(), List.copyOf(issues), fingerprint);
    }

    private record RawCell(BlockPos pos, int palette) {}

    private static ListTag palette(CompoundTag tag) {
        return tag.contains("palettes", Tag.TAG_LIST) ? tag.getList("palettes", Tag.TAG_LIST).getList(0)
                : tag.getList("palette", Tag.TAG_COMPOUND);
    }

    private static List<RawCell> rawCells(CompoundTag structure) {
        ListTag blocks = structure.getList("blocks", Tag.TAG_COMPOUND);
        if (blocks.size() > 32768) throw new IllegalArgumentException("建筑超过当前施工器的 32768 格上限");
        int paletteSize = palette(structure).size();
        Map<BlockPos, RawCell> cells = new LinkedHashMap<>();
        ListTag size = structure.getList("size", Tag.TAG_INT);
        for (Tag t : blocks) {
            CompoundTag block = (CompoundTag) t;
            ListTag p = block.getList("pos", Tag.TAG_INT);
            int index = block.getInt("state");
            if (p.size() != 3 || index < 0 || index >= paletteSize) throw new IllegalArgumentException("corrupt cell or palette index");
            BlockPos pos = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
            if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() >= size.getInt(0)
                    || pos.getY() >= size.getInt(1) || pos.getZ() >= size.getInt(2)) throw new IllegalArgumentException("cell outside blueprint dimensions");
            cells.put(pos, new RawCell(pos, index));
        }
        return List.copyOf(cells.values());
    }

    static String invalidState(CompoundTag tag) {
        String written = tag.getString("Name");
        ResourceLocation id = ResourceLocation.tryParse(written);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return "缺少方块: " + written;
        var definition = BuiltInRegistries.BLOCK.get(id).getStateDefinition();
        CompoundTag properties = tag.getCompound("Properties");
        for (String key : properties.getAllKeys()) {
            var property = definition.getProperty(key);
            if (property == null || property.getValue(properties.getString(key)).isEmpty())
                return "不兼容的方块状态: " + written + "[" + key + "=" + properties.getString(key) + "]";
        }
        return null;
    }

    /** 完整视觉稿,门床两半都保留;未知方块画占位框,绝不静默冒充空气。 */
    public static List<Cell> cells(MinecraftServer server, String name) {
        CompoundTag tag = BlueprintStore.readTag(server, name);
        ListTag palette = palette(tag);
        List<Cell> result = new ArrayList<>();
        for (RawCell cell : rawCells(tag)) {
            CompoundTag state = palette.getCompound(cell.palette());
            if (air(state.getString("Name"))) continue;
            boolean unknown = invalidState(state) != null;
            result.add(new Cell(cell.pos(), unknown ? Blocks.BARRIER.defaultBlockState()
                    : NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), state), unknown));
        }
        return List.copyOf(result);
    }

    private static boolean air(String name) {
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
    }

    public static String fingerprint(MinecraftServer server, String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(BlueprintStore.file(server, name)));
            Path meta = BlueprintStore.dir(server).resolve(name + ".catalog.json");
            if (Files.exists(meta)) {
                checkMetadata(server, meta);
                digest.update(Files.readAllBytes(meta));
            }
            digest.update(Integer.toString(BuiltInRegistries.BLOCK.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static JsonObject metadata(MinecraftServer server, String name) {
        Path path = BlueprintStore.dir(server).resolve(name + ".catalog.json");
        if (!Files.exists(path)) return new JsonObject();
        try {
            checkMetadata(server, path);
            JsonObject value = JSON.fromJson(Files.readString(path), JsonObject.class);
            if (value == null) throw new IllegalArgumentException("empty catalog metadata");
            return value;
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private static void checkMetadata(MinecraftServer server, Path path) throws IOException {
        if (Files.size(path) > 32768 || !path.toRealPath().startsWith(BlueprintStore.dir(server).toRealPath()))
            throw new IllegalArgumentException("invalid catalog metadata size or location");
    }

    private static String text(JsonObject meta, String key, String fallback, int max) {
        if (meta.has(key) && (!meta.get(key).isJsonPrimitive() || !meta.getAsJsonPrimitive(key).isString()))
            throw new IllegalArgumentException(key + " must be text");
        return shortText(meta.has(key) ? meta.get(key).getAsString() : fallback, max);
    }
    private static String shortText(String value, int max) {
        if (value.length() > max || value.chars().anyMatch(c -> c < 32 && c != 10)) throw new IllegalArgumentException("invalid metadata text");
        return value;
    }

    private static void write(MinecraftServer server, Collection<Entry> entries) {
        Path target = BlueprintStore.dir(server).resolve(INDEX);
        Path temp = null;
        try {
            if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("catalog index cannot be a symlink");
            temp = Files.createTempFile(target.getParent(), "architecture-index-", ".tmp");
            Files.writeString(temp, JSON.toJson(entries));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }
    }
}
