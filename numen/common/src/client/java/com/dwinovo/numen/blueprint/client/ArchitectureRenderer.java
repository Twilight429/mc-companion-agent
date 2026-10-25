package com.dwinovo.numen.blueprint.client;

import com.dwinovo.numen.blueprint.ArchitectureViewPayload;
import com.dwinovo.numen.blueprint.ProjectionLayers;
import com.dwinovo.numen.build.BuildValidity;
import com.dwinovo.numen.build.Placement;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** 原版方块模型与材质的半透明投影。只画已收到的稿,不向客户端世界写入假方块。 */
public final class ArchitectureRenderer {
    private record Ghost(BlockPos pos, BlockState state, boolean unknown) {}
    private static List<Ghost> cells = List.of();
    private static String dimension = "";
    private static long observedTick=Long.MIN_VALUE;
    private static List<Ghost> remaining=List.of();
    private static OptionalInt lowest=OptionalInt.empty();
    private ArchitectureRenderer() {}
    public static void update(ArchitectureViewPayload view) {
        Placement placement = new Placement(view.origin(), view.quarters());
        cells = view.cells().stream().map(c -> new Ghost(placement.cell(c.relative()),
                placement.turn(Block.stateById(c.state())), c.unknown())).toList();
        dimension = view.dimension();
        observedTick=Long.MIN_VALUE; remaining=List.of(); lowest=OptionalInt.empty();
    }
    public static void render(PoseStack pose, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (cells.isEmpty() || mc.level == null || !mc.level.dimension().location().toString().equals(dimension)) return;
        // 只核对客户端已加载的方块;未加载的格不能被当作完成。
        if(observedTick!=mc.level.getGameTime()) {
            observedTick=mc.level.getGameTime();
            remaining=cells.stream().filter(cell->cell.unknown()||!mc.level.hasChunkAt(cell.pos())
                    ||!BuildValidity.valid(mc.level.getBlockState(cell.pos()),cell.state(),false)).toList();
            lowest=ProjectionLayers.lowestUnfinished(remaining,cell->cell.pos().getY(),cell->false);
        }
        boolean whole=!ArchitectureClient.view.confirmed()||ArchitectureClient.positioning||ArchitectureClient.fullProjection;
        // 定位时总览全图;确认后仅隐藏已完成格,当前层完成就自然进入上一层。
        List<Ghost> candidates=ArchitectureClient.positioning||!ArchitectureClient.view.confirmed()?cells:remaining;
        var buffers = mc.renderBuffers().bufferSource();
        RenderType ghostType = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
        VertexConsumer tinted = new Alpha(buffers.getBuffer(ghostType));
        MultiBufferSource ghostBuffers = ignored -> tinted;
        var cam = camera.getPosition();
        pose.pushPose(); pose.translate(-cam.x, -cam.y, -cam.z);
        List<Ghost> visible = new ArrayList<>();
        for (Ghost cell : candidates) {
            if(!whole&&(lowest.isEmpty()||cell.pos().getY()!=lowest.getAsInt())) continue;
            if (cell.pos().distToCenterSqr(cam) > 128 * 128) continue;
            visible.add(cell);
            if (visible.size() >= 6000) break;
        }
        for (Ghost cell : visible) {
            if (cell.unknown() || cell.state().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
            pose.pushPose(); pose.translate(cell.pos().getX(), cell.pos().getY(), cell.pos().getZ());
            mc.getBlockRenderer().renderSingleBlock(cell.state(), pose, ghostBuffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        buffers.endBatch(ghostType);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (Ghost cell : visible) {
            if (cell.unknown() || cell.state().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
                BlockPos p = cell.pos();
                LevelRenderer.renderLineBox(pose, lines, p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                        cell.unknown() ? 1f : 0.2f, 0.35f, 1f, 0.8f);
            }
        }
        buffers.endBatch(RenderType.lines()); pose.popPose();
    }
    private record Alpha(VertexConsumer delegate) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, a * 96 / 255); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
    }
}
