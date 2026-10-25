package com.dwinovo.numen.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.dwinovo.numen.build.Placement;
import java.util.List;

/** Arrows are camera-relative horizontal moves, never changes to the player's position. */
public final class ArchitectureControls {
    private ArchitectureControls() {}
    public static BlockPos step(Direction facing,int forward,int right,int up) {
        if(facing.getAxis()==Direction.Axis.Y) throw new IllegalArgumentException("horizontal facing required");
        Direction side=facing.getClockWise();
        return new BlockPos(facing.getStepX()*forward+side.getStepX()*right,up,
                facing.getStepZ()*forward+side.getStepZ()*right);
    }
    /** 准星指的是建筑底面的中心,而不是旋转后可能跑到身后的原点角。 */
    public static BlockPos anchor(BlockPos target, int quarters, List<BlockPos> cells) {
        return target.subtract(center(quarters,cells));
    }
    public static BlockPos rotatedOrigin(BlockPos origin,int quarters,int turns,List<BlockPos> cells) {
        return origin.offset(center(quarters,cells)).subtract(center(quarters+turns,cells));
    }
    private static BlockPos center(int quarters,List<BlockPos> cells) {
        if(cells.isEmpty()) return BlockPos.ZERO;
        Placement placement=new Placement(BlockPos.ZERO,quarters);
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(BlockPos cell:cells) {
            BlockPos pos=placement.cell(cell);
            minX=Math.min(minX,pos.getX());maxX=Math.max(maxX,pos.getX());minY=Math.min(minY,pos.getY());
            minZ=Math.min(minZ,pos.getZ());maxZ=Math.max(maxZ,pos.getZ());
        }
        return new BlockPos(Math.floorDiv(minX+maxX,2),minY,Math.floorDiv(minZ+maxZ,2));
    }
}
