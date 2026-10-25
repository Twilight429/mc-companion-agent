package com.dwinovo.numen.cooperation;

import com.dwinovo.numen.NumenContent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.*;
import com.dwinovo.numen.api.task.TaskDispatch;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.util.*;

/** An invitation is not an action: the real owner must answer before the native task can run. */
public final class SocialInteraction {
    private static final Map<MinecraftServer,Map<UUID,Invitation>> OFFERS=new WeakHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Long>> LAST=new WeakHashMap<>();
    private static final Map<MinecraftServer,Set<UUID>> ACTIVE=new WeakHashMap<>();
    public static final String RESULT="social_interaction_result";
    private SocialInteraction() {}
    public record Invitation(UUID token,UUID companion,String kind,String text,long until) {}
    public record Offered(String token,String kind,String text,long until) {}
    public record Offer(@Doc("high_five, jump or spin; never attack.") String kind,
                        @Doc("Brief friendly invitation, not an instruction or a claim that an animation exists.") String text) {}
    public static void install(NumenApi api) {
        api.api("social","Invite the owner to a harmless short gesture. They press H to consent; never damages or controls the player.",SocialInteraction.class);
        api.registerEventType(RESULT,false);
    }
    public static String kind(String kind) {
        if(!Set.of("high_five","jump","spin").contains(kind)) throw new IllegalArgumentException("kind must be high_five, jump or spin");
        return kind;
    }
    public static Invitation pending(ServerPlayer owner) {
        var offers=OFFERS.computeIfAbsent(owner.getServer(),s->new HashMap<>());
        var offer=offers.get(owner.getUUID());
        if(offer!=null && owner.level().getGameTime()>=offer.until()) { offers.remove(owner.getUUID()); return null; }
        return offer;
    }
    @Fn("Offer a high-five hand gesture, one jump or a turn. No movement occurs until the real owner accepts with H within 15 seconds and 3 blocks.")
    @Example("numen.social.offer(\"high_five\",\"击个掌？\")")
    @Note("This is not attacking, teleporting or a guaranteed YSM animation. Consent expires, survives no reconnect guarantee, and cannot be fabricated by the model. Survival retains priority.")
    public static Offered offer(ServerCall call,Offer args) {
        var invitation=offer(call.her(),args.kind(),args.text());
        return new Offered(invitation.token().toString(),invitation.kind(),invitation.text(),invitation.until());
    }
    public static Invitation offer(NumenPlayer body,String kind,String text) {
        kind(kind);
        if(text==null||text.length()>160) throw new IllegalArgumentException("invitation text must be at most 160 characters");
        var owner=body.getServer().getPlayerList().getPlayer(body.getOwnerUuid());
        if(owner==null||owner.serverLevel()!=body.serverLevel()) throw new IllegalArgumentException("主人须在线且同维度");
        long now=body.level().getGameTime();
        var last=LAST.computeIfAbsent(body.getServer(),s->new HashMap<>());
        if(now-last.getOrDefault(owner.getUUID(),-10000L)<600) throw new IllegalArgumentException("互动邀请冷却中，请先继续原任务");
        if(ACTIVE.computeIfAbsent(body.getServer(),s->new HashSet<>()).contains(owner.getUUID())) throw new IllegalArgumentException("已有互动正在执行");
        var invitation=new Invitation(UUID.randomUUID(),body.getUUID(),kind,text,now+300);
        OFFERS.computeIfAbsent(body.getServer(),s->new HashMap<>()).put(owner.getUUID(),invitation); last.put(owner.getUUID(),now);
        owner.sendSystemMessage(Component.literal("[伙伴互动] "+text+"（靠近后按 H 回应；15 秒内有效）"));
        CooperationNetwork.send(owner,"新的互动邀请，H 回应"); return invitation;
    }
    public static void answer(ServerPlayer owner,UUID token,boolean accept) {
        var invitation=pending(owner);
        if(invitation==null||!invitation.token().equals(token)) throw new IllegalArgumentException("邀请已过期或不属于你");
        var found=owner.getServer().getPlayerList().getPlayer(invitation.companion());
        if(!(found instanceof NumenPlayer body)||!body.isOwnedByPlayer(owner.getUUID())) throw new IllegalArgumentException("不是你的同伴");
        if(accept && (owner.serverLevel()!=body.serverLevel()||owner.distanceToSqr(body)>9)) throw new IllegalArgumentException("请先靠近同伴至 3 格内，再按 H");
        OFFERS.get(owner.getServer()).remove(owner.getUUID());
        if(!accept) {
            NumenContent.api().emit(body,RESULT,Map.of("kind",invitation.kind()),"Owner declined the social invitation. Respect it and continue the original work.",false);
            CooperationNetwork.send(owner,"已婉拒，不执行动作"); return;
        }
        var active=ACTIVE.computeIfAbsent(owner.getServer(),s->new HashSet<>());
        if(!active.add(owner.getUUID())) throw new IllegalArgumentException("已有互动正在执行");
        TaskDispatch.runSync(body,new SocialTaskRecord(body,invitation.kind()),result->{
            active.remove(owner.getUUID());
            owner.sendSystemMessage(Component.literal("[伙伴互动] "+result.message()));
            NumenContent.api().emit(body,RESULT,Map.of("kind",invitation.kind(),"success",Boolean.toString(result.success())),result.message(),false);
            CooperationNetwork.send(owner,result.message());
        });
        CooperationNetwork.send(owner,"已回应，动作交由原生调度执行");
    }
}
