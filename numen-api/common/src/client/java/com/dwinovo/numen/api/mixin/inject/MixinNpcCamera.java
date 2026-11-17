package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.client.camera.NpcCameraRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinNpcCamera implements NpcCameraRenderer.CameraSlot {
    @Shadow @Final @Mutable private Camera mainCamera;
    @Override public void numen$camera(Camera camera) { mainCamera = camera; }

    @Inject(method = "render", at = @At("TAIL"))
    private void numen$capture(DeltaTracker delta, boolean renderWorld, CallbackInfo ci) {
        if (renderWorld) NpcCameraRenderer.render(delta);
    }
}
