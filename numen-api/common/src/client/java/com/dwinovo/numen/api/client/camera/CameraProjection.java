package com.dwinovo.numen.api.client.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** OpenGL 深度和左上原点像素中心还原为相对相机的世界坐标。 */
public final class CameraProjection {
    private CameraProjection() {}

    public static Vector3f unproject(Matrix4f inverseProjectionView, int x, int y,
                                    int width, int height, float depth) {
        return inverseProjectionView.transformProject(new Vector3f(
                2f * (x + .5f) / width - 1f, 1f - 2f * (y + .5f) / height,
                2f * depth - 1f));
    }
}
