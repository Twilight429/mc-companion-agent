package com.dwinovo.numen.cooperation;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.PlacedBlocks;
import com.dwinovo.numen.api.permission.ProtectedBlocks;
import com.dwinovo.numen.blueprint.ArchitectureDrafts;
import com.dwinovo.numen.blueprint.BlueprintStore;
import com.dwinovo.numen.build.Layout;
import com.dwinovo.numen.task.build.BuildTaskRecord;
import com.dwinovo.numen.tools.BuildOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** 只记录可查询的工地进度并推送显示,不拥有身体、不自动采集/放方块、不另开模型循环。 */
public final class CooperativeBuild {
    private static final Map<MinecraftServer,Map<UUID,Site>> SITES = new WeakHashMap<>();
    private CooperativeBuild() {}
    public record Material(String item,int total,int remaining,int player,int companion,int sharedChest,int missing) {}
    public record Snapshot(boolean active,String companion,String building,String dimension,String scope,
                           String playerMode,String companionMode,CooperationRoles.Decision playerRole,
                           CooperationRoles.Decision companionRole,String chest,int matched,int targets,int unseen,
                           List<Material> materials,boolean humor,boolean complete,String note,String companionId,String phase,String taskId,
                           boolean protectedBuilding) {}
    private record PendingPlacement(ServerPlayer player,BlockPos pos,BlockState before,long tick) {}
    public static final class Site {
        final UUID owner, companion;
        final String taskId=UUID.randomUUID().toString();
        final String fingerprint, dimension, name, title;
        final Layout layout;
        final BlockPos origin;
        final int quarters, floorY;
        final Map<Long,Integer> index = new HashMap<>();
        final BitSet seen = new BitSet(), matched = new BitSet();
        final List<PendingPlacement> pending = new ArrayList<>();
        final Map<String,Integer> total;
        Map<String,Integer> oldPlayer = Map.of();
        String playerMode="auto", companionMode="auto";
        String plannedPlayer="auto", plannedCompanion="auto";
        int foundationLayers, cursor, nearTicks;
        long lastMine=-10000,lastCollect=-10000,lastBuild=-10000,lastJoke=-10000;
        BlockPos chest;
        boolean humor=true, protectedBuilding=true, finished, stale,closed;
        String phase="active";
        Snapshot snapshot;
        Site(NumenPlayer body,ArchitectureDrafts.Draft draft,int layers) {
            owner=body.getOwnerUuid(); companion=body.getUUID(); name=draft.building().id(); fingerprint=draft.building().fingerprint();
            title=draft.building().title();
            dimension=body.serverLevel().dimension().location().toString();
            layout=BlueprintStore.load(body.serverLevel(),name,draft.origin(),draft.quarters());
            origin=draft.origin(); quarters=draft.quarters();
            floorY=layout.targets().stream().mapToInt(t -> t.pos().getY()).min().orElse(origin.getY());
            foundationLayers=layers;
            for(int i=0;i<layout.targets().size();i++) index.put(layout.targets().get(i).pos().asLong(),i);
            total=BuildOps.materials(layout,body.registryAccess());
        }
        public boolean inScope(BuildTaskRecord.Target target) {
            if(foundationLayers==0) return true;
            return target.pos().getY()<floorY+foundationLayers;
        }
    }
    private static Map<UUID,Site> sites(MinecraftServer server) { return SITES.computeIfAbsent(server,s -> new HashMap<>()); }
    public static Site site(NumenPlayer body) {
        Site site=statusSite(body);
        return site;
    }
    static Site tracking(NumenPlayer body) {
        Site site=sites(body.server).get(body.getOwnerUuid());
        return site!=null&&site.companion.equals(body.getUUID())?site:null;
    }
    /** 原生蓝图施工也附带同一个只读观察器,不要求使用某种协作模式或额外控制面板。 */
    public static void observeProjection(NumenPlayer body) {
        var draft=ArchitectureDrafts.approved(body);
        var existing=tracking(body);
        if(existing==null||!existing.fingerprint.equals(draft.building().fingerprint())
                ||!existing.origin.equals(draft.origin())||existing.quarters!=draft.quarters()) begin(body,0);
    }
    static Site statusSite(NumenPlayer body) {
        Site site=sites(body.getServer()).get(body.getOwnerUuid());
        if(site==null || !site.companion.equals(body.getUUID())) throw new IllegalArgumentException("尚无此同伴的建筑进度记录；不限制使用原生能力或已确认投影施工");
        return site;
    }
    static NumenPlayer companion(ServerPlayer owner) {
        Site site=sites(owner.getServer()).get(owner.getUUID());
        if(site!=null&&!site.closed) {
            ServerPlayer found=owner.getServer().getPlayerList().getPlayer(site.companion);
            if(found instanceof NumenPlayer body) return body;
        }
        return owner.getServer().getPlayerList().getPlayers().stream().filter(p -> p instanceof NumenPlayer body
                && body.isOwnedByPlayer(owner.getUUID()) && p.serverLevel()==owner.serverLevel())
                .map(p -> (NumenPlayer)p).min(Comparator.comparingDouble(p -> p.distanceToSqr(owner)))
                .orElseThrow(() -> new IllegalArgumentException("请先召唤你自己的同伴，从 B 选择模板并在世界按 Enter 确认投影"));
    }
    public static Snapshot begin(NumenPlayer body,int layers) {
        if(layers<0 || layers>3) throw new IllegalArgumentException("foundation layers must be 0..3; zero means full building");
        Site site=new Site(body,ArchitectureDrafts.approved(body),layers);
        Site previous=sites(body.getServer()).get(site.owner);
        var owner=body.server.getPlayerList().getPlayer(site.owner);
        if(previous!=null&&!previous.closed&&owner!=null&&previous.snapshot!=null)
            CooperationTasks.publish(owner,new CooperationTasks.Task(previous.taskId,"construction",previous.title,
                    previous.companion.toString(),previous.snapshot.companion(),"superseded",previous.snapshot.matched(),
                    previous.snapshot.targets(),"Agent 更新了进度记录；没有停止身体动作或修改规划。",body.level().getGameTime()));
        sites(body.getServer()).put(site.owner,site);
        observe(body,site,true);
        return site.snapshot;
    }
    public static Snapshot status(NumenPlayer body) { return statusSite(body).snapshot; }
    public static void end(ServerPlayer owner) {
        Site site=sites(owner.getServer()).remove(owner.getUUID());
        if(site==null) return;
        if(!site.closed) CooperationTasks.publish(owner,new CooperationTasks.Task(site.taskId,"construction",site.title,site.companion.toString(),site.snapshot.companion(),
                "cancelled",site.snapshot.matched(),site.snapshot.targets(),"玩家结束了合作任务，建筑与物品保留。",owner.level().getGameTime()));
        CooperationNetwork.send(owner,"");
    }
    public static Snapshot status(ServerPlayer owner) {
        Site site=sites(owner.getServer()).get(owner.getUUID()); return site==null?null:site.snapshot;
    }
    public static void scope(NumenPlayer body,int layers) {
        if(layers<0||layers>3) throw new IllegalArgumentException("layers must be 0..3");
        Site site=site(body); site.foundationLayers=layers; observe(body,site,true);
    }
    public static Snapshot proposeRoles(NumenPlayer body,String player,String companion) {
        var site=site(body); site.plannedPlayer=CooperationRoles.mode(player); site.plannedCompanion=CooperationRoles.mode(companion);
        observe(body,site,true); return site.snapshot;
    }
    public static void bind(NumenPlayer body,BlockPos pos) {
        Site site=site(body);
        if(body.serverLevel().isLoaded(pos) && container(body.serverLevel(),pos)!=null) {
            site.chest=pos.immutable(); observe(body,site,true);
        } else throw new IllegalArgumentException("指定位置没有已加载的真实容器；请先用原生采集/合成/放置能力准备箱子");
    }
    /** 调用者须先获得原生征询同意;记录只影响对应建筑,不拥有身体或任务调度。 */
    public static void releaseProtection(ServerPlayer owner, ArchitectureDrafts.Draft draft) {
        if(owner instanceof NumenPlayer) throw new IllegalArgumentException("解除建筑保护必须经过真实玩家确认");
        Site site=sites(owner.server).get(owner.getUUID());
        if(site!=null&&site.name.equals(draft.building().id())&&site.origin.equals(draft.origin())&&site.quarters==draft.quarters()) {
            site.protectedBuilding=false;
            ServerPlayer target=owner.server.getPlayerList().getPlayer(site.companion);
            if(target instanceof NumenPlayer body && body.serverLevel()==owner.serverLevel()) observe(body,site,false);
        }
        var placement=new com.dwinovo.numen.build.Placement(draft.origin(),draft.quarters());
        ProtectedBlocks.of(owner.serverLevel()).release(owner.getUUID(),draft.cells().stream().map(c->placement.cell(c.pos())).toList());
    }
    public static void mining(ServerPlayer player) {
        Site site=sites(player.getServer()).get(player.getUUID());
        if(site!=null&&!site.closed) site.lastMine=player.level().getGameTime();
    }
    public static void placement(ServerPlayer player,BlockPos pos,BlockState before) {
        Site site=sites(player.getServer()).get(player.getUUID());
        if(site!=null && !site.closed && site.pending.size()<16) site.pending.add(new PendingPlacement(player,pos.immutable(),before,player.level().getGameTime()));
    }
    public static void tick(MinecraftServer server) {
        for(Site site : List.copyOf(sites(server).values())) {
            if(site.closed) continue;
            ServerPlayer player=server.getPlayerList().getPlayer(site.companion);
            if(!(player instanceof NumenPlayer body) || !body.isAlive()) continue;
            if(!body.serverLevel().dimension().location().toString().equals(site.dimension)) continue;
            long now=body.level().getGameTime();
            for(var pending : List.copyOf(site.pending)) {
                if(now<=pending.tick()) continue;
                site.pending.remove(pending);
                if(pending.player().serverLevel()!=body.serverLevel() || !body.serverLevel().isLoaded(pending.pos())) continue;
                BlockState state=body.serverLevel().getBlockState(pending.pos());
                var placer=PlacedBlocks.of(body.serverLevel()).placerAt(pending.pos(),state);
                Integer index=site.index.get(pending.pos().asLong());
                if(index==null || state.equals(pending.before()) || placer==null || !placer.id().equals(site.owner)) continue;
                site.lastBuild=now;
                if(!site.layout.targets().get(index).matches(state) && site.humor && now-site.lastJoke>=600) {
                    site.lastJoke=now;
                    pending.player().sendSystemMessage(Component.literal("[伙伴] 这块好像想去隔壁串门啦～和投影不太一样，是临时脚手架吗？我先不碰它。"));
                }
            }
            scan(body,site,512);
            if(now%20==0) observe(body,site,false);
        }
    }
    private static void scan(NumenPlayer body,Site site,int budget) {
        var protection=ProtectedBlocks.of(body.serverLevel());
        for(int n=0;n<Math.min(budget,site.layout.targets().size());n++) {
            int index=site.cursor++%site.layout.targets().size(); var target=site.layout.targets().get(index);
            boolean loaded=body.serverLevel().isLoaded(target.pos()); site.seen.set(index,loaded);
            boolean matches=loaded && target.matches(body.serverLevel().getBlockState(target.pos()));
            site.matched.set(index,matches);
            if(matches && site.protectedBuilding && !target.desiredState().isAir())
                protection.protect(site.owner,List.of(target.pos()));
            if(!matches) site.finished=false;
        }
    }
    private static void observe(NumenPlayer body,Site site,boolean initial) {
        ServerPlayer owner=body.getServer().getPlayerList().getPlayer(site.owner);
        if(owner==null) return;
        long now=body.level().getGameTime();
        ArchitectureDrafts.Draft draft=ArchitectureDrafts.get(owner);
        site.stale=owner.serverLevel()!=body.serverLevel() || draft==null || !draft.confirmed() || !draft.building().fingerprint().equals(site.fingerprint)
                || !draft.dimension().equals(site.dimension) || !draft.origin().equals(site.origin)
                || draft.quarters()!=site.quarters;
        if(site.stale) site.finished=false;
        if(initial) scan(body,site,Math.min(512,site.layout.targets().size()));
        boolean appearsReady=true;
        for(int i=0;i<site.layout.targets().size();i++) if(site.inScope(site.layout.targets().get(i))&&(!site.seen.get(i)||!site.matched.get(i))) { appearsReady=false; break; }
        // 达标时再完整复核一次,不能用滚动扫描的旧缓存虚报完工。
        if(appearsReady&&!site.stale) scan(body,site,site.layout.targets().size());
        var playerInv=inventory(owner.getInventory()); var npcInv=inventory(body.getInventory());
        if(!initial && playerInv.entrySet().stream().anyMatch(e -> site.total.containsKey(e.getKey()) && e.getValue()>site.oldPlayer.getOrDefault(e.getKey(),0)))
            site.lastCollect=now;
        site.oldPlayer=playerInv;
        Container box=site.chest!=null && body.serverLevel().isLoaded(site.chest)?container(body.serverLevel(),site.chest):null;
        var chestInv=box==null?Map.<String,Integer>of():inventory(box);
        List<BuildTaskRecord.Target> remaining=new ArrayList<>(); int matched=0,unseen=0,scopeLeft=0,scopeUnseen=0,scopeTargets=0;
        boolean near=false;
        for(int i=0;i<site.layout.targets().size();i++) {
            var target=site.layout.targets().get(i);
            if(site.matched.get(i)) matched++; else { remaining.add(target); if(site.inScope(target)) scopeLeft++; }
            if(!site.seen.get(i)) unseen++;
            if(site.inScope(target)) { scopeTargets++; if(!site.seen.get(i)) scopeUnseen++; }
            if(owner.serverLevel()==body.serverLevel() && target.pos().distSqr(owner.blockPosition())<=64) near=true;
        }
        if(near && now-site.lastMine>100 && now-site.lastCollect>100) site.nearTicks+=20; else site.nearTicks=0;
        boolean fixtures=!site.layout.entities().isEmpty()||!site.layout.blockEntityData().isEmpty();
        site.phase=CooperationCompletion.phase(site.stale,scopeTargets,scopeLeft,scopeUnseen,site.foundationLayers==0,site.layout.dropped(),fixtures);
        site.closed=!site.phase.equals("active");
        site.finished=site.phase.equals("completed")&&matched==site.layout.targets().size()&&unseen==0&&site.layout.dropped()==0&&!fixtures;
        // Fixtures are conservatively billed until independently verified, never silently omitted.
        var needs=site.closed?Map.<String,Integer>of():BuildOps.materials(subset(site.layout,remaining,true),body.registryAccess());
        List<Material> rows=new ArrayList<>(); int missing=0,available=0;
        for(var entry : site.total.entrySet()) {
            String item=entry.getKey(); int left=needs.getOrDefault(item,0),p=playerInv.getOrDefault(item,0),n=npcInv.getOrDefault(item,0),c=chestInv.getOrDefault(item,0);
            int shortOf=CooperationRoles.shortfall(left,p,n,c); missing+=shortOf; available+=Math.min(left,p+n+c);
            rows.add(new Material(item,entry.getValue(),left,p,n,c,shortOf));
        }
        var pRole=CooperationRoles.player(site.playerMode,new CooperationRoles.Evidence(now-site.lastCollect<=200,now-site.lastBuild<=200,
                now-site.lastMine<=100,available==0,missing>0,site.nearTicks,site.closed));
        pRole=CooperationRoles.assigned(site.playerMode,site.plannedPlayer,pRole,site.closed);
        var npcRole=CooperationRoles.companion(site.companionMode,pRole,available==0,missing>0,scopeLeft==0 || site.closed);
        npcRole=CooperationRoles.assigned(site.companionMode,site.plannedCompanion,npcRole,scopeLeft==0||site.closed||available==0);
        String note=site.stale?"投影已变更，此记录仅供参考；Agent 应依据最新确认投影规划后续动作。":
                "仅进度观察，不决定 Agent 任务；库存不等于交付；靠近仅为弱意图。"+(site.protectedBuilding?"已完成建筑格受持久保护，不是采集材料来源。":"建筑保护已由玩家确认解除。")
                        +(site.chest!=null&&box==null?"材料箱未加载或已移除，箱内库存未知，当前按0估算缺口。":"");
        if(site.closed) note=site.finished?"已观测到真实目标方块全部匹配；没有因此停止或改变 Agent 任务。":site.foundationLayers>0?
                "已观测到约定地基层匹配；整栋建筑未宣告完工，浮窗不决定是否继续。":
                "方块观测待验收："+(fixtures?"装饰实体 / 方块实体数据；":"")+(site.layout.dropped()>0?site.layout.dropped()+" 格原图内容暂不支持；":"")+(scopeTargets==0?"没有可施工格；":"")+"后续动作由 Agent 决定。";
        site.snapshot=new Snapshot(!site.closed,body.getName().getString(),site.title,site.dimension,site.foundationLayers==0?"full":"foundation:"+site.foundationLayers,
                site.playerMode,site.companionMode,pRole,npcRole,site.chest==null?"未指定":site.chest.toShortString(),matched,site.layout.targets().size(),unseen,
                List.copyOf(rows),site.humor,site.finished,note,site.companion.toString(),site.phase,site.taskId,site.protectedBuilding);
        CooperationTasks.publish(owner,new CooperationTasks.Task(site.taskId,"construction",site.title+(site.foundationLayers>0?"（地基 "+site.foundationLayers+" 层）":""),
                site.companion.toString(),body.getName().getString(),site.phase,scopeTargets-scopeLeft,scopeTargets,note,now));
        // 只推送客户端观察值:不发模型事件、不注入规划上下文、不更改原生任务槽或目标。
        CooperationNetwork.push(owner,site.snapshot);
    }
    public static Layout subset(Layout original,List<BuildTaskRecord.Target> targets,boolean fixtures) {
        Set<Long> cells=new HashSet<>(); for(var target:targets) cells.add(target.pos().asLong());
        Map<Long,net.minecraft.nbt.CompoundTag> data=new HashMap<>(); original.blockEntityData().forEach((k,v)->{if(cells.contains(k))data.put(k,v);});
        Map<Long,List<BuildTaskRecord.CellNeed>> needs=new HashMap<>(); original.cellNeeds().forEach((k,v)->{if(cells.contains(k))needs.put(k,v);});
        return new Layout(targets,original.size(),data,fixtures?original.entities():List.of(),needs,original.dropped());
    }
    private static Map<String,Integer> inventory(Container container) {
        Map<String,Integer> out=new TreeMap<>();
        int slots=container instanceof net.minecraft.world.entity.player.Inventory?36:container.getContainerSize();
        for(int i=0;i<slots;i++) { ItemStack stack=container.getItem(i); if(!stack.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum); }
        return out;
    }
    private static Container container(ServerLevel level,BlockPos pos) {
        BlockState state=level.getBlockState(pos);
        if(state.getBlock() instanceof ChestBlock chest) {
            // A double chest can cross a chunk boundary. Do not load the other half just to count stock.
            if(state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.CHEST_TYPE)
                    && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.CHEST_TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                    && !level.isLoaded(pos.relative(ChestBlock.getConnectedDirection(state)))) return null;
            return ChestBlock.getContainer(chest,state,level,pos,true);
        }
        return level.getBlockEntity(pos) instanceof Container container?container:null;
    }
}
