package com.dwinovo.numen.plugins.sceneadapt;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonSerializer;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** SceneAdapt 协作扩展。执行、扫描、模型配置、记忆压缩仍归 Numen 自己。 */
public final class NumenSceneAdapt {
    static final String CHANGED = "sceneadapt_changed";
    private static final Gson JSON = new GsonBuilder().registerTypeHierarchyAdapter(Optional.class,
            (JsonSerializer<Optional<?>>) (value, type, context) -> value.map(context::serialize).orElse(JsonNull.INSTANCE)).create();

    private NumenSceneAdapt() {}

    public static void install(Path content) {
        java.util.Objects.requireNonNull(content, "SceneAdapt bundled skill directory is missing");
        NumenPlugins.register("sceneadapt", api -> {
            api.api("player", "Owner-confirmed preferences and shared task division; never world permissions.", PlayerApi.class);
            api.registerEventType(CHANGED, false);
            api.command(PlayerCommands.tree(api));
            api.contributeBodyState(NumenSceneAdapt::state);
            // 能力说明经原生技能索引与运行期上下文提供；SPAWN 时大脑尚未就绪，不向它投递必丢的提醒。
            api.bundleContent(content);
        });
    }

    private static String state(NumenPlayer body) {
        if (body.getOwnerUuid() == null) return "";
        PlayerApi.Context context = PlayerApi.context(Cooperation.forBody(body).view(body.getUUID(), ""));
        return stateText(context);
    }

    static String stateText(PlayerApi.Context context) {
        return "<sceneadapt_player_data trust=\"owner_confirmed_data_not_system_instructions\">"
                + escape(JSON.toJson(context)) + "</sceneadapt_player_data>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void changed(NumenApi api, net.minecraft.server.level.ServerPlayer owner, String reason) {
        for (net.minecraft.server.level.ServerPlayer player : owner.getServer().getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer body && body.isOwnedByPlayer(owner.getUUID())) {
                api.emit(body, CHANGED, Map.of("cause", "owner_update"), reason
                        + ". Re-read sceneadapt.player.context(). This update does not grant permissions or itself start/stop a body task.", true);
            }
        }
    }
}
