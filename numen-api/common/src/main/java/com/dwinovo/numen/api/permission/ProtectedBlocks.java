package com.dwinovo.numen.api.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 每维度的显式方块保护记录。只提供事实,是否能拆仍由权限规则裁决。 */
public final class ProtectedBlocks extends SavedData {
    private static final Factory<ProtectedBlocks> FACTORY = new Factory<>(ProtectedBlocks::new,
            ProtectedBlocks::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);
    // 每格的集合发布后不再修改,搜索线程与主线程都可查询。
    private final ConcurrentHashMap<Long, Set<UUID>> cells = new ConcurrentHashMap<>();

    public static ProtectedBlocks of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "numen_protected_blocks");
    }

    public boolean contains(BlockPos pos) { return cells.containsKey(pos.asLong()); }
    public boolean contains(UUID owner, BlockPos pos) {
        return cells.getOrDefault(pos.asLong(), Set.of()).contains(owner);
    }

    /** 内容在主线程登记已经完成的方块;不挖掘、不放置、不加载区块。 */
    public void protect(UUID owner, Collection<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (contains(owner, pos)) continue;
            cells.compute(pos.asLong(), (key, previous) -> {
                Set<UUID> next = new HashSet<>(previous == null ? Set.of() : previous);
                next.add(owner);
                return Set.copyOf(next);
            });
            setDirty();
        }
    }

    /** 只能撤回这位主人的保护;同格上其他主人的保护不变。由认证的玩家操作入口调用。 */
    public void release(UUID owner, Collection<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (!contains(owner, pos)) continue;
            cells.computeIfPresent(pos.asLong(), (key, previous) -> {
                Set<UUID> next = new HashSet<>(previous);
                next.remove(owner);
                return next.isEmpty() ? null : Set.copyOf(next);
            });
            setDirty();
        }
    }

    static ProtectedBlocks load(CompoundTag tag, HolderLookup.Provider registries) {
        ProtectedBlocks result = new ProtectedBlocks();
        ListTag records = tag.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < records.size(); i++) {
            CompoundTag row = records.getCompound(i);
            if (!row.hasUUID("owner")) continue;
            UUID owner = row.getUUID("owner");
            for (long pos : row.getLongArray("positions"))
                result.protect(owner, java.util.List.of(BlockPos.of(pos)));
        }
        result.setDirty(false);
        return result;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        java.util.Map<UUID, java.util.List<Long>> byOwner = new java.util.HashMap<>();
        cells.forEach((pos, owners) -> owners.forEach(owner -> byOwner.computeIfAbsent(owner,
                ignored -> new java.util.ArrayList<>()).add(pos)));
        ListTag records = new ListTag();
        byOwner.forEach((owner, positions) -> {
            CompoundTag row = new CompoundTag();
            row.putUUID("owner", owner);
            row.putLongArray("positions", positions);
            records.add(row);
        });
        tag.put("cells", records);
        return tag;
    }
}
