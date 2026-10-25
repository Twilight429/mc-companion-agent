package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.build.BuildStates;
import com.dwinovo.numen.build.Design;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 原创设计只写独立的本地蓝图版本;世界投影、确认和施工仍走同一条原生通道。 */
final class ArchitectureDesigns {
    static final int MAX_CELLS = 32768;
    private ArchitectureDesigns() {}

    static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(c -> c < 32 && c != 10))
            throw new IllegalArgumentException(field + " must be nonblank text, at most " + max + " characters");
        return value;
    }

    static String style(String style) {
        if (style == null || !style.matches("[a-z_]+") || style.equals("decoration")
                || ArchitectureDesigns.class.getResource("/numen/skills/numen/building_design/references/" + style + ".md") == null)
            throw new IllegalArgumentException("Use an actual building_design reference id, e.g. medieval_rustic, modern_minimalist or chinese_classical");
        return style;
    }

    /** Validate an entire plan before writing it; no commands, NBT payloads or world changes are accepted. */
    static CompoundTag structure(Design design, UUID owner, String style) {
        style(style);
        if (design.cells() == null || design.cells().isEmpty() || design.cells().size() > MAX_CELLS)
            throw new IllegalArgumentException("Submit 1..32768 local Cells, not an existing blueprint");
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (var cell : design.cells()) {
            local(cell.pos());
            if (cell.block() == null) throw new IllegalArgumentException("Every design cell needs a block name/state");
            BlockState state = BuildStates.normalize(BuildStates.resolve(cell.block()).state());
            if (cells.putIfAbsent(cell.pos(), state) != null)
                throw new IllegalArgumentException("Duplicate design cell at " + cell.pos().toShortString() + "; combine shapes with union first");
        }
        // The same door/bed placed by the native executor creates its second half. Show and price that same object.
        for (var entry : List.copyOf(cells.entrySet())) {
            var state = entry.getValue();
            if (BuildStates.isSecondaryHalf(state)) continue;
            BlockPos partner = partner(entry.getKey(), state);
            if (partner == null) continue;
            local(partner);
            BlockState other = counterpart(state);
            BlockState old = cells.putIfAbsent(partner, other);
            if (old != null && !old.equals(other)) throw new IllegalArgumentException("Door/bed second half collides at " + partner.toShortString());
        }
        for (var entry : cells.entrySet()) {
            if (!BuildStates.isSecondaryHalf(entry.getValue())) continue;
            BlockPos partner = partner(entry.getKey(), entry.getValue());
            if (!counterpart(entry.getValue()).equals(cells.get(partner)))
                throw new IllegalArgumentException("Orphan door/bed upper/head cell at " + entry.getKey().toShortString());
        }
        if (cells.size() > MAX_CELLS || cells.values().stream().allMatch(BlockState::isAir))
            throw new IllegalArgumentException("Design must include solid content and at most 32768 cells including second halves");
        ListTag palette = new ListTag(), blocks = new ListTag();
        Map<BlockState, Integer> states = new LinkedHashMap<>();
        int sx = 1, sy = 1, sz = 1;
        for (var entry : cells.entrySet()) {
            BlockPos pos = entry.getKey();
            int index = states.computeIfAbsent(entry.getValue(), state -> {
                int i = palette.size(); palette.add(NbtUtils.writeBlockState(state)); return i;
            });
            CompoundTag block = new CompoundTag(); block.put("pos", ints(pos.getX(), pos.getY(), pos.getZ()));
            block.putInt("state", index); blocks.add(block);
            sx = Math.max(sx, pos.getX() + 1); sy = Math.max(sy, pos.getY() + 1); sz = Math.max(sz, pos.getZ() + 1);
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        tag.put("size", ints(sx, sy, sz)); tag.put("palette", palette); tag.put("blocks", blocks); tag.put("entities", new ListTag());
        CompoundTag meta = new CompoundTag(); meta.putUUID("NumenGeneratedOwner", owner); meta.putString("Style", style);
        tag.put("Metadata", meta);
        return tag;
    }

    static void local(BlockPos pos) {
        if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() > 255 || pos.getY() > 255 || pos.getZ() > 255)
            throw new IllegalArgumentException("Design positions are LOCAL cells in 0..255, not world coordinates");
    }
    private static ListTag ints(int... values) {
        ListTag out = new ListTag(); for (int n : values) out.add(IntTag.valueOf(n)); return out;
    }
    private static BlockPos partner(BlockPos pos, BlockState state) {
        if (state.hasProperty(BlockStateProperties.BED_PART))
            return pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING), state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? 1 : -1);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        return null;
    }
    private static BlockState counterpart(BlockState state) {
        if (state.hasProperty(BlockStateProperties.BED_PART)) return state.setValue(BlockStateProperties.BED_PART,
                state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT);
        return state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
    }

    static List<Design.Cell> read(CompoundTag tag) {
        ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
        List<Design.Cell> cells = new ArrayList<>();
        for (Tag raw : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag block = (CompoundTag) raw; ListTag p = block.getList("pos", Tag.TAG_INT);
            var state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), palette.getCompound(block.getInt("state")));
            cells.add(new Design.Cell(new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2)), BlockStateParser.serialize(state)));
        }
        return List.copyOf(cells);
    }

    static Design patch(List<Design.Cell> source, Optional<Design> changes, List<BlockPos> remove) {
        if (changes.isEmpty() && remove.isEmpty()) throw new IllegalArgumentException("Give changed Cells or positions to remove");
        Map<BlockPos, Design.Cell> cells = new LinkedHashMap<>();
        for (var cell : source) cells.put(cell.pos(), cell);
        for (var pos : remove) { local(pos); removeObject(cells, pos); }
        if (changes.isPresent()) {
            var edit = changes.get();
            if (edit.cells() == null || edit.cells().size() > MAX_CELLS) throw new IllegalArgumentException("changes must be local Cells");
            Set<BlockPos> changed = new HashSet<>();
            for (var cell : edit.cells()) {
                local(cell.pos());
                if (cell.block() == null || !changed.add(cell.pos())) throw new IllegalArgumentException("Invalid or duplicate changed cell");
                removeObject(cells, cell.pos());
            }
            for (var cell : edit.cells()) cells.put(cell.pos(), cell);
        }
        return new Design(List.copyOf(cells.values()), null);
    }
    private static void removeObject(Map<BlockPos, Design.Cell> cells, BlockPos pos) {
        var old = cells.remove(pos);
        if (old == null) return;
        var state = BuildStates.resolve(old.block()).state();
        var other = partner(pos, state);
        if (other != null) {
            var pair = cells.get(other);
            if (pair != null && BuildStates.resolve(pair.block()).state().equals(counterpart(state))) cells.remove(other);
        }
    }

    static ArchitectureDrafts.Draft current(ServerPlayer owner, String revision) {
        var draft = ArchitectureDrafts.get(owner);
        if (draft == null || !draft.revision().toString().equals(revision))
            throw new IllegalArgumentException("投影已更新或不存在，请先读取 architecture.draft，勿覆盖玩家的新调整");
        if (!draft.dimension().equals(owner.serverLevel().dimension().location().toString()))
            throw new IllegalArgumentException("草稿在另一个维度，请返回原维度修改");
        if (!draft.building().fingerprint().equals(ArchitectureCatalog.fingerprint(owner.server, draft.building().id())))
            throw new IllegalArgumentException("蓝图文件已改变，请先刷新后重新预览");
        return draft;
    }

    static ArchitectureDrafts.Draft publish(NumenPlayer body, String title, String style, String request,
                                           Design design, BlockPos origin, int quarters, Optional<String> revision) {
        ServerPlayer owner = body.server.getPlayerList().getPlayer(body.getOwnerUuid());
        if (owner == null || owner.serverLevel() != body.serverLevel() || !Services.network().canSend(owner, ArchitectureViewPayload.TYPE))
            throw new IllegalArgumentException("主人须在线、同维度且安装同版本投影客户端");
        var old = ArchitectureDrafts.get(owner);
        if (old != null) current(owner, revision.orElse(""));
        else if (revision.isPresent()) throw new IllegalArgumentException("旧投影已被取消，请重新读取草稿状态");
        text(title, "title", 160); text(request, "request", 1200);
        CompoundTag tag = structure(design, owner.getUUID(), style);
        var visual = read(tag).stream().map(c -> new ArchitectureCatalog.Cell(c.pos(), BuildStates.resolve(c.block()).state(), false)).toList();
        ArchitectureDrafts.checkBounds(owner, origin, quarters, visual);
        String name = "ai_" + owner.getUUID().toString().replace("-", "") + "_" + UUID.randomUUID().toString().replace("-", "");
        JsonObject meta = new JsonObject();
        meta.addProperty("title", title); meta.addProperty("author", "AI 辅助设计：" + body.getName().getString() + " / " + owner.getName().getString());
        meta.addProperty("sourceUrl", "Numen 本地原创草稿；风格参考 building_design/" + style);
        meta.addProperty("license", "AI 辅助生成，不冒充下载作品；如参考第三方作品须另行注明来源与使用范围。");
        meta.addProperty("description", request);
        var tags = new com.google.gson.JsonArray(); tags.add("AI生成"); tags.add(style); meta.add("tags", tags);
        if (old != null) meta.addProperty("previousDraft", old.building().id());
        Path dir = BlueprintStore.dir(body.server), file = dir.resolve(name + ".nbt"), sidecar = dir.resolve(name + ".catalog.json");
        // Immutable names preserve old accepted designs and never overwrite downloaded/user files.
        try {
            try (var out = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW)) { NbtIo.writeCompressed(tag, out); }
            Files.writeString(sidecar, new Gson().toJson(meta), java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        ArchitectureCatalog.index(body.serverLevel(), name);
        var draft = ArchitectureDrafts.preview(owner, name, origin, quarters);
        owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("[建筑草稿] " + title + " · 仅投影，未施工。P 移到准星处 / 方向键微调（Shift 加速）/ PageUp、PageDown 高度 / R 居中旋转 / Enter 确认；也可继续对话修改。"));
        return draft;
    }

    static boolean owned(CompoundTag tag, UUID owner) {
        var meta = tag.getCompound("Metadata");
        return meta.hasUUID("NumenGeneratedOwner") && meta.getUUID("NumenGeneratedOwner").equals(owner);
    }
}
