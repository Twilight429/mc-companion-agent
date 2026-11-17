package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.client.camera.NpcCameraRenderer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;
import java.util.List;

/** 离屏帧单独按 NPC 视锥筛地形,不能沿用从主人位置计算的遮挡图。 */
@Mixin(LevelRenderer.class)
public abstract class MixinNpcCameraTerrain implements NpcCameraRenderer.TerrainSlot {
    @Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Shadow private ViewArea viewArea;
    @Unique private List<SectionRenderDispatcher.RenderSection> numen$savedSections;

    @Override public void numen$beginCamera() {
        numen$savedSections = new ArrayList<>(visibleSections);
    }

    @Override public void numen$endCamera(Vec3 originalEye) {
        if (numen$savedSections == null) return;
        visibleSections.clear(); visibleSections.addAll(numen$savedSections);
        numen$savedSections = null;
        sectionRenderDispatcher.setCamera(originalEye);
    }

    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void numen$cameraTerrain(Camera camera, Frustum frustum, boolean captured, boolean spectator, CallbackInfo ci) {
        if (numen$savedSections == null) return;
        visibleSections.clear();
        sectionRenderDispatcher.setCamera(camera.getPosition());
        var level = Minecraft.getInstance().level;
        if (viewArea != null && level != null) {
            for (var section : viewArea.sections) {
                // 只使用客户端实际收到的区块;深度缓冲负责物体遮挡,不查询服务端隐藏资源。
                if (level.hasChunkAt(section.getOrigin()) && frustum.isVisible(section.getBoundingBox()))
                    visibleSections.add(section);
            }
        }
        ci.cancel();
    }
}
