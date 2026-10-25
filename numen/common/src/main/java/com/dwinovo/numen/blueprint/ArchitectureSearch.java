package com.dwinovo.numen.blueprint;

import java.util.*;
import java.util.regex.Pattern;

/** 可解释的中英同义词 + BM25 召回;LLM 在现有脑回路里按玩家意图重排,这里不另开一次模型调用。 */
public final class ArchitectureSearch {
    private static final Pattern WORDS = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+");
    private static final List<List<String>> ALIASES = List.of(
            List.of("住宅", "房屋", "房子", "house", "home", "cottage", "residence"),
            List.of("村镇", "村庄", "乡村", "田园", "village", "rustic", "rural"),
            List.of("中世纪", "欧式", "medieval", "tudor"),
            List.of("日式", "和风", "japanese", "minka"),
            List.of("现代", "简约", "modern", "minimalist"),
            List.of("木质", "木屋", "wood", "timber", "spruce", "oak"),
            List.of("石质", "石头", "stone", "cobblestone", "bricks"),
            List.of("城堡", "堡垒", "castle", "fortress"),
            List.of("农场", "农舍", "farm", "farmhouse"),
            List.of("小型", "小屋", "小住宅", "small", "tiny"));
    private ArchitectureSearch() {}
    public record Match(ArchitectureCatalog.Entry building, double score) {}

    static Map<String, Integer> terms(String input) {
        String text = input.toLowerCase(Locale.ROOT);
        for (var aliases : ALIASES) {
            String before = text;
            if (aliases.stream().anyMatch(before::contains)) text += " " + String.join(" ", aliases);
        }
        Map<String, Integer> out = new HashMap<>();
        var matcher = WORDS.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.charAt(0)) == Character.UnicodeScript.HAN) {
                if (token.length() == 1) out.merge(token, 1, Integer::sum);
                else for (int i = 0; i < token.length() - 1; i++) out.merge(token.substring(i, i + 2), 1, Integer::sum);
            } else out.merge(token, 1, Integer::sum);
        }
        return out;
    }

    public static List<Match> search(List<ArchitectureCatalog.Entry> entries, String query, int limit) {
        if (query.length() > 600) throw new IllegalArgumentException("query is too long");
        int count = Math.max(1, Math.min(10, limit));
        if (query.isBlank()) return entries.stream().sorted(Comparator.comparing(ArchitectureCatalog.Entry::id))
                .limit(count).map(e -> new Match(e, 0)).toList();
        var wanted = terms(query).keySet();
        List<Map<String, Integer>> documents = entries.stream().map(e -> terms(
                (e.title() + " ").repeat(3) + (String.join(" ", e.tags()) + " ").repeat(2) + " " + e.id() + " " + e.description()
                        + " " + String.join(" ", e.blocks().keySet()))).toList();
        double avg = documents.stream().mapToInt(d -> d.values().stream().mapToInt(Integer::intValue).sum()).average().orElse(1);
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            var doc = documents.get(i);
            int length = doc.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            for (String token : wanted) {
                int tf = doc.getOrDefault(token, 0);
                if (tf == 0) continue;
                long df = documents.stream().filter(d -> d.containsKey(token)).count();
                double idf = Math.log(1 + (entries.size() - df + 0.5) / (df + 0.5));
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length / Math.max(1, avg)));
            }
            if (score > 0) matches.add(new Match(entries.get(i), Math.round(score * 1000) / 1000.0));
        }
        return matches.stream().sorted(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(m -> m.building().id())).limit(count).toList();
    }
}
