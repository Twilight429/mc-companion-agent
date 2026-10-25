package com.dwinovo.numen.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import java.util.*;
import java.util.function.Consumer;

/** 完整的只读视觉稿,不发送方块实体内容物、不改世界。方块索引与普通区块用同一注册表。 */
public record ArchitectureViewPayload(UUID revision, String name, String dimension, BlockPos origin, int quarters,
                                      boolean confirmed, boolean compatible, String credit, List<Cell> cells)
        implements CustomPacketPayload {
    public record Cell(BlockPos relative, int state, boolean unknown) {}
    public static final UUID NONE = new UUID(0, 0);
    public static final Type<ArchitectureViewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("numen", "architecture_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArchitectureViewPayload> STREAM_CODEC = StreamCodec.of(
            ArchitectureViewPayload::write, ArchitectureViewPayload::read);
    public static volatile Consumer<ArchitectureViewPayload> onClient = ignored -> {};
    public static void handle(ArchitectureViewPayload payload) { onClient.accept(payload); }
    public static ArchitectureViewPayload empty() {
        return new ArchitectureViewPayload(NONE, "", "", BlockPos.ZERO, 0, false, false, "", List.of());
    }
    public static ArchitectureViewPayload of(ArchitectureDrafts.Draft draft) {
        return new ArchitectureViewPayload(draft.revision(), draft.building().id(), draft.dimension(), draft.origin(),
                draft.quarters(), draft.confirmed(), draft.building().compatible(), ArchitectureDrafts.credit(draft.building()),
                draft.cells().stream().map(c -> new Cell(c.pos(), Block.getId(c.state()), c.unknown())).toList());
    }
    private static void write(RegistryFriendlyByteBuf b, ArchitectureViewPayload p) {
        b.writeUUID(p.revision()); b.writeUtf(p.name(), 160); b.writeUtf(p.dimension(), 256);
        b.writeBlockPos(p.origin()); b.writeVarInt(p.quarters()); b.writeBoolean(p.confirmed()); b.writeBoolean(p.compatible());
        b.writeUtf(p.credit(), 2048); b.writeVarInt(p.cells().size());
        for (Cell cell : p.cells()) { b.writeBlockPos(cell.relative()); b.writeVarInt(cell.state()); b.writeBoolean(cell.unknown()); }
    }
    private static ArchitectureViewPayload read(RegistryFriendlyByteBuf b) {
        UUID revision = b.readUUID(); String name = b.readUtf(160), dimension = b.readUtf(256);
        BlockPos origin = b.readBlockPos(); int rotation = b.readVarInt(); boolean confirmed = b.readBoolean(), compatible = b.readBoolean();
        String credit = b.readUtf(2048); int size = b.readVarInt();
        if (size < 0 || size > 32768 || rotation < 0 || rotation > 3) throw new IllegalArgumentException("invalid architecture view");
        List<Cell> cells = new ArrayList<>(size);
        for (int i = 0; i < size; i++) cells.add(new Cell(b.readBlockPos(), b.readVarInt(), b.readBoolean()));
        return new ArchitectureViewPayload(revision, name, dimension, origin, rotation, confirmed, compatible, credit, List.copyOf(cells));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
