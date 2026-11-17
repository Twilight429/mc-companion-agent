package com.dwinovo.numen.api.permission;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 每只同伴的丢弃保护偏好;原生权限规则读取它,不在任务执行器另开授权通道。 */
public final class DiscardPolicy {
    public static final int MAX_EXCEPTIONS = 256;
    public static final Codec<DiscardPolicy> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("wood", true).forGetter(DiscardPolicy::wood),
            Codec.BOOL.optionalFieldOf("minerals", true).forGetter(DiscardPolicy::minerals),
            Codec.STRING.listOf().optionalFieldOf("keep", List.of()).forGetter(p -> List.copyOf(p.keep)),
            Codec.STRING.listOf().optionalFieldOf("discard", List.of()).forGetter(p -> List.copyOf(p.discard))
    ).apply(i, (w, m, k, d) -> new DiscardPolicy(w, m, Set.copyOf(k), Set.copyOf(d))));
    public static final DiscardPolicy DEFAULT = new DiscardPolicy(true, true, Set.of(), Set.of());

    // 原版标签兼容数据包,公共标签兼容采用它们的模组。木种和矿物基础品补齐无标签的物品。
    private static final List<String> WOOD_TAGS = List.of("minecraft:logs", "minecraft:planks", "minecraft:wooden_stairs",
            "minecraft:wooden_slabs", "minecraft:wooden_fences", "minecraft:fence_gates", "minecraft:wooden_doors",
            "minecraft:wooden_trapdoors", "minecraft:wooden_buttons", "minecraft:wooden_pressure_plates",
            "c:logs", "c:planks", "c:stripped_logs", "c:stripped_woods");
    private static final List<String> MINERAL_TAGS = List.of("minecraft:coal_ores", "minecraft:iron_ores", "minecraft:copper_ores",
            "minecraft:gold_ores", "minecraft:redstone_ores", "minecraft:lapis_ores", "minecraft:diamond_ores", "minecraft:emerald_ores",
            "c:ores", "c:raw_materials", "c:ingots", "c:nuggets", "c:gems");
    private static final List<String> MINERALS = List.of("coal", "charcoal", "raw_iron", "raw_copper", "raw_gold",
            "iron_ingot", "copper_ingot", "gold_ingot", "netherite_ingot", "iron_nugget", "gold_nugget", "diamond", "emerald",
            "lapis_lazuli", "redstone", "quartz", "amethyst_shard", "netherite_scrap", "ancient_debris", "nether_gold_ore",
            "nether_quartz_ore", "coal_block", "iron_block", "copper_block", "gold_block", "netherite_block", "diamond_block",
            "emerald_block", "lapis_block", "redstone_block", "raw_iron_block", "raw_copper_block", "raw_gold_block", "amethyst_block");
    private final boolean wood, minerals;
    private final Set<String> keep, discard;
    private static final Set<String> WOOD_IDS = woodIds();
    private static final Set<String> MINERAL_IDS = mineralIds();

    public DiscardPolicy(boolean wood, boolean minerals, Set<String> keep, Set<String> discard) {
        if (keep.size() > MAX_EXCEPTIONS || discard.size() > MAX_EXCEPTIONS)
            throw new IllegalArgumentException("单件例外最多各 " + MAX_EXCEPTIONS + " 种");
        this.wood = wood;
        this.minerals = minerals;
        this.keep = Set.copyOf(keep);
        this.discard = Set.copyOf(discard);
        if (!java.util.Collections.disjoint(keep, discard)) throw new IllegalArgumentException("同一物品不能同时保护和允许丢弃");
        for (String id : java.util.stream.Stream.concat(keep.stream(), discard.stream()).toList()) {
            if (ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException("物品 ID 无效: " + id);
        }
    }
    public boolean wood() { return wood; }
    public boolean minerals() { return minerals; }
    public Set<String> keep() { return keep; }
    public Set<String> discard() { return discard; }

    /** 单件选择优先于分类;只决定清单成员,放行和拒绝由权限规则裁决。 */
    public boolean protects(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        if (keep.contains(id)) return true;
        if (discard.contains(id)) return false;
        return wood && (WOOD_IDS.contains(id) || tagged(item, WOOD_TAGS))
                || minerals && (MINERAL_IDS.contains(id) || tagged(item, MINERAL_TAGS));
    }
    private static boolean tagged(Item item, List<String> tags) {
        return tags.stream().anyMatch(tag -> item.builtInRegistryHolder()
                .is(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag))));
    }
    private static Set<String> woodIds() {
        Set<String> out = new TreeSet<>();
        for (String type : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "crimson", "warped", "bamboo")) {
            for (String suffix : List.of("planks", "stairs", "slab", "fence", "fence_gate", "door", "trapdoor", "button", "pressure_plate"))
                out.add("minecraft:" + type + "_" + suffix);
            for (String suffix : List.of("log", "wood", "stem", "hyphae", "block")) {
                out.add("minecraft:" + type + "_" + suffix);
                out.add("minecraft:stripped_" + type + "_" + suffix);
            }
        }
        out.addAll(List.of("minecraft:bamboo", "minecraft:bamboo_mosaic", "minecraft:bamboo_mosaic_stairs",
                "minecraft:bamboo_mosaic_slab", "minecraft:stick"));
        return Set.copyOf(out);
    }
    private static Set<String> mineralIds() {
        Set<String> out = new TreeSet<>();
        MINERALS.forEach(id -> out.add("minecraft:" + id));
        for (String ore : List.of("coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald")) {
            out.add("minecraft:" + ore + "_ore"); out.add("minecraft:deepslate_" + ore + "_ore");
        }
        return Set.copyOf(out);
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("wood", wood); o.addProperty("minerals", minerals);
        JsonArray k = new JsonArray(), d = new JsonArray();
        new TreeSet<>(keep).forEach(k::add); new TreeSet<>(discard).forEach(d::add);
        o.add("keep", k); o.add("discard", d);
        return o;
    }

    /** 每轮运行态直接显示身上哪些物品受保护;完整单件例外按需经只读 API 查询。 */
    public static String describe(com.dwinovo.numen.api.entity.NumenPlayer her) {
        DiscardPolicy policy = her.getOwnerUuid() == null ? DEFAULT
                : PermissionStore.of(her.getServer(), her.getOwnerUuid()).discardOf(her.getUUID());
        Set<String> protectedCarried = new TreeSet<>();
        for (int slot = 0; slot < her.getInventory().getContainerSize(); slot++) {
            var stack = her.getInventory().getItem(slot);
            if (!stack.isEmpty() && policy.protects(stack.getItem()))
                protectedCarried.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return "<discard_policy>Protected categories: wood=" + policy.wood + ", minerals=" + policy.minerals
                + "; protected carried item types=" + protectedCarried
                + ". Other item types may be discarded as needed unless other permission rules restrict it. "
                + "Keep useful food, tools and task supplies. Full settings: numen.permission.discard_settings().</discard_policy>";
    }

    /** 客户端和服务器共同读取;服务器写入必须检查物品确实已注册,不接受任意规则表达式。 */
    public static DiscardPolicy fromJson(JsonObject o, boolean validateItems) {
        return new DiscardPolicy(flag(o, "wood"), flag(o, "minerals"),
                ids(o, "keep", validateItems), ids(o, "discard", validateItems));
    }
    private static boolean flag(JsonObject o, String key) {
        var e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " 必须是开关值");
        return e.getAsBoolean();
    }
    private static Set<String> ids(JsonObject o, String key, boolean validate) {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw new IllegalArgumentException(key + " 必须是物品列表");
        JsonArray values = o.getAsJsonArray(key);
        if (values.size() > MAX_EXCEPTIONS) throw new IllegalArgumentException("单件例外过多");
        Set<String> out = new TreeSet<>();
        for (var value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("物品 ID 必须是文字");
            String text = value.getAsString();
            ResourceLocation id = ResourceLocation.tryParse(text);
            if (id == null || text.length() > 256 || validate && (!BuiltInRegistries.ITEM.containsKey(id)
                    || BuiltInRegistries.ITEM.get(id) == Items.AIR)) throw new IllegalArgumentException("未知物品: " + text);
            out.add(id.toString());
        }
        return out;
    }
}
