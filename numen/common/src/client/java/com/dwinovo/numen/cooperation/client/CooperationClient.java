package com.dwinovo.numen.cooperation.client;

import com.dwinovo.numen.cooperation.*;
import com.dwinovo.numen.api.platform.Services;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

public final class CooperationClient {
    public static final CooperationPanel panel=new CooperationPanel();
    public static CooperationNetwork.View view=new CooperationNetwork.View(null,null,"");
    public static java.util.function.BooleanSupplier supported=()->false;
    private static final Gson JSON=new Gson();
    private static String message="靠近侧边标签查看同伴动态；调整任务请直接对话。";
    private CooperationClient() {}
    public static void install() {
        CooperationNetwork.Reply.onClient=reply->{
            view=JSON.fromJson(reply.json(),CooperationNetwork.View.class);
            if(!view.message().isEmpty()) message=view.message();
        };
    }
    public static String message() { return message; }
    public static void send(String action,String value,BlockPos pos) {
        if(supported.getAsBoolean()&&Minecraft.getInstance().getConnection()!=null)
            Services.network().sendToServer(new CooperationNetwork.Request(action,value,pos));
    }
    public static void send(String action,String value) { send(action,value,BlockPos.ZERO); }
    public static void open() {
        var mc=Minecraft.getInstance();
        if(mc.player==null) return;
        if(!supported.getAsBoolean()) { mc.player.sendSystemMessage(Component.literal("此服务器未安装合作任务扩展")); return; }
        panel.clearInput();
        mc.setScreen(new CooperationScreen()); send("status","");
    }
    public static void respond(boolean decline) {
        var invite=view.invitation(); var mc=Minecraft.getInstance();
        if(invite==null||mc.level==null||mc.level.getGameTime()>=invite.until()) {
            if(mc.player!=null) mc.player.sendSystemMessage(Component.literal("当前没有有效互动邀请")); return;
        }
        send(decline?"decline":"accept",invite.token().toString());
    }
    public static void hud(GuiGraphics graphics) {
        var mc=Minecraft.getInstance(); var invite=view.invitation();
        if(mc.player!=null&&!mc.options.hideGui&&!(mc.screen instanceof CooperationScreen)) {
            int mx=(int)(mc.mouseHandler.xpos()*graphics.guiWidth()/mc.getWindow().getScreenWidth());
            int my=(int)(mc.mouseHandler.ypos()*graphics.guiHeight()/mc.getWindow().getScreenHeight());
            renderSide(graphics,mx,my,mc.screen!=null);
        }
        if(invite==null||mc.level==null||mc.level.getGameTime()>=invite.until()||mc.options.hideGui) return;
        String text="[伙伴互动] "+invite.text()+"  H 回应 / Shift+H 婉拒";
        graphics.drawString(mc.font,text,Math.max(6,(graphics.guiWidth()-mc.font.width(text))/2),graphics.guiHeight()-65,0xFFFFFF);
    }
    public static void renderSide(GuiGraphics graphics,int mx,int my,boolean controls) {
        var context=com.dwinovo.numen.api.client.hud.CompanionPanels.selected();
        if(context==null&&view.plan()!=null&&view.plan().companionId()!=null)
            context=com.dwinovo.numen.api.client.hud.CompanionPanels.context(java.util.UUID.fromString(view.plan().companionId()));
        if(context!=null) panel.renderDrawer(graphics,context,mx,my,controls);
    }
    public static void clear() { panel.clearInput(); view=new CooperationNetwork.View(null,null,""); message="连接已断开，等待 Agent 状态同步。"; }
}
