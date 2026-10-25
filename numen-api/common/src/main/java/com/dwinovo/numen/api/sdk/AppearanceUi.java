package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.platform.services.INetworkChannel;
import com.dwinovo.numen.script.ApiReply;
import com.dwinovo.numen.script.ErrorKind;
import com.google.gson.JsonObject;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.Consumer;

/** 外观插件声明三个已登记的 API;界面只可查询、列贴图和换装,没有任意指令入口。 */
public final class AppearanceUi {
    private AppearanceUi() {}
    private static Map<String,String> functions=Map.of();
    private static final Map<net.minecraft.server.MinecraftServer,Map<UUID,Long>> LAST=new WeakHashMap<>();
    static { com.dwinovo.numen.api.platform.ServerLifecycle.onStopped(LAST::clear); }
    public static void bind(String options,String textures,String apply) {
        functions=Map.of("options",options,"textures",textures,"apply",apply);
    }
    public record Request(long serial,UUID companion,String action,String model,String texture) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("numen","appearance_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{
            b.writeLong(p.serial()); b.writeUUID(p.companion()); b.writeUtf(p.action(),16); b.writeUtf(p.model(),512); b.writeUtf(p.texture(),512);
        },b->new Request(b.readLong(),b.readUUID(),b.readUtf(16),b.readUtf(512),b.readUtf(512)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Reply(long serial,UUID companion,String action,String json) implements CustomPacketPayload {
        public static final Type<Reply> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("numen","appearance_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=StreamCodec.of((b,p)->{
            b.writeLong(p.serial()); b.writeUUID(p.companion()); b.writeUtf(p.action(),16); b.writeUtf(p.json(),30000);
        },b->new Reply(b.readLong(),b.readUUID(),b.readUtf(16),b.readUtf(30000)));
        public static volatile Consumer<Reply> onClient=ignored->{};
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        Services.network().registerClientToServer(Request.TYPE,Request.CODEC,AppearanceUi::handle,INetworkChannel.Execution.MAIN);
        Services.network().registerServerToClient(Reply.TYPE,Reply.CODEC,r->Reply.onClient.accept(r));
    }
    public static boolean owned(ServerPlayer owner,ServerPlayer target) {
        return !(owner instanceof NumenPlayer) && target instanceof NumenPlayer her && owns(owner.getUUID(),her.getOwnerUuid());
    }
    static boolean owns(UUID requester,UUID owner) { return requester!=null&&requester.equals(owner); }
    private static void handle(Request r,ServerPlayer owner) {
        if(!Services.network().canSend(owner,Reply.TYPE)) return;
        ServerPlayer target=owner.server.getPlayerList().getPlayer(r.companion());
        if(!owned(owner,target)) { reply(owner,r,ApiReply.error(ErrorKind.DENIED,"只能更换自己的在线 NPC 的模型，不修改玩家外观",null,null).toString()); return; }
        var last=LAST.computeIfAbsent(owner.server,s->new HashMap<>()); long now=System.nanoTime();
        if(now-last.getOrDefault(owner.getUUID(),0L)<100_000_000L) { reply(owner,r,ApiReply.error(ErrorKind.FAILED,"操作过快，请稍后刷新",null,null).toString()); return; }
        last.put(owner.getUUID(),now);
        String fn=functions.get(r.action());
        if(fn==null) { reply(owner,r,ApiReply.error(ErrorKind.NO_FUNCTION,"服务器未启用此外观接口（需要安装 YSM）",null,null).toString()); return; }
        JsonObject args=new JsonObject();
        if(!r.action().equals("options")) args.addProperty("model",r.model());
        if(r.action().equals("apply")&&!r.texture().isEmpty()) args.addProperty("texture",r.texture());
        Dispatcher.serve(fn,args,(NumenPlayer)target,target.getUUID(),"appearance-ui-"+r.serial(),json->{
            if(r.action().equals("apply")) com.dwinovo.numen.api.event.NumenEvents.serverMessage((NumenPlayer)target,
                    "Owner used the NPC appearance UI: "+fn+" model="+r.model()+" texture="+r.texture()+"; actual receipt: "+json,false,0,0);
            reply(owner,r,json);
        });
    }
    private static void reply(ServerPlayer owner,Request r,String json) {
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>30000)
            json=ApiReply.error(ErrorKind.FAILED,"服务器模型清单超过界面单次传输上限，请减少本次模型清单后刷新",null,null).toString();
        if(owner.connection!=null&&Services.network().canSend(owner,Reply.TYPE))
            Services.network().sendToPlayer(owner,new Reply(r.serial(),r.companion(),r.action(),json));
    }
}
