package com.dwinovo.numen.cooperation;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.sdk.*;
import com.dwinovo.numen.blueprint.ArchitectureDrafts;
import com.dwinovo.numen.blueprint.BlueprintStore;
import com.dwinovo.numen.task.build.*;
import com.dwinovo.numen.build.Layout;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Planning uses observations; execution stays in the native build task and permission system. */
public final class CooperationApi {
    private CooperationApi() {}
    public static void install(NumenApi api) {
        api.api("cooperate", "Optional read-only progress accounting plus explicitly requested bounded construction helpers. The HUD never schedules, locks roles, wakes the model or ends body tasks. Load construction_cooperation before using.", CooperationApi.class);
        // 仅保留旧存档队列的类型解码;观察器不再发出此类事件。
        api.registerEventType("cooperative_build_changed",false);
    }
    public record Scope(@Doc("Lowest blueprint layers to OBSERVE, 1..3; zero means full building. Does not limit execution.") @Omitted("full building") Optional<Integer> layers) {}
    @Fn("Attach optional progress observation to the owner's confirmed projection. Does not start, control or stop body work or goals.")
    @Example("numen.cooperate.begin({layers=1})")
    public static CooperativeBuild.Snapshot begin(ServerCall call, Scope args) { return CooperativeBuild.begin(call.her(),args.layers().orElse(0)); }
    @Fn("End only the owner's construction progress record after their conversational request. Does NOT stop the native body task or change the current goal; handle those explicitly with native APIs when requested.")
    public static String close(ServerCall call) {
        var owner=call.her().server.getPlayerList().getPlayer(call.her().getOwnerUuid());
        if(owner==null) throw new IllegalArgumentException("主人须在线");
        CooperativeBuild.end(owner);
        return "Progress tracking ended. Blocks, stock and protection preserved; no body task or goal was changed.";
    }
    @Fn("Read actual construction progress and separate stocks. Role evidence is informational, never an execution gate or command.")
    @Example("return numen.cooperate.status()")
    public static CooperativeBuild.Snapshot status(ServerCall call) { return CooperativeBuild.status(call.her()); }
    @Fn("Change only the scope label of progress accounting. It never controls execution: pass layers explicitly on each place call.")
    @Example("numen.cooperate.scope({layers=0})")
    public static CooperativeBuild.Snapshot scope(ServerCall call,Scope args) {
        CooperativeBuild.scope(call.her(),args.layers().orElse(0)); return status(call);
    }
    public record Chest(@Doc("An already placed, loaded real shared container. Obtain/craft/place it normally first.") BlockPos pos) {}
    @Fn("Designate the real shared material chest; does not move items, create a chest or grant take/store permission.")
    @Example("numen.cooperate.bind_chest({x=100,y=64,z=20})")
    public static CooperativeBuild.Snapshot bind_chest(ServerCall call,Chest args) {
        CooperativeBuild.bind(call.her(),args.pos()); return status(call);
    }
    public record Batch(@Doc("Maximum cells this pass, 1..64.") @Omitted("32") Optional<Integer> count,
                        @Doc("Lowest blueprint layers THIS call may build, 1..3; zero means full. Independent of HUD records or role labels.") @Omitted("zero") Optional<Integer> layers) {}
    public record Roles(@Doc("Informational player intention: auto, collector, builder, both or idle. No UI lock or execution constraint.") String player,
                        @Doc("Suggested companion role; empty stocks still require collection before construction.") String companion) {}
    @Fn("Record conversational cooperation intentions as informational labels only. These never permit/refuse execution or impose a global Agent state.")
    @Example("numen.cooperate.assign(\"collector\",\"builder\")")
    public static CooperativeBuild.Snapshot assign(ServerCall call,Roles args) {
        return CooperativeBuild.proposeRoles(call.her(),args.player(),args.companion());
    }
    public record Work(Layout layout,int reserved,int unloaded) {}
    public static Work work(com.dwinovo.numen.api.entity.NumenPlayer body,int count,int layers) {
        if(count<1||count>64) throw new IllegalArgumentException("count must be 1..64");
        if(layers<0||layers>3) throw new IllegalArgumentException("layers must be 0..3");
        var approved=ArchitectureDrafts.approved(body);
        var layout=BlueprintStore.load(body.serverLevel(),approved.building().id(),approved.origin(),approved.quarters());
        int floor=layout.targets().stream().mapToInt(t->t.pos().getY()).min().orElse(approved.origin().getY());
        var owner=body.getServer().getPlayerList().getPlayer(body.getOwnerUuid());
        if(owner==null||owner.serverLevel()!=body.serverLevel()) throw new IllegalArgumentException("主人已离线或换了维度，协作暂停");
        var remaining=new ArrayList<BuildTaskRecord.Target>(); int reserved=0,unloaded=0;
        for(var target:layout.targets()) {
            if(layers>0&&target.pos().getY()>=floor+layers) continue;
            if(!body.serverLevel().isLoaded(target.pos())) { unloaded++; continue; }
            if(target.matches(body.serverLevel().getBlockState(target.pos()))) continue;
            if(target.pos().distSqr(owner.blockPosition())<=9) { reserved++; continue; }
            remaining.add(target);
        }
        remaining.sort(Comparator.comparingInt((BuildTaskRecord.Target t)->t.pos().getY()).thenComparingDouble(t->t.pos().distSqr(body.blockPosition())));
        var selected=List.copyOf(remaining.subList(0,Math.min(count,remaining.size())));
        // Do not claim partial work as a whole Built.Site. Decorative fixtures use a separate native final pass.
        return new Work(CooperativeBuild.subset(layout,selected,false),reserved,unloaded);
    }
    @Fn("Build one small batch from the confirmed projection with this call's explicit count/layers, reserving a 3-block area near the owner. No task mode or role label is required.")
    @Example("numen.cooperate.place({count=32,layers=1})")
    @Note("No begin, active progress record or role mode is required. Reads the current owner-confirmed projection directly; the layers argument limits ONLY this batch. Uses native tasks, real materials and permissions. Never blindly retry; inspect actual receipts. An observer is attached for display only. Fixtures need a separate agreed final pass.")
    public static Job<BuildCompanionTask.Placed> place(ServerCall call,Batch args) {
        int layers=args.layers().orElse(0);
        Work work=work(call.her(),args.count().orElse(32),layers);
        if(work.layout().targets().isEmpty()) throw new IllegalArgumentException("本轮无可施工格；可能已达标、玩家工位被保留或区块未加载。请 status 检查，不要重复重试");
        var approved=ArchitectureDrafts.approved(call.her());
        var tracking=CooperativeBuild.tracking(call.her());
        if(tracking==null||tracking.closed||tracking.foundationLayers!=layers||!tracking.fingerprint.equals(approved.building().fingerprint())
                ||!tracking.origin.equals(approved.origin())||tracking.quarters!=approved.quarters())
            CooperativeBuild.begin(call.her(),layers);
        ArchitectureDrafts.announceBuild(call.her());
        boolean consume=!com.dwinovo.numen.WorkProfile.of(call.her()).freeMaterials();
        return Job.of(new BuildTaskRecord(call,call.her().level().getGameTime()+BuildOrder.deadlineTicks(work.layout().targets().size(),consume),work.layout(),consume,true,null));
    }
    public record Verification(boolean complete,int wrong,int unloaded,int dropped,boolean fixturesPending,String note) {}
    @Fn("Verify the whole building from real loaded blocks, never from a successful task receipt alone. Decorative data prevents automatic full acceptance in this version.")
    @Example("return numen.cooperate.finish()")
    public static Verification finish(ServerCall call) {
        var body=call.her(); var site=CooperativeBuild.statusSite(body);
        ArchitectureDrafts.requireApproved(body,site.name,site.origin,site.quarters);
        int wrong=0,unloaded=0;
        for(var target:site.layout.targets()) {
            if(!body.serverLevel().isLoaded(target.pos())) unloaded++;
            else if(!target.matches(body.serverLevel().getBlockState(target.pos()))) wrong++;
        }
        boolean fixtures=!site.layout.entities().isEmpty()||!site.layout.blockEntityData().isEmpty();
        boolean complete=wrong==0&&unloaded==0&&site.layout.dropped()==0&&!fixtures;
        site.finished=complete;
        return new Verification(complete,wrong,unloaded,site.layout.dropped(),fixtures,complete?"真实方块已验收":"尚未完整验收；摆设/方块实体数据需单独核对，不虚报完工");
    }
}
