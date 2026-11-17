package com.dwinovo.numen.api.client.camera;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.NpcCamera;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.*;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayDeque;

/** 仅在规划请求时画一帧世界,不画 HUD、菜单或玩家的手。一次主画面最多处理一个请求。 */
public final class NpcCameraRenderer {
    public interface CameraSlot { void numen$camera(Camera camera); }
    public interface TerrainSlot {
        void numen$beginCamera();
        void numen$endCamera(Vec3 originalEye);
    }
    private record Queued(NpcCamera.Request request, long queuedAt) {}
    private static final ArrayDeque<Queued> QUEUE = new ArrayDeque<>();
    private static boolean rendering;
    private static TextureTarget target;
    private static java.util.UUID session=java.util.UUID.randomUUID();
    static String sessionId() { return session.toString(); }
    private static final java.util.Map<java.util.UUID, java.util.concurrent.CompletableFuture<com.google.gson.JsonObject>> LOCAL = new java.util.HashMap<>();
    private NpcCameraRenderer() {}

    public static void accept(NpcCamera.Request request) {
        if (!com.dwinovo.numen.api.client.data.ClientPrefs.experimentalVisionEnabled()) {
            fail(request,NpcCamera.DISABLED); return;
        }
        if (QUEUE.size() >= 32) { fail(request,"client camera queue full"); return; }
        QUEUE.addLast(new Queued(request,System.nanoTime()));
    }

    public static void acceptLocal(java.util.UUID companion, java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> result) {
        if (!com.dwinovo.numen.api.client.data.ClientPrefs.experimentalVisionEnabled()) {
            result.completeExceptionally(new IllegalStateException("Visual experiments are disabled")); return;
        }
        Minecraft mc=Minecraft.getInstance();
        if (result.isDone()) return;
        if (mc.level==null || QUEUE.size()>=32) {
            result.completeExceptionally(new IllegalStateException("No world loaded or camera queue full")); return;
        }
        var id=java.util.UUID.randomUUID();
        LOCAL.put(id,result);
        accept(new NpcCamera.Request(id,companion,mc.level.dimension().location().toString()));
    }

    public static void clear() {
        session=java.util.UUID.randomUUID();
        LOCAL.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Client disconnected")));
        LOCAL.clear();
        QUEUE.clear();
        if (target != null) { target.destroyBuffers(); target = null; }
    }

    public static void disable() {
        while (!QUEUE.isEmpty()) fail(QUEUE.removeFirst().request(),NpcCamera.DISABLED);
        clear();
    }

    public static void render(DeltaTracker delta) {
        if (!com.dwinovo.numen.api.client.data.ClientPrefs.experimentalVisionEnabled()) {
            while (!QUEUE.isEmpty()) fail(QUEUE.removeFirst().request(),NpcCamera.DISABLED);
            return;
        }
        if (rendering || QUEUE.isEmpty()) return;
        Queued next = QUEUE.removeFirst();
        NpcCamera.Request request = next.request();
        var local=LOCAL.get(request.id());
        if (local!=null && local.isDone()) { LOCAL.remove(request.id()); return; }
        Minecraft mc = Minecraft.getInstance();
        if ((System.nanoTime()-next.queuedAt())/1_000_000 > NpcCamera.TIMEOUT_MS) { fail(request,"camera request expired"); return; }
        if (mc.level == null || mc.player == null || !mc.level.dimension().location().toString().equals(request.dimension())) {
            fail(request,"client is not in the companion's dimension"); return;
        }
        boolean owned = NumenRoster.instance().entries().stream().anyMatch(e -> e.uuid().equals(request.companion()) && !e.dead() && !e.removed());
        Entity npc = mc.level.getPlayerByUUID(request.companion());
        if (!owned || npc == null || !npc.isAlive() || !mc.level.hasChunkAt(npc.blockPosition())) {
            fail(request,"companion is outside client-loaded terrain or is not alive"); return;
        }
        // Fabulous 的后处理链持有玩家主画布;不能把那张图冒充同伴画面。
        if (Minecraft.useShaderTransparency()) { fail(request,"NPC camera currently requires Fast or Fancy graphics, not Fabulous"); return; }
        rendering = true;
        try {
            capture(mc, npc, request, delta);
        } catch (RuntimeException e) {
            Constants.LOG.error("[numen-camera] first-person render failed",e);
            fail(request,"first-person renderer failed; see client log");
        } finally {
            rendering = false;
        }
    }

    private static final class EyeCamera extends Camera {
        void at(Minecraft mc, Entity npc, float partial) {
            setup(mc.level,npc,false,false,partial);
            // 新相机没有眼高平滑历史,直接用真实眼睛,避免落在脚下或方块内部。
            setPosition(npc.getEyePosition(partial));
        }
    }

    private static void capture(Minecraft mc, Entity npc, NpcCamera.Request request, DeltaTracker delta) {
        RenderTarget previousTarget = mc.getMainRenderTarget();
        Camera previousCamera = mc.gameRenderer.getMainCamera();
        boolean panoramic = mc.gameRenderer.isPanoramicMode();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var previousSorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        mc.getProfiler().push("numen_npc_camera");
        EyeCamera eye = new EyeCamera();
        float partial = delta.getGameTimeDeltaPartialTick(true);
        TerrainSlot terrain = (TerrainSlot)mc.levelRenderer;
        terrain.numen$beginCamera();
        try {
            eye.at(mc,npc,partial);
            Matrix4f view = new Matrix4f().rotation(eye.rotation().conjugate(new Quaternionf()));
            Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70),
                    (float)NpcCamera.WIDTH/NpcCamera.HEIGHT,0.05f,mc.gameRenderer.getDepthFar());
            if (target == null) target = new TextureTarget(NpcCamera.WIDTH,NpcCamera.HEIGHT,true,Minecraft.ON_OSX);
            ((CameraTarget)mc).numen$cameraTarget(target);
            ((CameraSlot)mc.gameRenderer).numen$camera(eye);
            mc.gameRenderer.setPanoramicMode(true);
            target.bindWrite(true);
            modelView.identity(); RenderSystem.applyModelViewMatrix();
            mc.gameRenderer.resetProjectionMatrix(projection);
            mc.gameRenderer.lightTexture().updateLightTexture(partial);
            mc.levelRenderer.prepareCullFrustum(eye.getPosition(),view,projection);
            mc.levelRenderer.renderLevel(delta,false,eye,mc.gameRenderer,mc.gameRenderer.lightTexture(),view,projection);
            renderOwner(mc,eye,view,partial);
            try (var image = Screenshot.takeScreenshot(target)) {
                byte[] png = image.asByteArray();
                if (png.length > NpcCamera.MAX_BYTES) { fail(request,"camera PNG exceeds upload limit"); return; }
                Vec3 pos = eye.getPosition();
                var local=LOCAL.get(request.id());
                if (local!=null) {
                    target.bindWrite(true);
                    local.complete(NpcSurfaceFrame.capture(mc,npc,eye,view,projection,request.id(),png));
                    LOCAL.remove(request.id());
                    return;
                }
                NumenNetwork.sendToServer(new NpcCamera.Reply(request.id(),request.companion(),request.dimension(),
                        pos.x,pos.y,pos.z,eye.getYRot(),eye.getXRot(),png,""));
            } catch (java.io.IOException e) {
                Constants.LOG.error("[numen-camera] PNG encoding failed",e);
                fail(request,"camera PNG encoding failed");
            }
        } finally {
            ((CameraTarget)mc).numen$cameraTarget(previousTarget);
            ((CameraSlot)mc.gameRenderer).numen$camera(previousCamera);
            mc.gameRenderer.setPanoramicMode(panoramic);
            // 共享世界渲染器的可见性和实体朝向缓存归还给玩家,下一帧照常渲染。
            terrain.numen$endCamera(previousCamera.getPosition());
            mc.levelRenderer.prepareCullFrustum(previousCamera.getPosition(),
                    new Matrix4f().rotation(previousCamera.rotation().conjugate(new Quaternionf())),
                    mc.gameRenderer.getProjectionMatrix(mc.options.fov().get()));
            mc.getEntityRenderDispatcher().prepare(mc.level,previousCamera,mc.crosshairPickEntity);
            mc.getBlockEntityRenderDispatcher().prepare(mc.level,previousCamera,mc.hitResult);
            previousTarget.bindWrite(true);
            modelView.popMatrix(); RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection,previousSorting);
            RenderSystem.setShaderColor(1,1,1,1);
            RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
            RenderSystem.disableBlend(); FogRenderer.setupNoFog();
            mc.getProfiler().pop();
        }
    }

    /** 原版 LevelRenderer 在非玩家本人视角下省略 LocalPlayer;补画主人,使 NPC 能真正看到玩家。 */
    private static void renderOwner(Minecraft mc, Camera eye, Matrix4f view, float partial) {
        if (mc.player.isSpectator()) return;
        var buffers = mc.renderBuffers().bufferSource();
        var pose = new com.mojang.blaze3d.vertex.PoseStack();
        pose.mulPose(view);
        Vec3 player = mc.player.getPosition(partial).subtract(eye.getPosition());
        mc.getEntityRenderDispatcher().render(mc.player,player.x,player.y,player.z,
                mc.player.getViewYRot(partial),partial,pose,buffers,
                mc.getEntityRenderDispatcher().getPackedLightCoords(mc.player,partial));
        buffers.endBatch();
    }

    private static void fail(NpcCamera.Request request, String why) {
        var local=LOCAL.remove(request.id());
        if (local!=null) { local.completeExceptionally(new IllegalStateException(why)); return; }
        if (Minecraft.getInstance().getConnection() != null)
            NumenNetwork.sendToServer(NpcCamera.Reply.missing(request,why));
    }
}
