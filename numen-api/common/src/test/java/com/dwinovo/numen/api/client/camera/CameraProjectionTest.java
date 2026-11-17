package com.dwinovo.numen.api.client.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraProjectionTest {
    @Test void depthRecoversKnownWorldPointWithRotatedCameraAndTopLeftPixels() {
        Matrix4f view=new Matrix4f().rotationY(.7f).rotateX(-.2f);
        Matrix4f projection=new Matrix4f().perspective((float)Math.toRadians(70),640f/360,.05f,256f);
        Matrix4f pv=new Matrix4f(projection).mul(view);
        Matrix4f inverse=new Matrix4f(pv).invert();
        for(int[] pixel:new int[][]{{322,182},{2,2},{638,358}}) {
            Vector3f ray=CameraProjection.unproject(inverse,pixel[0],pixel[1],640,360,.99f).normalize(6f);
            Vector3f ndc=pv.transformProject(new Vector3f(ray));
            Vector3f restored=CameraProjection.unproject(inverse,pixel[0],pixel[1],640,360,(ndc.z+1)/2);
            assertEquals(ray.x,restored.x,.0002);
            assertEquals(ray.y,restored.y,.0002);
            assertEquals(ray.z,restored.z,.0002);
        }
        Matrix4f p=new Matrix4f().perspective((float)Math.toRadians(70),640f/360,.05f,256f).invert();
        assertTrue(CameraProjection.unproject(p,2,2,640,360,.9f).y>0);
        assertTrue(CameraProjection.unproject(p,2,358,640,360,.9f).y<0);
    }
}
