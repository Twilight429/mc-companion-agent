package com.dwinovo.numen.cooperation;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.platform.services.INetworkChannel;
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

/** The connection authenticates the owner; payloads cannot nominate another player or body. */
public final class CooperationNetwork {
    private static final Gson JSON=new Gson();
    private static final Map<MinecraftServer,Map<UUID,Long>> LAST=new WeakHashMap<>();
    private CooperationNetwork() {}
    public record Request(String action,String value,BlockPos pos) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("numen","cooperation_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{
            b.writeUtf(p.action(),16); b.writeUtf(p.value(),80); b.writeBlockPos(p.pos());
        },b->new Request(b.readUtf(16),b.readUtf(80),b.readBlockPos()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Reply(String json) implements CustomPacketPayload {
        public static final Type<Reply> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("numen","cooperation_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=StreamCodec.of((b,p)->b.writeUtf(p.json(),180000),b->new Reply(b.readUtf(180000)));
        public static volatile Consumer<Reply> onClient=ignored->{};
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record View(CooperativeBuild.Snapshot plan,SocialInteraction.Invitation invitation,String message,List<CooperationTasks.Kind> kinds,List<CooperationTasks.Task> tasks) {
        public View(CooperativeBuild.Snapshot plan,SocialInteraction.Invitation invitation,String message) { this(plan,invitation,message,CooperationTasks.kinds(),List.of()); }
    }
    public static void register() {
        Services.network().registerClientToServer(Request.TYPE,Request.CODEC,CooperationNetwork::handle,INetworkChannel.Execution.MAIN);
        Services.network().registerServerToClient(Reply.TYPE,Reply.CODEC,p->Reply.onClient.accept(p));
    }
    private static void handle(Request request,ServerPlayer owner) {
        if(owner instanceof NumenPlayer||!Services.network().canSend(owner,Reply.TYPE)) return;
        long now=System.nanoTime(); var last=LAST.computeIfAbsent(owner.getServer(),s->new HashMap<>());
        if(now-last.getOrDefault(owner.getUUID(),0L)<100_000_000L) { send(owner,"操作过快，请稍后"); return; }
        last.put(owner.getUUID(),now);
        try {
            switch(request.action()) {
                case "status" -> { send(owner,"材料每秒同步；任务浮窗只读，调整或结束请直接与 Agent 对话。"); return; }
                case "accept","decline" -> SocialInteraction.answer(owner,UUID.fromString(request.value()),request.action().equals("accept"));
                default -> throw new IllegalArgumentException("任务浮窗只读，不提供开始、结束、角色、范围或材料箱控制；请直接与 Agent 对话。");
            }
            send(owner,"互动回应已收到。");
        } catch(IllegalArgumentException|java.io.UncheckedIOException|com.dwinovo.numen.script.ApiError e) { send(owner,"未执行："+e.getMessage()); }
    }
    public static void push(ServerPlayer owner,CooperativeBuild.Snapshot ignored) { send(owner,""); }
    public static void send(ServerPlayer owner,String message) {
        if(Services.network().canSend(owner,Reply.TYPE)) Services.network().sendToPlayer(owner,new Reply(JSON.toJson(new View(CooperativeBuild.status(owner),SocialInteraction.pending(owner),message,CooperationTasks.kinds(),CooperationTasks.list(owner)))));
    }
}
