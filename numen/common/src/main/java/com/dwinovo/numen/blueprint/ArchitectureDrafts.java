package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.build.Placement;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** 每位真实玩家一份视觉草稿。移动/旋转生成新修订,确认只锁定摆放,从不派施工任务。 */
public final class ArchitectureDrafts {
    private static final Map<MinecraftServer, Map<UUID, Draft>> DRAFTS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, UUID>> ANNOUNCED = new WeakHashMap<>();
    private ArchitectureDrafts() {}
    public record Draft(UUID revision, ArchitectureCatalog.Entry building, String dimension, BlockPos origin,
                        int quarters, boolean confirmed, List<ArchitectureCatalog.Cell> cells) {
        public Draft {
            origin = origin.immutable();
            cells = List.copyOf(cells);
        }
    }

    private static Map<UUID, Draft> drafts(MinecraftServer server) {
        return DRAFTS.computeIfAbsent(server, ignored -> new HashMap<>());
    }
    public static Draft get(ServerPlayer owner) { return drafts(owner.getServer()).get(owner.getUUID()); }

    public static Draft preview(ServerPlayer owner, String name, BlockPos origin, int quarters) {
        var entry = ArchitectureCatalog.get(owner.getServer(), name);
        if (!entry.fingerprint().equals(ArchitectureCatalog.fingerprint(owner.getServer(), name)))
            throw new IllegalArgumentException("建筑或署名信息已变更，请先刷新建筑库");
        List<ArchitectureCatalog.Cell> cells = ArchitectureCatalog.cells(owner.getServer(), name);
        Draft draft = new Draft(UUID.randomUUID(), entry, owner.serverLevel().dimension().location().toString(),
                origin, Math.floorMod(quarters, 4), false, cells);
        checkBounds(owner, draft);
        drafts(owner.getServer()).put(owner.getUUID(), draft);
        send(owner, draft);
        return draft;
    }

    public static Draft move(ServerPlayer owner, UUID revision, BlockPos origin, int quarters) {
        Draft was = current(owner, revision);
        Draft draft = new Draft(UUID.randomUUID(), was.building(), was.dimension(), origin,
                Math.floorMod(quarters, 4), false, was.cells());
        checkBounds(owner, draft);
        drafts(owner.getServer()).put(owner.getUUID(), draft);
        send(owner, draft);
        return draft;
    }

    public static Draft confirm(ServerPlayer owner, UUID revision) {
        if (owner instanceof NumenPlayer) throw new IllegalArgumentException("只有真实玩家可确认建筑投影");
        Draft was = current(owner, revision);
        if (!was.building().compatible()) throw new IllegalArgumentException("建筑包含不兼容内容，不能确认施工位置: "
                + String.join("; ", was.building().issues()));
        Draft draft = new Draft(was.revision(), was.building(), was.dimension(), was.origin(), was.quarters(), true, was.cells());
        drafts(owner.getServer()).put(owner.getUUID(), draft);
        send(owner, draft);
        owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("投影位置已确认，尚未开工。"
                + credit(draft.building())));
        return draft;
    }

    public static void cancel(ServerPlayer owner, UUID revision) {
        current(owner, revision);
        drafts(owner.getServer()).remove(owner.getUUID());
        if (Services.network().canSend(owner, ArchitectureViewPayload.TYPE))
            Services.network().sendToPlayer(owner, ArchitectureViewPayload.empty());
    }

    private static Draft current(ServerPlayer owner, UUID revision) {
        Draft draft = get(owner);
        if (draft == null || !draft.revision().equals(revision)) throw new IllegalArgumentException("投影已更新，请基于最新投影操作");
        if (!draft.dimension().equals(owner.serverLevel().dimension().location().toString()))
            throw new IllegalArgumentException("投影属于另一个维度，请重新放置");
        if (!draft.building().fingerprint().equals(ArchitectureCatalog.fingerprint(owner.getServer(), draft.building().id())))
            throw new IllegalArgumentException("建筑或署名文件已变更，旧确认已失效，请刷新后重新预览");
        return draft;
    }

    private static void checkBounds(ServerPlayer owner, Draft draft) {
        checkBounds(owner, draft.origin(), draft.quarters(), draft.cells());
    }

    static void checkBounds(ServerPlayer owner, BlockPos origin, int quarters, List<ArchitectureCatalog.Cell> cells) {
        Placement placement = new Placement(origin, quarters);
        for (var cell : cells) {
            BlockPos pos = placement.cell(cell.pos());
            if (pos.getY() < owner.level().getMinBuildHeight() || pos.getY() >= owner.level().getMaxBuildHeight()
                    || !owner.serverLevel().getWorldBorder().isWithinBounds(pos))
                throw new IllegalArgumentException("投影超出世界高度或边界");
        }
    }

    public static Draft approved(NumenPlayer her) {
        Draft draft = drafts(her.getServer()).get(her.getOwnerUuid());
        if (draft == null || !draft.confirmed()) throw denied("玩家还未确认投影位置与朝向");
        requireApproved(her, draft.building().id(), draft.origin(), draft.quarters());
        return draft;
    }

    static boolean matches(Draft draft, UUID owner, String name, String dimension, BlockPos origin, int quarters, String fingerprint) {
        return owner != null && draft != null && draft.confirmed() && draft.building().compatible()
                && draft.building().id().equals(name) && draft.dimension().equals(dimension)
                && draft.origin().equals(origin) && draft.quarters() == Math.floorMod(quarters, 4)
                && draft.building().fingerprint().equals(fingerprint);
    }

    /** 只约束已入库作品;旧的临时施工图仍走原生流程。摆放确认不能被模型直接调用 build.place 绕过。 */
    public static void requireApproved(NumenPlayer her, String name, BlockPos origin, int quarters) {
        if (!ArchitectureCatalog.managed(her.getServer(), name)) return;
        Draft draft = drafts(her.getServer()).get(her.getOwnerUuid());
        if (!matches(draft, her.getOwnerUuid(), name, her.serverLevel().dimension().location().toString(),
                origin, quarters, ArchitectureCatalog.fingerprint(her.getServer(), name)))
            throw denied("该建筑须先由玩家从 B 选模板，并在世界按 Enter 确认同一位置、朝向和文件版本；移动后需重新确认");
    }

    private static ApiError denied(String message) { return new ApiError(ErrorKind.DENIED, message, null); }

    public static String credit(ArchitectureCatalog.Entry entry) {
        return "作品: " + entry.title() + " | 作者: " + entry.author() + " | 来源: " + entry.sourceUrl()
                + " | 授权: " + entry.license();
    }

    /** 同一修订首次提交施工时署名一次;这是申请施工,不冒充任务已完成。 */
    public static void announceBuild(NumenPlayer her) {
        Draft draft = drafts(her.getServer()).get(her.getOwnerUuid());
        if (draft == null || !draft.confirmed()) return;
        var announced = ANNOUNCED.computeIfAbsent(her.getServer(), ignored -> new HashMap<>());
        if (draft.revision().equals(announced.get(her.getOwnerUuid()))) return;
        announced.put(her.getOwnerUuid(), draft.revision());
        com.dwinovo.numen.cooperation.CooperativeBuild.observeProjection(her);
        ServerPlayer owner = her.getServer().getPlayerList().getPlayer(her.getOwnerUuid());
        if (owner != null) owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "已提交按确认投影施工的请求。" + credit(draft.building())));
    }

    private static void send(ServerPlayer owner, Draft draft) {
        if (!Services.network().canSend(owner, ArchitectureViewPayload.TYPE)) {
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("当前客户端不支持投影，请安装同版本 Numen。"));
            return;
        }
        Services.network().sendToPlayer(owner, ArchitectureViewPayload.of(draft));
    }
}
