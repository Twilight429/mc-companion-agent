package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.NumenApiFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureMaterialsTest {
    @BeforeAll static void boot() { NumenApiFixture.install(); }
    @Test void countedStockIsActualItemsNotImaginedCraftingConversions() {
        var stock=new SimpleContainer(new ItemStack(Items.BIRCH_LOG,3),new ItemStack(Items.BIRCH_LOG,2),
                new ItemStack(Items.OAK_PLANKS,7),ItemStack.EMPTY);
        assertEquals(java.util.Map.of("minecraft:birch_log",5,"minecraft:oak_planks",7),ArchitectureMaterials.stock(stock));
        assertFalse(ArchitectureMaterials.stock(stock).containsKey("minecraft:birch_planks"));
    }
    @Test void materialSurveyDoesNotSuggestRareCraftedBuildingBlocksAsNaturalDeposits() {
        assertTrue(ArchitectureMaterials.candidate(Blocks.STONE.defaultBlockState()));
        assertTrue(ArchitectureMaterials.candidate(Blocks.SAND.defaultBlockState()));
        assertFalse(ArchitectureMaterials.candidate(Blocks.QUARTZ_BLOCK.defaultBlockState()));
        assertFalse(ArchitectureMaterials.candidate(Blocks.OAK_PLANKS.defaultBlockState()));
        assertFalse(ArchitectureMaterials.candidate(Blocks.DIAMOND_ORE.defaultBlockState()));
    }
    @Test void aBlockBehindAnOpaqueWallIsNotVisibleMaterial() {
        var world=new com.dwinovo.numen.pathing.TestWorld();
        var target=new BlockPos(0,1,4);world.set(target,Blocks.STONE.defaultBlockState());
        var eye=new Vec3(.5,1.5,.5);
        assertTrue(ArchitectureMaterials.visible(world,eye,target));
        for(int x=-1;x<=1;x++) for(int y=0;y<=3;y++) world.set(new BlockPos(x,y,2),Blocks.BARRIER.defaultBlockState());
        assertFalse(ArchitectureMaterials.visible(world,eye,target));
    }
    @Test void buriedSoilDoesNotConsumeTheVisibleMaterialSamplingBudget() {
        var world=new com.dwinovo.numen.pathing.TestWorld().ground(0,Blocks.DIRT.defaultBlockState());
        assertFalse(ArchitectureMaterials.exposed(world,new BlockPos(0,-1,0)));
        assertTrue(ArchitectureMaterials.exposed(world,BlockPos.ZERO));
    }
}
