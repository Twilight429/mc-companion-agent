package com.dwinovo.numen.cooperation.client;

import com.dwinovo.numen.api.client.hud.CompanionPanels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** QQ 式只读侧边浮窗:读取原生大脑 / 身体状态,不发送控制、分工或任务切换。 */
public final class CooperationPanel {
    private float shown;
    private long lastFrame;
    private int scroll,left,top,width,height;
    private boolean expanded;

    public void clearInput() { expanded=false;shown=0;scroll=0;lastFrame=0; }
    public void renderDrawer(GuiGraphics g,CompanionPanels.Context context,int mx,int my,boolean mouseAvailable) {
        width=Math.min(280,g.guiWidth()-36);height=Math.min(292,g.guiHeight()-24);
        if(width<100||height<90) return;
        top=Math.max(12,(g.guiHeight()-height)/2);
        int handleX=g.guiWidth()-18,handleY=g.guiHeight()/2-20;
        boolean handle=mx>=handleX&&mx<g.guiWidth()&&my>=handleY&&my<handleY+40;
        boolean inside=shown>.05f&&mx>=left&&mx<g.guiWidth()&&my>=top&&my<top+height;
        expanded=mouseAvailable&&(handle||inside);
        long now=net.minecraft.Util.getMillis();
        float blend=lastFrame==0?1f:Math.clamp((now-lastFrame)/120f,0f,1f);lastFrame=now;
        shown+=(expanded?1f-shown:-shown)*blend;
        if(shown<.005f) shown=0;
        left=g.guiWidth()-(int)((width+18)*shown);
        if(shown>0) {
            g.enableScissor(0,0,g.guiWidth(),g.guiHeight());
            renderContents(g,context,left,top,width,height,mx,my);
            g.disableScissor();
        }
        CompanionPanels.box(g,handleX,handleY,18,40);
        CompanionPanels.text(g,"任",handleX+4,handleY+6,12,true);
        CompanionPanels.text(g,"务",handleX+4,handleY+22,12,true);
    }

    private void renderContents(GuiGraphics g,CompanionPanels.Context context,int x,int y,int w,int h,int mx,int my) {
        var mc=Minecraft.getInstance();
        CompanionPanels.box(g,x,y,w,h);
        CompanionPanels.text(g,"同伴动态",x+9,y+8,w-18,true);
        int yy=y+25;
        var entity=mc.level==null?null:mc.level.getPlayerByUUID(context.companion());
        String name=entity==null?"同伴":entity.getName().getString();
        CompanionPanels.text(g,name+" · "+context.activity(),x+9,yy,w-18,false);yy+=16;
        yy=wrapped(g,context.objective().isEmpty()?"当前目标：暂无":"当前目标："+context.objective(),x+9,yy,w-18,3);
        if(!context.planningReason().isEmpty()) yy=wrapped(g,"规划反馈："+context.planningReason(),x+9,yy+3,w-18,2);
        yy+=8;
        var plan=CooperationClient.view.plan();
        if(plan!=null&&context.companion().toString().equals(plan.companionId())&&yy<y+h-75) {
            CompanionPanels.text(g,"建筑进度 · 最近记录",x+9,yy,w-18,true);yy+=14;
            CompanionPanels.text(g,plan.building(),x+9,yy,w-18,false);yy+=13;
            CompanionPanels.text(g,plan.matched()+" / "+plan.targets()+" 格"+(plan.unseen()>0?" · 未加载 "+plan.unseen():""),x+9,yy,w-18,false);yy+=13;
            bar(g,x+9,yy,w-18,plan.targets()==0?0:(double)plan.matched()/plan.targets());yy+=10;
            var materials=plan.materials().stream().filter(m->m.remaining()>0).toList();
            if(!materials.isEmpty()&&yy<y+h-65) {
                CompanionPanels.text(g,"材料缺口 "+materials.stream().mapToInt(m->m.missing()).sum()+" · 悬停查看",x+9,yy,w-18,false);yy+=14;
                int columns=Math.max(1,(w-18)/44),capacity=Math.max(0,(y+h-39-yy)/32)*columns;
                scroll=Math.clamp(scroll,0,Math.max(0,materials.size()-Math.max(1,capacity)));
                List<Component> tooltip=null;
                for(int i=0;i<capacity&&i+scroll<materials.size();i++) {
                    var material=materials.get(i+scroll);int ix=x+9+i%columns*44,iy=yy+i/columns*32;
                    var id=ResourceLocation.tryParse(material.item());
                    var stack=id==null?ItemStack.EMPTY:new ItemStack(BuiltInRegistries.ITEM.get(id));
                    if(!stack.isEmpty()) g.renderItem(stack,ix+12,iy);
                    int held=material.player()+material.companion()+material.sharedChest();
                    bar(g,ix,iy+18,39,(double)held/Math.max(1,material.remaining()));
                    CompanionPanels.text(g,Math.min(held,material.remaining())+"/"+material.remaining(),ix,iy+23,41,false);
                    if(mx>=ix&&mx<ix+41&&my>=iy&&my<iy+31) tooltip=List.of(stack.isEmpty()?Component.literal(material.item()):stack.getHoverName(),
                            Component.literal("待建 "+material.remaining()+" · 缺 "+material.missing()),
                            Component.literal("玩家 "+material.player()+" · NPC "+material.companion()+" · 共享箱 "+material.sharedChest()));
                }
                if(tooltip!=null) g.renderComponentTooltip(mc.font,tooltip,mx,my);
            } else if(materials.isEmpty()) CompanionPanels.text(g,"当前没有待补充的方块材料。",x+9,yy,w-18,false);
        } else if(yy<y+h-42) wrapped(g,"当前行动暂无数量进度。",x+9,yy,w-18,3);
        CompanionPanels.text(g,"调整或结束任务：直接与 Agent 对话",x+9,y+h-17,w-18,false);
    }
    private static int wrapped(GuiGraphics g,String text,int x,int y,int width,int lines) {
        int n=0;
        for(var line:Minecraft.getInstance().font.split(Component.literal(text),width)) {
            if(n++>=lines) break;g.drawString(Minecraft.getInstance().font,line,x,y,CompanionPanels.foreground(),false);y+=11;
        }
        return y;
    }
    private static void bar(GuiGraphics g,int x,int y,int width,double progress) {
        g.fill(x,y,x+width,y+3,(CompanionPanels.secondary()&0xFFFFFF)|0x40000000);
        g.fill(x,y,x+(int)(width*Math.clamp(progress,0d,1d)),y+3,CompanionPanels.accent());
    }
    public boolean scroll(double mx,double my,double delta) {
        if(!expanded||mx<left||mx>=left+width||my<top||my>=top+height) return false;
        scroll=Math.max(0,scroll-(int)Math.signum(delta));return true;
    }
}
