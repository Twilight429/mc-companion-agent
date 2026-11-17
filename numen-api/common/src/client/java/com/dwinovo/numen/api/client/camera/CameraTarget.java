package com.dwinovo.numen.api.client.camera;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** 临时更换离屏绘制的落点,不切玩家相机、声音和鼠标。 */
public interface CameraTarget {
    void numen$cameraTarget(RenderTarget target);
}
