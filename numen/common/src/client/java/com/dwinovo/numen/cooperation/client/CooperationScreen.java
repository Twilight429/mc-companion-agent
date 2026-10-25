package com.dwinovo.numen.cooperation.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** C 仅释放鼠标以便悬停只读浮窗,不暂停世界、不提供任务控制。 */
public final class CooperationScreen extends Screen {
    public CooperationScreen() { super(Component.literal("Agent 状态浮窗（只读）")); }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float delta) {}
    @Override public void render(GuiGraphics g,int x,int y,float delta) { CooperationClient.renderSide(g,x,y,true); }
    @Override public boolean mouseScrolled(double x,double y,double sx,double sy) { return CooperationClient.panel.scroll(x,y,sy); }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_C) { onClose(); return true; }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }
}
