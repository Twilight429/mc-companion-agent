package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.client.camera.CameraTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.*;

@Mixin(Minecraft.class)
public abstract class MixinCameraTarget implements CameraTarget {
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    @Override public void numen$cameraTarget(RenderTarget target) { this.mainRenderTarget = target; }
}
