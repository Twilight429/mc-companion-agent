package com.dwinovo.numen.api.client.hud;

import com.dwinovo.numen.api.client.agent.*;
import com.dwinovo.numen.api.client.chat.SelectedCompanion;
import com.dwinovo.numen.api.client.screen.UiTheme;
import com.dwinovo.numen.api.client.screen.Nb;
import net.minecraft.client.gui.GuiGraphics;
import java.util.*;

/** 插件在原生对话流上方嵌入任务卡;只提供视图,不运行大脑或控制身体。 */
public final class CompanionPanels {
    private CompanionPanels() {}
    public record Context(UUID companion,String activity,String objective,String planningReason) {
        public Context(UUID companion,String activity) { this(companion,activity,"",""); }
    }
    public interface Panel {
        int height(Context context,int width,int available);
        void render(GuiGraphics g,Context context,int x,int y,int w,int h,int mx,int my);
        boolean click(double x,double y,int button);
        default boolean scroll(double x,double y,double amount) { return false; }
    }
    private static final List<Panel> PANELS=new ArrayList<>();
    private static final List<Panel> DRAWN=new ArrayList<>();
    public static void register(Panel panel) { PANELS.add(panel); }
    public static Context context(UUID id) {
        var loop=RemoteCompanions.get(id).orElse(null);
        if(loop==null) return new Context(id,"状态尚未同步");
        var status=loop.status();
        String activity=status.bodyTaskRunning() ? (status.activity()!=null?ActivityWords.text(status.activity()):"正在执行任务")
                : status.hold()!=null ? (status.holdReason()!=null?CauseWords.text(status.holdReason()):switch(status.hold()) {
                    case DEAD -> "等待重生";
                    case EXTERNAL -> "外接控制中";
                    case AWAY -> "等待玩家上线";
                    case OWNER_STOP -> "已按玩家要求停止";
                    case BLOCKED -> "等待模型配置";
                    case FAILED -> "规划失败，等待下一条指示";
                })
                : status.activity()!=null?ActivityWords.text(status.activity()):status.phase()!=null?"正在思考 / 规划":"待命";
        var goal=loop.goal();
        return new Context(id,activity,goal==null?"":goal.objective(),goal==null||goal.lastReason()==null?"":goal.lastReason());
    }
    public static Context selected() {
        var c=SelectedCompanion.resolveTarget();
        UUID id=c==null?null:Conversations.instance().soloOf(c);
        return id==null?null:context(id);
    }
    public static int render(GuiGraphics g,UUID id,int x,int y,int w,int available,int mx,int my) {
        DRAWN.clear();
        if(id==null||available<40) return 0;
        int used=0; Context context=context(id);
        for(Panel panel:PANELS) {
            int h=Math.clamp(panel.height(context,w,available-used),0,Math.max(0,available-used));
            if(h==0) continue;
            panel.render(g,context,x,y+used,w,h,mx,my); DRAWN.add(panel); used+=h+4;
        }
        return used;
    }
    public static boolean click(double x,double y,int button) { return DRAWN.stream().anyMatch(p->p.click(x,y,button)); }
    public static boolean scroll(double x,double y,double amount) { return DRAWN.stream().anyMatch(p->p.scroll(x,y,amount)); }
    public static void clearHits() { DRAWN.clear(); }
    public static int surface() { return UiTheme.current().band(); }
    public static int foreground() { return UiTheme.current().onBand(); }
    public static int secondary() { return UiTheme.current().textDim(); }
    public static int accent() { return UiTheme.current().accent(); }
    public static int warning() { return UiTheme.current().run(); }
    /** 原版 Screen 的事件与无障碍保留,外观使用同一套 NumenUI 按钮。 */
    public static net.minecraft.client.gui.components.Button button(int x,int y,int w,int h,String title,Runnable action) {
        return new net.minecraft.client.gui.components.Button(x,y,w,h,net.minecraft.network.chat.Component.literal(title),
                b->action.run(),narration->narration.get()) {
            private final com.dwinovo.numen.api.client.ui.widget.Button look=new com.dwinovo.numen.api.client.ui.widget.Button(
                    title,com.dwinovo.numen.api.client.ui.widget.Button.Style.NORMAL,action);
            @Override protected void renderWidget(GuiGraphics g,int mx,int my,float delta) {
                look.setBounds(getX(),getY(),getWidth(),getHeight()); look.setEnabled(active);
                look.render(new com.dwinovo.numen.api.client.ui.mc.McDrawSurface(g,net.minecraft.client.Minecraft.getInstance().font),
                        com.dwinovo.numen.api.client.screen.settings.HostThemeColors.current(),mx,my,net.minecraft.Util.getMillis());
            }
        };
    }
    public static void box(GuiGraphics g,int x,int y,int w,int h) {
        var th=UiTheme.current(); g.fill(x,y,x+w,y+h,th.band()); Nb.border(g,x,y,w,h,1,th.aiBorder());
    }
    public static void text(GuiGraphics g,String s,int x,int y,int w,boolean accent) {
        var font=net.minecraft.client.Minecraft.getInstance().font;
        Nb.text(g,font,Nb.clip(font,s,w),x,y,accent?UiTheme.current().accent():UiTheme.current().onBand());
    }
}
