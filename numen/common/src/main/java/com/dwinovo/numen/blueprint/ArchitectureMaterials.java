package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Sight;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.permission.Permission;
import com.dwinovo.numen.api.permission.ProtectedBlocks;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.scan.BlockScan;
import com.dwinovo.numen.scan.BlockSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** 有界、只读的建筑材料调查:已有库存与真实可见的自然资源分开,不把房子当矿。 */
public final class ArchitectureMaterials {
    static final int SAMPLE_LIMIT = 256;
    private ArchitectureMaterials() {}
    public record Resource(String block, int visible, BlockPos nearest, String caveat) {}
    public record View(BlockPos center, int radius, Map<String,Integer> companionStock, Map<String,Integer> playerStock, boolean playerStockKnown,
                List<Resource> resources, int checked, boolean limited, int unloadedColumns, String note) {}

    static Pending<View> survey(NumenPlayer body, int radius) {
        if(radius<4 || radius>32) throw new IllegalArgumentException("materials radius must be 4..32");
        ServerLevel level=body.serverLevel();
        Vec3 eye=body.getEyePosition();
        Set<Block> targets=new HashSet<>();
        for(Block block:BuiltInRegistries.BLOCK) if(candidate(block.defaultBlockState())) targets.add(block);
        Pending<View> result=Pending.create();
        BlockScan.start(body,radius,targets,found->{
            if(body.serverLevel()!=level) {
                result.fail(new com.dwinovo.numen.script.ApiError(com.dwinovo.numen.script.ErrorKind.INTERRUPTED,
                        "Dimension changed during material survey; survey the new site again",null)); return;
            }
            BlockGetter view=loadedView(level);
            var gate=Permission.gateFor(body);
            var protection=ProtectedBlocks.of(level);
            // 先排掉完全埋藏的格,避免近处地下泥土耗尽样本、反而漏掉眼前的树。
            var exposed=found.groups().stream().flatMap(g->g.cells().keySet().stream()).distinct()
                    .filter(p->exposed(view,p)).sorted(Comparator.comparingDouble(p->p.distSqr(found.center())))
                    .limit(SAMPLE_LIMIT+1).toList();
            var cells=exposed.stream().limit(SAMPLE_LIMIT).toList();
            Map<String,Integer> counts=new TreeMap<>(); Map<String,BlockPos> nearest=new HashMap<>();
            for(BlockPos pos:cells) {
                BlockState state=view.getBlockState(pos);
                Action action=Action.breakBlock(pos,state);
                if(!candidate(state)||protection.contains(pos)||!gate.isNatural(action,view)
                        ||gate.judge(action,view).kind()==com.dwinovo.numen.permission.Verdict.Kind.DENY||!visible(view,eye,pos)) continue;
                String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                counts.merge(id,1,Integer::sum); nearest.putIfAbsent(id,pos);
            }
            List<Resource> resources=counts.entrySet().stream().map(e->new Resource(e.getKey(),e.getValue(),nearest.get(e.getKey()),
                    "Observed blocks only, not inventory or guaranteed drops; check tools, recipes and safe access")).toList();
            var owner=body.server.getPlayerList().getPlayer(body.getOwnerUuid());
            boolean ownerNearby=owner!=null && owner.serverLevel()==level && owner.distanceToSqr(body)<=radius*radius;
            BlockSearch.ScanResult coverage=found.coverage();
            boolean limited=exposed.size()>SAMPLE_LIMIT
                    ||coverage.collectCapHit()||coverage.sectionCapNote()!=null||coverage.columnsUnloaded()>0;
            String note="Design from stock first, then visible natural resources. Player stock belongs to the player and requires handover. "
                    +"Counts are a lower bound, not a complete bill, a crafting promise or permission to collect. "
                    +"Unseen/unloaded resources are UNKNOWN. Finished buildings, player placements and generated structures are excluded. "
                    +"Wood variants and raw stone are not interchangeable inventory; consult actual recipes for planks, stairs, glass or smelting. "
                    +(ownerNearby?"":"Owner is not within survey radius; player stock is UNKNOWN, not empty. ")
                    +(limited?"Survey was capped or partly unloaded; move and survey again only when needed.":"");
            if(limited) result.report("Local building-material survey is incomplete; only visible sampled natural blocks were counted.");
            result.complete(new View(found.center(),radius,stock(body.getInventory()),ownerNearby?stock(owner.getInventory()):Map.of(),ownerNearby,
                    resources,cells.size(),limited,coverage.columnsUnloaded(),note));
        });
        return result;
    }

    static boolean candidate(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.CLAY)
                || state.is(Blocks.TERRACOTTA) || state.is(Blocks.MUD) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.DIRT) || state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE);
    }

    static Map<String,Integer> stock(Container container) {
        Map<String,Integer> counts=new TreeMap<>();
        int slots=container instanceof Inventory?36:container.getContainerSize();
        for(int i=0;i<slots;i++) {
            var stack=container.getItem(i);
            if(!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);
        }
        return Map.copyOf(counts);
    }

    static boolean visible(BlockGetter view, Vec3 eye, BlockPos pos) {
        for(Vec3 point:Sight.faces(view,eye,pos)) if(Sight.trace(view,eye,point,pos).clear(null)) return true;
        return false;
    }

    static boolean exposed(BlockGetter view,BlockPos pos) {
        for(var direction:net.minecraft.core.Direction.values())
            if(!view.getBlockState(pos.relative(direction)).canOcclude()) return true;
        return false;
    }

    private static BlockGetter loadedView(ServerLevel level) {
        return new BlockGetter() {
            @Override public BlockState getBlockState(BlockPos pos) {
                var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
                return chunk==null?Blocks.BARRIER.defaultBlockState():chunk.getBlockState(pos);
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public int getHeight() { return level.getHeight(); }
            @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
        };
    }
}
