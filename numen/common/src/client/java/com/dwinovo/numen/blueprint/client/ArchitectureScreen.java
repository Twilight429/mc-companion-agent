package com.dwinovo.numen.blueprint.client;

import com.dwinovo.numen.api.client.hud.CompanionPanels;

import com.dwinovo.numen.blueprint.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** A chooser and separate rotating prototype. Placement is edited with keys in the world, not coordinate widgets. */
public final class ArchitectureScreen extends Screen {
    private EditBox search;
    private String selected="",editing;
    private int page,leftWidth,rightX,prototypeBottom,detailScroll;
    public ArchitectureScreen() { super(Component.literal("建筑模板库")); }
    public void refreshed() { if(search!=null) editing=search.getValue(); rebuildWidgets(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float delta) {
        // No menu post-blur after our text/model has already been drawn.
    }
    @Override protected void init() {
        leftWidth=Math.max(130,width*42/100-20); rightX=leftWidth+28;
        search=addRenderableWidget(new EditBox(font,12,31,Math.max(50,leftWidth-92),20,Component.literal("风格与用途")));
        search.setMaxLength(600); search.setValue(editing==null?ArchitectureClient.query:editing);
        button(leftWidth-74,31,40,"搜索",()->ArchitectureClient.send("search",search.getValue(),BlockPos.ZERO,0));
        button(leftWidth-30,31,42,"刷新",()->ArchitectureClient.send("refresh",search.getValue(),BlockPos.ZERO,0));
        int slots=slots(),start=page*slots;
        if(start>=ArchitectureClient.matches.size()) { page=0; start=0; }
        if(selected()==null&&!ArchitectureClient.matches.isEmpty()) selected=ArchitectureClient.matches.getFirst().building().id();
        for(int i=start;i<Math.min(start+slots,ArchitectureClient.matches.size());i++) {
            var entry=ArchitectureClient.matches.get(i).building();
            button(12,60+(i-start)*36,leftWidth,(entry.id().equals(selected)?"▶ ":"")+(entry.compatible()?"":"! ")+entry.title(),()->{
                selected=entry.id(); detailScroll=0; editing=search.getValue(); rebuildWidgets();
            });
        }
        button(12,height-80,55,"上一页",()->{if(page>0){page--;refreshed();}});
        button(72,height-80,55,"下一页",()->{if((page+1)*slots<ArchitectureClient.matches.size()){page++;refreshed();}});
        int rightWidth=Math.max(100,width-rightX-12);
        button(rightX,height-80,Math.max(60,rightWidth/2-3),"选择并投放",this::deploy).active=selected()!=null;
        button(rightX+rightWidth/2+3,height-80,Math.max(40,rightWidth/2-3),"关闭",this::onClose);
        button(rightX,height-105,Math.max(60,rightWidth/2-3),"复制来源署名",()->{
            if(selected()!=null) minecraft.keyboardHandler.setClipboard(ArchitectureDrafts.credit(selected()));
        }).active=selected()!=null;
        button(rightX+rightWidth/2+3,height-105,Math.max(40,rightWidth/2-3),"复制材料清单",()->{
            var entry=selected(); if(entry==null) return;
            StringBuilder text=new StringBuilder(ArchitectureDrafts.credit(entry)).append(entry.compatible()?"\n物品清单\n":"\n不兼容：仅方块统计，非可支付清单\n");
            (entry.compatible()?entry.materials():entry.blocks()).forEach((id,count)->text.append(id).append(" × ").append(count).append('\n'));
            minecraft.keyboardHandler.setClipboard(text.toString());
        }).active=selected()!=null;
        prototypeBottom=Math.max(90,(height-120)*65/100);
        if(selected()!=null) ArchitectureClient.inspect(selected);
    }
    private int slots() { return Math.max(1,(height-145)/36); }
    private ArchitectureCatalog.Entry selected() {
        return ArchitectureClient.matches.stream().map(ArchitectureSearch.Match::building).filter(e->e.id().equals(selected)).findFirst().orElse(null);
    }
    private Button button(int x,int y,int w,String title,Runnable action) {
        return addRenderableWidget(CompanionPanels.button(x,y,w,20,title,action));
    }
    private void deploy() {
        var entry=selected(); if(entry==null||minecraft.player==null) return;
        BlockPos spot=minecraft.player.blockPosition().relative(minecraft.player.getDirection(),4);
        if(minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit&&hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK)
            spot=hit.getBlockPos().relative(hit.getDirection());
        var thumbnail=ArchitectureClient.thumbnail(entry.id());
        var cells=thumbnail!=null?thumbnail.cells().stream().map(ArchitectureViewPayload.Cell::relative).toList():
                List.of(BlockPos.ZERO,new BlockPos(entry.size().get(0)-1,entry.size().get(1)-1,entry.size().get(2)-1));
        ArchitectureClient.send("preview",entry.id(),ArchitectureControls.anchor(spot,0,cells),0); onClose();
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==GLFW.GLFW_KEY_ENTER&&search.isFocused()) { ArchitectureClient.send("search",search.getValue(),BlockPos.ZERO,0); return true; }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(x>=rightX) { detailScroll=Math.max(0,detailScroll-(int)(vertical*3)); return true; }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    private void line(GuiGraphics g,String text,int y,int color) {
        CompanionPanels.text(g,text,rightX,y,Math.max(50,width-rightX-18),false);
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        CompanionPanels.box(g,4,4,width-8,height-8);
        var entry=selected();
        if(entry!=null) {
            var thumbnail=ArchitectureClient.thumbnail(entry.id());
            ArchitecturePrototype.render(g,thumbnail,rightX,31,width-rightX-12,Math.max(48,prototypeBottom-31));
        }
        super.render(g,mouseX,mouseY,delta);
        CompanionPanels.text(g,title.getString(),12,12,leftWidth,true);
        CompanionPanels.text(g,ArchitectureClient.recommended?"同伴推荐 · 请选择真实模板":"本地建筑原型 · 自动水平旋转",rightX,12,width-rightX-12,true);
        for(int i=page*slots();i<Math.min((page+1)*slots(),ArchitectureClient.matches.size());i++) {
            var e=ArchitectureClient.matches.get(i).building();
            CompanionPanels.text(g,"作者: "+e.author()+" · "+e.cells()+" 格",16,83+(i-page*slots())*36,leftWidth,false);
        }
        if(entry!=null) {
            int yy=prototypeBottom+6;
            var lines=new ArrayList<String>();
            lines.add(entry.title()+" · "+entry.size().get(0)+"×"+entry.size().get(1)+"×"+entry.size().get(2));
            lines.add("作者: "+entry.author()); lines.add("来源: "+entry.sourceUrl());
            lines.add("授权: "+entry.license());
            if(!entry.compatible()) lines.add("不可施工："+String.join("；",entry.issues()));
            else lines.add("原图 "+entry.cells()+" 格 · 施工不支持 "+entry.notBuilt()+" 格");
            lines.add(entry.compatible()?"材料（背包/箱子库存另看 C）":"方块统计（不是可支付材料清单）");
            (entry.compatible()?entry.materials():entry.blocks()).entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed())
                    .forEach(m->lines.add(m.getKey().replace("minecraft:","")+" × "+m.getValue()));
            var wrappedLines=lines.stream().flatMap(text->font.split(Component.literal(text).withStyle(s->s.withColor(CompanionPanels.foreground()&0xFFFFFF)),Math.max(50,width-rightX-16)).stream()).toList();
            int visible=Math.max(0,(height-111-yy)/11);
            detailScroll=Math.clamp(detailScroll,0,Math.max(0,wrappedLines.size()-visible));
            for(int i=detailScroll;i<Math.min(wrappedLines.size(),detailScroll+visible);i++) {
                g.drawString(font,wrappedLines.get(i),rightX,yy,-1,false); yy+=11;
            }
        } else line(g,"请选择模板；原型预览不会替换世界投影。",70,0xFFDFE8F0);
        CompanionPanels.text(g,"方向键平移 · PageUp/Down 高度 · R 旋转 · Enter 确认 · J 全图/逐层 · Backspace 撤销",12,height-51,width-24,true);
        int y=height-36;
        for(var wrapped:font.split(Component.literal(ArchitectureClient.message).withStyle(s->s.withColor(CompanionPanels.warning()&0xFFFFFF)),width-24)) {
            if(y>height-12) break; g.drawString(font,wrapped,12,y,-1,false); y+=11;
        }
    }
}
