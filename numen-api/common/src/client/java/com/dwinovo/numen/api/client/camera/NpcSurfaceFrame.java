package com.dwinovo.numen.api.client.camera;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.dwinovo.numen.api.brain.NpcCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;

/** 仅在对外请求时读取同一离屏画布的深度,原生方块射线与渲染深度一致才建立对应。 */
final class NpcSurfaceFrame {
    static JsonObject capture(Minecraft mc, Entity npc, Camera eye, Matrix4f view,
                              Matrix4f projection, UUID frameId, byte[] png) {
        int width = NpcCamera.WIDTH, height = NpcCamera.HEIGHT;
        FloatBuffer depth = MemoryUtil.memAllocFloat(width * height);
        try {
            GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            if (GL11.glGetError()!=GL11.GL_NO_ERROR) throw new IllegalStateException("Cannot read NPC render depth");
            Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
            JsonObject frame = new JsonObject();
            frame.addProperty("schema", "numen.surface_frame.v1");
            frame.addProperty("frame_id", frameId.toString());
            frame.addProperty("world_session", NpcCameraRenderer.sessionId());
            frame.addProperty("companion", npc.getUUID().toString());
            String dimension = mc.level.dimension().location().toString();
            frame.addProperty("dimension", dimension);
            frame.addProperty("client_game_time", mc.level.getGameTime());
            frame.addProperty("captured_at_ms", System.currentTimeMillis());
            frame.addProperty("width", width); frame.addProperty("height", height);
            frame.addProperty("sample_width", 160); frame.addProperty("sample_height", 90);
            frame.addProperty("pixel_convention", "top-left; sample(u,v) uses RGB pixel (4*u+2,4*v+2) center");
            frame.addProperty("depth_convention", "OpenGL nonlinear [0,1]; 1=far/unknown; sampled after opaque and entity rendering");
            frame.addProperty("matrix_convention", "column-major; view is rotation only; add eye after inverse(projection*view)");
            frame.addProperty("surface_gate", "client-loaded solid-render block; collider hit within 0.03 blocks of rendered depth; grid 32x16x32");
            frame.addProperty("limitations", "No mesh-ready or shader compatibility guarantee; block cells are not whole-tree instances; unsupported/translucent surfaces have no association");
            frame.addProperty("rgb_png_base64", Base64.getEncoder().encodeToString(png));
            Vec3 origin = eye.getPosition();
            frame.add("eye", array(origin.x, origin.y, origin.z));
            frame.add("projection", matrix(projection)); frame.add("view_rotation", matrix(view));
            BlockPos center = npc.blockPosition();
            frame.add("grid_origin", array(center.getX()-16, center.getY()-8, center.getZ()-16));
            frame.add("grid_shape", array(32,16,32));
            JsonArray samples = new JsonArray();
            var objects = new LinkedHashMap<BlockPos, JsonObject>();
            for (int v=0; v<90; v++) for (int u=0; u<160; u++) {
                int px=4*u+2, py=4*v+2;
                float z=depth.get((height-1-py)*width+px);
                samples.add(Float.isFinite(z) ? z : 1f);
                if (!Float.isFinite(z) || z<=0 || z>=1) continue;
                var relative=CameraProjection.unproject(inverse,px,py,width,height,z);
                Vec3 point=origin.add(relative.x,relative.y,relative.z);
                if (!Double.isFinite(point.lengthSqr()) || origin.distanceToSqr(point)>48*48) continue;
                Vec3 end=point.add(point.subtract(origin).normalize().scale(.05));
                var hit=mc.level.clip(new ClipContext(origin,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,npc));
                if (hit.getType()!=HitResult.Type.BLOCK || hit.getLocation().distanceToSqr(point)>.03*.03) continue;
                BlockPos pos=hit.getBlockPos();
                // 方块边缘容易混合两个表面;保留面内至少 0.08 格的点。
                Vec3 location=hit.getLocation();
                double fx=location.x-pos.getX(), fy=location.y-pos.getY(), fz=location.z-pos.getZ();
                var axis=hit.getDirection().getAxis();
                if ((axis!=net.minecraft.core.Direction.Axis.X && (fx<.08 || fx>.92))
                        || (axis!=net.minecraft.core.Direction.Axis.Y && (fy<.08 || fy>.92))
                        || (axis!=net.minecraft.core.Direction.Axis.Z && (fz<.08 || fz>.92))) continue;
                if (pos.getX()<center.getX()-16 || pos.getX()>=center.getX()+16
                        || pos.getY()<center.getY()-8 || pos.getY()>=center.getY()+8
                        || pos.getZ()<center.getZ()-16 || pos.getZ()>=center.getZ()+16
                        || !mc.level.hasChunkAt(pos)) continue;
                var state=mc.level.getBlockState(pos);
                if (!state.isSolidRender(mc.level,pos)) continue;
                JsonObject object=objects.computeIfAbsent(pos.immutable(), key -> {
                    JsonObject o=new JsonObject();
                    o.addProperty("object_id",NpcCameraRenderer.sessionId()+":"+dimension+":"+key.getX()+","+key.getY()+","+key.getZ());
                    o.add("block_pos",array(key.getX(),key.getY(),key.getZ()));
                    o.addProperty("native_block_id",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    o.addProperty("native_block_state",state.toString());
                    o.add("sample_indices",new JsonArray()); return o;
                });
                object.getAsJsonArray("sample_indices").add(v*160+u);
            }
            frame.add("depth_gl",samples);
            JsonArray rows=new JsonArray(); objects.values().forEach(rows::add); frame.add("objects",rows);
            return frame;
        } finally { MemoryUtil.memFree(depth); }
    }

    private static JsonArray matrix(Matrix4f m) {
        JsonArray a=new JsonArray(); for(float f:m.get(new float[16])) a.add(f); return a;
    }
    private static JsonArray array(double... values) {
        JsonArray a=new JsonArray(); for(double v:values) a.add(v); return a;
    }
}
