package com.dwinovo.numen.blueprint.client;

import com.dwinovo.numen.blueprint.ArchitectureViewPayload;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** A read-only, textured isometric miniature. Its rotation never changes the world draft. */
public final class ArchitecturePrototype {
    private record Cell(BlockPos pos,BlockState state,boolean unknown) {}
    private static ArchitectureViewPayload prepared;
    private static List<Cell> shell=List.of();
    private static int minX,minY,minZ,maxX,maxY,maxZ,total;
    private static boolean truncated;
    private ArchitecturePrototype() {}
    private static void prepare(ArchitectureViewPayload view) {
        if(prepared==view) return;
        prepared=view; var all=new LinkedHashMap<BlockPos,Cell>();
        minX=minY=minZ=Integer.MAX_VALUE; maxX=maxY=maxZ=Integer.MIN_VALUE;
        for(var c:view.cells()) {
            BlockState state=Block.stateById(c.state()); if(state.isAir()&&!c.unknown()) continue;
            BlockPos p=c.relative(); all.put(p,new Cell(p,state,c.unknown()));
            minX=Math.min(minX,p.getX()); minY=Math.min(minY,p.getY()); minZ=Math.min(minZ,p.getZ());
            maxX=Math.max(maxX,p.getX()+1); maxY=Math.max(maxY,p.getY()+1); maxZ=Math.max(maxZ,p.getZ()+1);
        }
        total=all.size(); var visible=new ArrayList<Cell>();
        for(var cell:all.values()) {
            boolean enclosed=solid(cell);
            if(enclosed) for(var direction:Direction.values()) if(!solid(all.get(cell.pos().relative(direction)))) { enclosed=false; break; }
            if(!enclosed) visible.add(cell);
        }
        truncated=visible.size()>8000;
        shell=List.copyOf(visible.subList(0,Math.min(8000,visible.size())));
    }
    private static boolean solid(Cell c) {
        return c!=null&&!c.unknown()&&c.state().isSolidRender(EmptyBlockGetter.INSTANCE,c.pos());
    }
    public static void render(GuiGraphics g,ArchitectureViewPayload view,int x,int y,int width,int height) {
        var mc=Minecraft.getInstance();
        g.fill(x,y,x+width,y+height,0xFF20262E);
        if(view==null) { g.drawCenteredString(mc.font,"读取建筑原型…",x+width/2,y+height/2,0xC7DCE8); return; }
        prepare(view);
        if(total==0) { g.drawCenteredString(mc.font,"此模板没有可展示的方块",x+width/2,y+height/2,0xC7DCE8); return; }
        double sx=maxX-minX,sy=maxY-minY,sz=maxZ-minZ,span=Math.hypot(sx,sz);
        float scale=(float)Math.max(.1,Math.min((width-18)/Math.max(1,span),(height-25)/Math.max(1,sy*.866+span*.5))*.83);
        float yaw=45+(net.minecraft.Util.getMillis()%24000L)*360f/24000;
        g.flush(); g.enableScissor(x,y,x+width,y+height);
        var pose=g.pose(); pose.pushPose();
        var buffers=mc.renderBuffers().bufferSource();
        try {
            RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
            RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,Minecraft.ON_OSX);
            Lighting.setupFor3DItems();
            pose.translate(x+width/2.0,y+height/2.0+3,180);
            pose.scale(scale,-scale,scale);
            pose.mulPose(Axis.XP.rotationDegrees(30)); pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.translate(-(minX+maxX)/2.0,-(minY+maxY)/2.0,-(minZ+maxZ)/2.0);
            for(var cell:shell) {
                if(cell.unknown()||cell.state().getRenderShape()!=RenderShape.MODEL) continue;
                pose.pushPose(); pose.translate(cell.pos().getX(),cell.pos().getY(),cell.pos().getZ());
                mc.getBlockRenderer().renderSingleBlock(cell.state(),pose,buffers,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
            buffers.endBatch();
            var lines=buffers.getBuffer(RenderType.lines());
            int step=Math.max(1,(int)Math.ceil(Math.max(sx,sz)/24));
            for(int ix=minX;ix<=maxX;ix+=step) LevelRenderer.renderLineBox(pose,lines,ix,minY-.03,minZ,ix,minY-.03,maxZ,.24f,.30f,.36f,.7f);
            for(int iz=minZ;iz<=maxZ;iz+=step) LevelRenderer.renderLineBox(pose,lines,minX,minY-.03,iz,maxX,minY-.03,iz,.24f,.30f,.36f,.7f);
            for(var cell:shell) if(cell.unknown()||cell.state().getRenderShape()!=RenderShape.MODEL) {
                var p=cell.pos(); LevelRenderer.renderLineBox(pose,lines,p.getX(),p.getY(),p.getZ(),p.getX()+1,p.getY()+1,p.getZ()+1,
                        cell.unknown()?1f:.3f,.5f,1f,.85f);
            }
            buffers.endBatch(RenderType.lines());
        } finally {
            pose.popPose(); RenderSystem.disableDepthTest(); RenderSystem.depthMask(true);
            // Keep the model's color, not its depth: later GUI labels must stay on top.
            RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,Minecraft.ON_OSX);
            Lighting.setupFor3DItems(); g.disableScissor();
        }
        g.drawString(mc.font,(maxX-minX)+" × "+(maxY-minY)+" × "+(maxZ-minZ)+" 格",x+5,y+5,0xE3EBF3,false);
        if(truncated) g.drawString(mc.font,"外壳预览限 8000 格；施工数据未截断",x+5,y+height-12,0xFFD28B,false);
    }
}
