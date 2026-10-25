package com.dwinovo.numen.blueprint;

import com.dwinovo.numen.build.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureControlsTest {
    @org.junit.jupiter.api.BeforeAll static void boot() { com.dwinovo.numen.NumenApiFixture.install(); }
    private static final List<BlockPos> CELLS=List.of(new BlockPos(0,2,0),new BlockPos(8,9,4));
    @Test void crosshairPlacementKeepsTheBaseCentreUnderTheCursorAtEveryRotation() {
        var target=new BlockPos(100,64,200);
        for(int q=0;q<4;q++) {
            var origin=ArchitectureControls.anchor(target,q,CELLS);
            var placement=new Placement(origin,q);
            var a=placement.cell(CELLS.get(0));var b=placement.cell(CELLS.get(1));
            assertEquals(target.getX(),Math.floorDiv(a.getX()+b.getX(),2));
            assertEquals(target.getZ(),Math.floorDiv(a.getZ()+b.getZ(),2));
            assertEquals(target.getY(),Math.min(a.getY(),b.getY()));
        }
    }
    @Test void fourCentredRotationsReturnToTheExactOriginalAnchorWithoutDrift() {
        var original=new BlockPos(-100,70,12);var now=original;
        for(int q=0;q<4;q++) now=ArchitectureControls.rotatedOrigin(now,q,1,CELLS);
        assertEquals(original,now);
    }
    @Test void centreRotationPreservesTheCrosshairAnchorIncludingEvenDimensions() {
        var cells=List.of(BlockPos.ZERO,new BlockPos(5,3,8));
        var target=new BlockPos(-101,62,-201);
        for(int q=0;q<4;q++) assertEquals(ArchitectureControls.anchor(target,q+1,cells),
                ArchitectureControls.rotatedOrigin(ArchitectureControls.anchor(target,q,cells),q,1,cells));
    }
    @Test void arrowsStayRelativeToThePlayerNotToTheRotatingBuilding() {
        assertEquals(new BlockPos(0,0,-5),ArchitectureControls.step(Direction.NORTH,5,0,0));
        assertEquals(new BlockPos(0,1,1),ArchitectureControls.step(Direction.EAST,0,1,1));
    }
}
