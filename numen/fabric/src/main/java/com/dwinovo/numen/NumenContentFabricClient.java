package com.dwinovo.numen;

import com.dwinovo.numen.debug.PathDebugPayload;
import com.dwinovo.numen.debug.client.PathDebugRenderer;
import com.dwinovo.numen.debug.client.PathDebugState;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Fabric 的客户端入口:只接寻路调试的覆盖层——下行快照、世界里画线、断线清空。 */
public class NumenContentFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        com.dwinovo.numen.blueprint.client.ArchitectureClient.install();
        com.dwinovo.numen.cooperation.client.CooperationClient.install();
        com.dwinovo.numen.cooperation.client.CooperationClient.supported=()->ClientPlayNetworking.canSend(com.dwinovo.numen.cooperation.CooperationNetwork.Request.TYPE);
        var cooperationKey=net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new net.minecraft.client.KeyMapping("操作合作任务浮层",org.lwjgl.glfw.GLFW.GLFW_KEY_C,"Numen"));
        var socialKey=net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new net.minecraft.client.KeyMapping("回应伙伴互动",org.lwjgl.glfw.GLFW.GLFW_KEY_H,"Numen"));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client->{
            while(cooperationKey.consumeClick()) if(client.screen==null) com.dwinovo.numen.cooperation.client.CooperationClient.open();
            while(socialKey.consumeClick()) if(client.screen==null&&client.player!=null) com.dwinovo.numen.cooperation.client.CooperationClient.respond(client.player.isShiftKeyDown());
        });
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((graphics,ticks)->com.dwinovo.numen.cooperation.client.CooperationClient.hud(graphics));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->com.dwinovo.numen.cooperation.client.CooperationClient.clear());
        com.dwinovo.numen.blueprint.client.ArchitectureClient.supported = () -> ClientPlayNetworking.canSend(
                com.dwinovo.numen.blueprint.ArchitectureNetwork.Request.TYPE);
        var architectureKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new net.minecraft.client.KeyMapping("建筑投影库", org.lwjgl.glfw.GLFW.GLFW_KEY_B, "Numen"));
        var rotateKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
                new net.minecraft.client.KeyMapping("旋转建筑投影", org.lwjgl.glfw.GLFW.GLFW_KEY_R, "Numen"));
        var moveUp=architectureKey("投影向前",org.lwjgl.glfw.GLFW.GLFW_KEY_UP);
        var moveDown=architectureKey("投影向后",org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN);
        var moveLeft=architectureKey("投影向左",org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT);
        var moveRight=architectureKey("投影向右",org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT);
        var heightUp=architectureKey("升高建筑投影",org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP);
        var heightDown=architectureKey("降低建筑投影",org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN);
        var confirm=architectureKey("确认投影位置（不施工）",org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        var cancel=architectureKey("撤销建筑投影",org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE);
        var reposition=architectureKey("重新调整建筑投影",org.lwjgl.glfw.GLFW.GLFW_KEY_P);
        var fullProjection=architectureKey("切换完整 / 最低未完成层投影",org.lwjgl.glfw.GLFW.GLFW_KEY_J);
        final long[] nextMove={0};
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (architectureKey.consumeClick()) if (client.screen == null) com.dwinovo.numen.blueprint.client.ArchitectureClient.open();
            while (rotateKey.consumeClick()) if (client.screen == null) com.dwinovo.numen.blueprint.client.ArchitectureClient.rotate();
            while(confirm.consumeClick()) if(client.screen==null) com.dwinovo.numen.blueprint.client.ArchitectureClient.confirm();
            while(cancel.consumeClick()) if(client.screen==null) com.dwinovo.numen.blueprint.client.ArchitectureClient.cancel();
            while(reposition.consumeClick()) if(client.screen==null) com.dwinovo.numen.blueprint.client.ArchitectureClient.reposition();
            while(fullProjection.consumeClick()) if(client.screen==null) com.dwinovo.numen.blueprint.client.ArchitectureClient.toggleProjection();
            long now=net.minecraft.Util.getMillis();
            if(client.screen==null&&now>=nextMove[0]&&com.dwinovo.numen.blueprint.client.ArchitectureClient.positioning) {
                int forward=(moveUp.isDown()?1:0)-(moveDown.isDown()?1:0);
                int right=(moveRight.isDown()?1:0)-(moveLeft.isDown()?1:0);
                int up=(heightUp.isDown()?1:0)-(heightDown.isDown()?1:0);
                if(forward!=0||right!=0||up!=0) { com.dwinovo.numen.blueprint.client.ArchitectureClient.shift(forward,right,up); nextMove[0]=now+170; }
            }
            com.dwinovo.numen.blueprint.client.ArchitectureClient.tick();
        });
        PathDebugState.install();
        ClientPlayNetworking.registerGlobalReceiver(PathDebugPayload.TYPE,
                (payload, context) -> context.client().execute(() -> PathDebugPayload.handle(payload)));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (context.matrixStack() != null) {
                PathDebugRenderer.render(context.matrixStack(), context.camera());
                com.dwinovo.numen.blueprint.client.ArchitectureRenderer.render(context.matrixStack(), context.camera());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PathDebugState.clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> com.dwinovo.numen.blueprint.client.ArchitectureClient.clear());
    }
    private static net.minecraft.client.KeyMapping architectureKey(String name,int key) {
        return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(new net.minecraft.client.KeyMapping(name,key,"Numen 建筑投影"));
    }
}
