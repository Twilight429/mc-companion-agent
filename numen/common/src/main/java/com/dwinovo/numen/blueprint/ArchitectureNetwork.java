package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.platform.services.INetworkChannel;
import com.dwinovo.numen.build.Placement;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.Consumer;

/** 建筑库协议:连接身份就是玩家身份,请求不接受主人 UUID 或任意文件路径。 */
public final class ArchitectureNetwork {
    private static final Gson JSON = new Gson();
    private static final Map<MinecraftServer, Map<UUID, Long>> LAST = new WeakHashMap<>();
    private ArchitectureNetwork() {}
    public record Request(String action, String value, BlockPos origin, int rotation, UUID revision) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("numen", "architecture_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((b, p) -> {
            b.writeUtf(p.action(), 16); b.writeUtf(p.value(), 600); b.writeBlockPos(p.origin());
            b.writeVarInt(p.rotation()); b.writeUUID(p.revision());
        }, b -> new Request(b.readUtf(16), b.readUtf(600), b.readBlockPos(), b.readVarInt(), b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Reply(String json) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("numen", "architecture_catalog"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json(), 180000), b -> new Reply(b.readUtf(180000)));
        public static volatile Consumer<Reply> onClient = ignored -> {};
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Results(List<ArchitectureSearch.Match> matches, String message,String kind,String query) {}
    public static void register() {
        var network = Services.network();
        network.registerClientToServer(Request.TYPE, Request.CODEC, ArchitectureNetwork::handle, INetworkChannel.Execution.MAIN);
        network.registerServerToClient(Reply.TYPE, Reply.CODEC, reply -> Reply.onClient.accept(reply));
        network.registerServerToClient(ArchitectureViewPayload.TYPE, ArchitectureViewPayload.STREAM_CODEC, ArchitectureViewPayload::handle);
        network.registerServerToClient(ArchitectureThumbnailPayload.TYPE,ArchitectureThumbnailPayload.CODEC,p->ArchitectureThumbnailPayload.onClient.accept(p));
    }
    private static void handle(Request request, ServerPlayer owner) {
        if (owner instanceof NumenPlayer || !Services.network().canSend(owner, Reply.TYPE)) return;
        long now = System.nanoTime();
        var limits = LAST.computeIfAbsent(owner.getServer(), ignored -> new HashMap<>());
        Long last = limits.get(owner.getUUID());
        if (last != null && now - last < 100_000_000L) {
            reply(owner, List.of(), "操作过快，请稍后重试"); return;
        }
        limits.put(owner.getUUID(), now);
        try {
            switch (request.action()) {
                case "refresh" -> {
                    var result = ArchitectureCatalog.refresh(owner.serverLevel());
                    catalog(owner, ArchitectureSearch.search(ArchitectureCatalog.entries(owner.getServer()), request.value(), 10),
                            "已更新 " + result.indexed() + " 个建筑；待处理 " + result.remaining() + "。"
                                    + String.join("; ", result.errors()),"catalog",request.value());
                }
                case "search" -> {
                    String message = "来源、作者及材料均取自本地建筑记录；新文件或修改后的文件请点刷新。";
                    if (ArchitectureCatalog.entries(owner.getServer()).isEmpty()) {
                        var result = ArchitectureCatalog.refresh(owner.serverLevel());
                        message = "首次已索引 " + result.indexed() + " 个建筑；待处理 " + result.remaining() + "。"
                                + String.join("; ", result.errors());
                    }
                    catalog(owner, ArchitectureSearch.search(ArchitectureCatalog.entries(owner.getServer()), request.value(), 10), message,"catalog",request.value());
                }
                case "inspect" -> {
                    if(!Services.network().canSend(owner,ArchitectureThumbnailPayload.TYPE)) throw new IllegalArgumentException("客户端需要更新以显示建筑原型");
                    var entry=ArchitectureCatalog.get(owner.getServer(),request.value());
                    if(!entry.fingerprint().equals(ArchitectureCatalog.fingerprint(owner.getServer(),entry.id())))
                        throw new IllegalArgumentException("文件已改变，请先刷新建筑库");
                    var cells=ArchitectureCatalog.cells(owner.getServer(),entry.id()).stream().map(c->new ArchitectureViewPayload.Cell(c.pos(),net.minecraft.world.level.block.Block.getId(c.state()),c.unknown())).toList();
                    Services.network().sendToPlayer(owner,new ArchitectureThumbnailPayload(new ArchitectureViewPayload(
                            ArchitectureViewPayload.NONE,entry.id(),"",BlockPos.ZERO,0,false,entry.compatible(),ArchitectureDrafts.credit(entry),cells)));
                }
                case "preview" -> {
                    ArchitectureDrafts.preview(owner, request.value(), request.origin(), Placement.quarters(request.rotation()));
                    reply(owner, List.of(), "仅投影，未施工。P 移到准星处；方向键微调（Shift 加速）；PageUp/PageDown 高度；R 居中旋转；Enter 确认。");
                }
                case "move" -> ArchitectureDrafts.move(owner, request.revision(), request.origin(), Placement.quarters(request.rotation()));
                case "confirm" -> {
                    ArchitectureDrafts.confirm(owner, request.revision());
                    reply(owner, List.of(), "位置已确认，未开工。请再告诉同伴施工或先备料。");
                }
                case "cancel" -> { ArchitectureDrafts.cancel(owner, request.revision()); reply(owner, List.of(), "已撤销投影及其摆放确认"); }
                default -> throw new IllegalArgumentException("unknown architecture action");
            }
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            reply(owner, List.of(), "操作未执行: " + e.getMessage());
        }
    }
    private static void reply(ServerPlayer owner, List<ArchitectureSearch.Match> matches, String message) {
        catalog(owner,matches,message,"message","");
    }
    public static void recommend(ServerPlayer owner,String query,List<ArchitectureSearch.Match> matches) {
        if(Services.network().canSend(owner,Reply.TYPE)) catalog(owner,matches,
                matches.isEmpty()?"本地建筑库没有符合条件的模板，请导入作品或更换描述。":"同伴推荐了 "+matches.size()+" 个真实模板，按 B 比较原型并选择投放；尚未放置或开工。","recommend",query);
        owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("[建筑推荐] "+(matches.isEmpty()?"本地没有匹配模板，不编造候选。":"已推荐 "+matches.size()+" 个模板，按 B 选择并投放。")));
    }
    private static void catalog(ServerPlayer owner,List<ArchitectureSearch.Match> matches,String message,String kind,String query) {
        Services.network().sendToPlayer(owner,new Reply(JSON.toJson(new Results(matches,message,kind,query))));
    }
}
