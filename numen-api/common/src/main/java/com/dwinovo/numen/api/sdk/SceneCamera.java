package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.llm.RequestPicture;
import com.dwinovo.numen.api.brain.NpcCamera;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 内容工具按需索取现场图;位姿来自同一帧,不得用调用前的位置代替。 */
public final class SceneCamera {
    private SceneCamera() {}
    public record View(RequestPicture picture, Vec3 eye, float yaw, float pitch, String dimension) {}
    public static CompletableFuture<View> capture(MinecraftServer server, UUID owner, UUID companion, CancelToken cancel) {
        return NpcCamera.captureView(server,owner,companion,cancel).thenApply(v -> v==null?null:
                new View(v.picture(),v.eye(),v.yaw(),v.pitch(),v.dimension()));
    }
}
