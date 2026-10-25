package com.dwinovo.numen.plugins.sceneadapt;

import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** 服务端身份决定资料归属；模型的参数和玩家提交的名字都不能指定另一个资料库。 */
final class Cooperation {
    private static final Map<MinecraftServer, Map<UUID, PlayerStore>> WORLDS = new WeakHashMap<>();

    private Cooperation() {}

    static synchronized PlayerStore forOwner(MinecraftServer server, UUID owner) {
        if (owner == null) throw PlayerStore.invalid("同伴尚未绑定玩家");
        return WORLDS.computeIfAbsent(server, ignored -> new HashMap<>()).computeIfAbsent(owner,
                id -> new PlayerStore(server.getWorldPath(LevelResource.ROOT), id));
    }

    static PlayerStore forBody(NumenPlayer body) {
        return forOwner(body.getServer(), body.getOwnerUuid());
    }

}
