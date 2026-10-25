package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.platform.Services;
import com.dwinovo.numen.api.sdk.AppearanceUi;
import com.dwinovo.numen.script.ApiReply;
import net.minecraft.client.Minecraft;
import java.util.*;

/** 编辑同伴卡内的模型草稿:查询不换装,只有保存才提交,回包绑定 NPC 与请求号。 */
public final class AppearancePicker {
    public static java.util.function.BooleanSupplier supported=()->false;
    private static long serial;
    private final UUID companion;
    private final Runnable changed;
    private final java.util.function.BooleanSupplier connected,available;
    private final java.util.function.LongSupplier clock;
    private final java.util.function.Consumer<AppearanceUi.Request> send;
    private List<String> models=List.of(),textures=List.of();
    private String model="",texture="",originalModel="",originalTexture="";
    private String message="读取 NPC 模型清单…",action="",queued="";
    private long pending=-1,sentAt,nextSend;
    private boolean busy,loaded,closed;
    private Runnable afterApply;
    AppearancePicker(UUID companion,Runnable changed) {
        this(companion,changed,()->Minecraft.getInstance().getConnection()!=null,()->supported.getAsBoolean(),
                net.minecraft.Util::getMillis,r->Services.network().sendToServer(r));
    }
    AppearancePicker(UUID companion,Runnable changed,java.util.function.BooleanSupplier connected,
                     java.util.function.BooleanSupplier available,java.util.function.LongSupplier clock,
                     java.util.function.Consumer<AppearanceUi.Request> send) {
        this.companion=companion; this.changed=changed; this.connected=connected; this.available=available; this.clock=clock; this.send=send;
    }
    List<String> modelLabels() { return models.isEmpty()?List.of("不可用"):models.stream().map(m->m.isEmpty()?"原生 / 未设置":m).toList(); }
    List<String> textureLabels() { return textures.isEmpty()?List.of("无可用贴图"):textures; }
    int modelIndex() { return Math.max(0,models.indexOf(model)); }
    int textureIndex() { return Math.max(0,textures.indexOf(texture)); }
    boolean modelsEnabled() { return loaded&&!busy&&!models.isEmpty(); }
    boolean texturesEnabled() { return loaded&&!busy&&!textures.isEmpty(); }
    boolean busy() { return busy; }
    boolean applying() { return afterApply!=null; }
    String message() { return message; }
    boolean dirty() { return !model.equals(originalModel)||!texture.equals(originalTexture); }
    boolean canSave() { return !busy&&(!dirty()||!model.isEmpty()&&!texture.isEmpty()); }
    void chooseModel(int index) {
        if(!modelsEnabled()) return;
        model=models.get(index); texture=""; textures=List.of();
        if(model.isEmpty()) { texture=originalTexture; changed.run(); return; }
        request("textures");
    }
    void chooseTexture(int index) { if(texturesEnabled()) { texture=textures.get(index); changed.run(); } }
    void refresh() { request("options"); }
    void save(Runnable after) {
        if(!canSave()) return;
        if(!dirty()) { after.run(); return; }
        afterApply=after; request("apply");
    }
    void close() { closed=true; queued=""; afterApply=null; }
    private void request(String value) {
        if(closed||busy||!connected.getAsBoolean()) return;
        if(!available.getAsBoolean()) { message="服务器不支持 NPC 模型设置，请更新服务端。"; afterApply=null; changed.run(); return; }
        action=value; queued=value; busy=true; pending=-1;
        message=value.equals("apply")?"保存 NPC 模型中…":"读取 YSM 模型 / 贴图清单…";
        AppearanceUi.Reply.onClient=this::receive; changed.run(); tick();
    }
    void tick() {
        if(closed) return;
        long now=clock.getAsLong();
        if(!queued.isEmpty()&&now>=nextSend) {
            queued=""; pending=++serial; sentAt=now; nextSend=now+150;
            send.accept(new AppearanceUi.Request(pending,companion,action,model,texture));
        }
        if(busy&&queued.isEmpty()&&now-sentAt>15000) {
            busy=false; pending=-1; afterApply=null; message="服务器未在 15 秒内回应，请刷新重试。"; changed.run();
        }
    }
    @SuppressWarnings("unchecked")
    private void receive(AppearanceUi.Reply reply) {
        if(closed||!reply.companion().equals(companion)||reply.serial()!=pending||!reply.action().equals(action)) return;
        busy=false; pending=-1;
        var result=ApiReply.parse(reply.json());
        if(!result.ok()) { afterApply=null; message=String.valueOf(result.error().get("message")); changed.run(); return; }
        switch(action) {
            case "options" -> {
                var data=(Map<String,Object>)result.value();
                originalModel=model=Objects.toString(data.get("current_model"),"");
                originalTexture=texture=Objects.toString(data.get("current_texture"),"");
                models=new ArrayList<>(strings(data.get("models"))); textures=strings(data.get("textures"));
                if(!models.isEmpty()&&!models.contains(model)) models.addFirst(model);
                loaded=true; message=models.isEmpty()?"YSM 没有可用模型，请检查模型文件与授权。":"模型与贴图在保存时应用；取消不换装。";
            }
            case "textures" -> {
                textures=strings(result.value());
                texture=model.equals(originalModel)&&textures.contains(originalTexture)?originalTexture:textures.isEmpty()?"":textures.getFirst();
                message=textures.isEmpty()?"此模型没有可用贴图，暂不能保存换装。":"模型与贴图在保存时应用；取消不换装。";
            }
            case "apply" -> {
                originalModel=model; originalTexture=texture; message="NPC 模型已保存并通过状态回读。";
                Runnable done=afterApply; afterApply=null; changed.run(); if(done!=null) done.run(); return;
            }
        }
        changed.run();
    }
    private static List<String> strings(Object value) { return value instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of(); }
}
