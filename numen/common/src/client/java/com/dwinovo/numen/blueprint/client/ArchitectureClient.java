package com.dwinovo.numen.blueprint.client;

import com.dwinovo.numen.blueprint.*;
import com.dwinovo.numen.api.platform.Services;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Catalog prototypes never become world drafts. World positioning sends one revision at a time. */
public final class ArchitectureClient {
    private static final Gson JSON=new Gson();
    public static ArchitectureViewPayload view=ArchitectureViewPayload.empty();
    public static List<ArchitectureSearch.Match> matches=List.of();
    public static String message="把有权使用的投影放入本实例 schematics，再点刷新导入。",query="";
    public static boolean recommended,positioning,fullProjection;
    public static java.util.function.BooleanSupplier supported=()->false;
    private record Request(String action,String value,BlockPos pos,int rotation) {}
    private static final Deque<Request> requests=new ArrayDeque<>();
    private static final Map<String,ArchitectureViewPayload> thumbnails=new LinkedHashMap<>();
    private static final Set<String> inspecting=new HashSet<>(),failed=new HashSet<>();
    private static BlockPos offset=BlockPos.ZERO;
    private static int turns;
    private static boolean waiting,confirm,cancel;
    private static long nextSend,waitSince;
    private static Request lastRequest;
    private ArchitectureClient() {}
    public static void install() {
        ArchitectureViewPayload.onClient=payload->{
            boolean moved=waiting&&lastRequest!=null&&lastRequest.action().equals("move")
                    && payload.name().equals(view.name())&&payload.dimension().equals(view.dimension())
                    && payload.origin().equals(lastRequest.pos())&&payload.quarters()==lastRequest.rotation()/90;
            boolean changed=!payload.revision().equals(view.revision());
            boolean newBuilding=!payload.name().equals(view.name())||!payload.dimension().equals(view.dimension());
            if(changed&&!moved) { offset=BlockPos.ZERO;turns=0;confirm=false;cancel=false; }
            if(!payload.revision().equals(view.revision())) fullProjection=false;
            if(!payload.name().equals(view.name())||!payload.dimension().equals(view.dimension())) { offset=BlockPos.ZERO; turns=0; confirm=false; cancel=false; }
            view=payload;
            // Confirmation keeps the revision id, but is still an acknowledgement.
            waiting=false;
            if(payload.name().isEmpty()||payload.confirmed()) { positioning=false; offset=BlockPos.ZERO; turns=0; confirm=false; cancel=false; }
            else positioning=true;
            refreshPreview();
            if(newBuilding&&!payload.name().isEmpty()) hint();
        };
        ArchitectureThumbnailPayload.onClient=payload->{
            inspecting.remove(payload.view().name()); thumbnails.put(payload.view().name(),payload.view());
            while(thumbnails.size()>3) thumbnails.remove(thumbnails.keySet().iterator().next());
        };
        ArchitectureNetwork.Reply.onClient=reply->{
            var result=JSON.fromJson(reply.json(),ArchitectureNetwork.Results.class);
            if("catalog".equals(result.kind())||"recommend".equals(result.kind())) {
                matches=result.matches(); query=result.query(); recommended="recommend".equals(result.kind());
                thumbnails.clear(); failed.clear();
                if(Minecraft.getInstance().screen instanceof ArchitectureScreen screen) screen.refreshed();
            }
            message=result.message();
            if(message.startsWith("操作未执行")||message.startsWith("操作过快")) {
                if(lastRequest!=null&&lastRequest.action().equals("inspect")) { inspecting.remove(lastRequest.value()); failed.add(lastRequest.value()); }
                waiting=false; offset=BlockPos.ZERO; turns=0; confirm=false; cancel=false;
                refreshPreview();
            }
        };
    }
    public static ArchitectureViewPayload thumbnail(String id) { return thumbnails.get(id); }
    public static void inspect(String id) {
        if(!thumbnails.containsKey(id)&&!failed.contains(id)&&inspecting.add(id)) send("inspect",id,BlockPos.ZERO,0);
    }
    public static void open() {
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.getConnection()==null) return;
        if(!supported.getAsBoolean()) { mc.player.sendSystemMessage(Component.literal("此服务器未安装同版本建筑库扩展")); return; }
        mc.setScreen(new ArchitectureScreen());
        // Keep model recommendations visible; opening B must not replace them with a blank search.
        if(matches.isEmpty()&&!recommended) send("search",query,BlockPos.ZERO,0);
    }
    public static void send(String action,String value,BlockPos origin,int rotation) {
        if(!supported.getAsBoolean()||Minecraft.getInstance().getConnection()==null) return;
        if(action.equals("search")||action.equals("refresh")) query=value;
        if(action.equals("refresh")) { thumbnails.clear(); failed.clear(); inspecting.clear(); }
        if(requests.size()>=12) { message="请求排队过多，请等待当前预览完成"; return; }
        requests.add(new Request(action,value,origin,rotation));
    }
    private static void transmit(Request request) {
        lastRequest=request; nextSend=net.minecraft.Util.getMillis()+160;
        if(Set.of("preview","move","confirm","cancel").contains(request.action())) { waiting=true; waitSince=net.minecraft.Util.getMillis(); }
        Services.network().sendToServer(new ArchitectureNetwork.Request(request.action(),request.value(),request.pos(),request.rotation(),view.revision()));
    }
    public static void tick() {
        var mc=Minecraft.getInstance(); long now=net.minecraft.Util.getMillis();
        if(mc.player==null||!supported.getAsBoolean()) return;
        if(waiting&&now-waitSince>5000) {
            waiting=false; offset=BlockPos.ZERO; turns=0; confirm=false; cancel=false;
            message="投影定位未收到确认，请检查连接后重新调整；没有自动重发旧坐标。";
            refreshPreview();
        }
        if(now<nextSend) return;
        if(waiting) return;
        if(!requests.isEmpty()) { transmit(requests.remove()); return; }
        if(view.name().isEmpty()) return;
        Request action=null;
        if(cancel) { action=new Request("cancel","",view.origin(),0); cancel=false; }
        else if(!offset.equals(BlockPos.ZERO)||turns!=0) {
            action=new Request("move","",view.origin().offset(offset),Math.floorMod(view.quarters()+turns,4)*90);
            offset=BlockPos.ZERO; turns=0;
        } else if(confirm) { action=new Request("confirm","",view.origin(),view.quarters()*90); confirm=false; }
        if(action!=null) { waiting=true; waitSince=now; transmit(action); refreshPreview(); }
    }
    public static void shift(int forward,int right,int up) {
        var mc=Minecraft.getInstance();
        if(!positioning||mc.player==null) return;
        int stride=mc.player.isShiftKeyDown()?5:1;
        var delta=ArchitectureControls.step(mc.player.getDirection(),forward*stride,right*stride,up*stride);
        offset=new BlockPos(Math.clamp(offset.getX()+delta.getX(),-64,64),Math.clamp(offset.getY()+delta.getY(),-64,64),Math.clamp(offset.getZ()+delta.getZ(),-64,64));
        refreshPreview();
    }
    public static void rotate() {
        if(!positioning||view.name().isEmpty()) return;
        var preview=previewView();
        var next=ArchitectureControls.rotatedOrigin(preview.origin(),preview.quarters(),1,localCells());
        offset=offset.offset(next.subtract(preview.origin()));turns=(turns+1)%4;refreshPreview();
    }
    public static void toggleProjection() { if(!view.name().isEmpty()) fullProjection=!fullProjection; }
    public static void confirm() { if(positioning&&view.compatible()) confirm=true; else message="当前投影不兼容，不能确认施工"; }
    public static void cancel() { if(positioning) cancel=true; }
    public static void reposition() {
        if(view.name().isEmpty()) return;
        var mc=Minecraft.getInstance();
        if(!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)||hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK) {
            message="请先用准星瞄准建筑要落地的地面，再按 P。"; hint();return;
        }
        var preview=previewView();
        BlockPos origin=ArchitectureControls.anchor(hit.getBlockPos().relative(hit.getDirection()),preview.quarters(),localCells());
        offset=offset.offset(origin.subtract(preview.origin()));positioning=true;refreshPreview();hint();
    }
    private static List<BlockPos> localCells() { return view.cells().stream().map(ArchitectureViewPayload.Cell::relative).toList(); }
    private static ArchitectureViewPayload previewView() {
        boolean moving=waiting&&lastRequest!=null&&lastRequest.action().equals("move");
        BlockPos origin=(moving?lastRequest.pos():view.origin()).offset(offset);
        int rotation=Math.floorMod((moving?lastRequest.rotation()/90:view.quarters())+turns,4);
        return new ArchitectureViewPayload(view.revision(),view.name(),view.dimension(),origin,rotation,
                !positioning&&view.confirmed(),view.compatible(),view.credit(),view.cells());
    }
    private static void refreshPreview() { ArchitectureRenderer.update(previewView()); }
    private static void hint() {
        var player=Minecraft.getInstance().player;
        if(player!=null) player.displayClientMessage(Component.literal("投影：P 移到准星处 · 方向键微调 · Shift 加速 · PageUp/Down 高度 · R 居中旋转 · Enter 确认（不施工）"),true);
    }
    public static void clear() {
        view=ArchitectureViewPayload.empty(); matches=List.of(); requests.clear(); thumbnails.clear(); inspecting.clear(); failed.clear();
        offset=BlockPos.ZERO; turns=0; waiting=false; confirm=false; cancel=false; positioning=false; recommended=false; fullProjection=false; nextSend=0;
        message="投影已断开；请在当前服务器重新选择。"; ArchitectureRenderer.update(view);
    }
}
