package com.dwinovo.numen.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodecHelper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import static com.dwinovo.numen.e2e.FakeModel.lua;
import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Fabric 服务器验证目录、二进制投影、玩家确认与原生执行器,不碰玩家存档。 */
class ArchitectureTest extends Scenario {
    private static final MinecraftCodecHelper CODEC = new MinecraftCodecHelper();
    private static final UUID NONE = new UUID(0,0);
    private final java.util.Map<Bot,Integer> cooperationMarks=new java.util.IdentityHashMap<>();
    @Test void a_completed_cooperation_task_is_archived_without_an_llm_finish_and_does_not_control_later_work() throws Exception {
        console("forceload add 400 400 431 431");
        Bot owner=join("e2e_auto_finish","en_us"),other=join("e2e_tasks_other","en_us");
        register(owner); register(other);
        owner.payload("minecraft:register","numen:cooperation_state".getBytes(StandardCharsets.UTF_8));
        other.payload("minecraft:register","numen:cooperation_state".getBytes(StandardCharsets.UTF_8));
        request(owner,"refresh","cooperation_fixture",402,-60,402,0,NONE); response(owner,"已更新");
        request(owner,"preview","cooperation_fixture",402,-60,402,0,NONE);
        View draft=view(owner,"cooperation_fixture",NONE,false);
        request(owner,"confirm","",402,-60,402,0,draft.revision()); view(owner,"cooperation_fixture",NONE,true);
        summon(owner,"AutoFinisher"); console("tp AutoFinisher 400.5 -60 402.5");
        console("gamemode creative AutoFinisher");
        int setup=owner.mark();owner.command("numen permission mode AutoFinisher bypass");
        owner.await(setup,m->m.text().contains("bypass"),WAIT,"真实施工权限");
        var started=agentAction(owner,"AutoFinisher","track approved construction","numen.cooperate.begin()");
        String task=started.getAsJsonObject("plan").get("taskId").getAsString();
        assertTrue(started.getAsJsonObject("plan").get("active").getAsBoolean());
        assertEquals("minecraft:bricks",started.getAsJsonArray("kinds").get(0).getAsJsonObject().get("icon").getAsString());
        int requestsBefore=MODEL.asked().size();
        for(String action:java.util.List.of("begin","end","roles","scope","chest","protection")) {
            coop(owner,action,"false",0,0,0);
            assertTrue(coopResponse(owner,"未执行").get("message").getAsString().contains("只读"));
        }
        assertEquals(requestsBefore,MODEL.asked().size(),"只读浮窗的请求不能唤醒规划器或切换任务");
        int payloadMark=owner.payloadMark();
        // 真实原生施工留下放置者归属,全程没有 numen.cooperate.finish 调用。
        MODEL.whenHeard("build these approved cells",lua("numen.build.place({{name='oak_planks',pos={x=402,y=-60,z=402}},{name='oak_planks',pos={x=403,y=-60,z=402}},{name='oak_planks',pos={x=404,y=-60,z=402}},{name='stone',pos={x=402,y=-59,z=402}}}); print('AUTO_FINISH_REAL_BUILD')"),say("Native construction done."));
        int built=owner.mark();owner.command("msg AutoFinisher build these approved cells");
        owner.await(built,m->m.text().contains("Native construction done."),WAIT,"原生施工完成");
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("AUTO_FINISH_REAL_BUILD")),
                ()->"原生施工必须成功："+MODEL.asked().getLast().toolResults());
        var closed=json(owner.awaitPayload(payloadMark,p->p.channel().equals("numen:cooperation_state")&&json(p).has("plan")
                &&json(p).getAsJsonObject("plan").get("phase").getAsString().equals("completed"),WAIT,"方块完成自动收尾"));
        assertFalse(closed.getAsJsonObject("plan").get("active").getAsBoolean());
        assertTrue(closed.getAsJsonObject("plan").get("complete").getAsBoolean());
        assertTrue(closed.getAsJsonObject("plan").get("protectedBuilding").getAsBoolean());
        assertEquals(task,closed.getAsJsonArray("tasks").get(0).getAsJsonObject().get("id").getAsString());
        coop(other,"status","",0,0,0); assertEquals(0,coopResponse(other,"材料每秒同步").getAsJsonArray("tasks").size());
        MODEL.whenHeard("inspect completed plan and move on",lua("local s=numen.cooperate.status(); assert(not s.active and s.complete); local ok=pcall(function() numen.cooperate.place() end); assert(not ok); numen.move.to({x=400,y=-60,z=402},1); print('CLOSED_PLAN_NO_LONGER_BUILDS')"),say("The building is done; I can do other work."));
        int mark=owner.mark(); owner.command("msg AutoFinisher inspect completed plan and move on");
        owner.await(mark,m->m.text().contains("The building is done; I can do other work."),WAIT,"已完成建筑不阻止其他任务");
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("CLOSED_PLAN_NO_LONGER_BUILDS")));
        assertFalse(MODEL.asked().getLast().body().contains("<cooperative_build_data>"),"结束的工地不继续注入其他任务上下文");
        // 任务关闭后仍经真正的挖掘准备 / 手部权限拒绝,宽松模式也不能把房子当建材。
        mark=owner.mark();owner.command("numen permission mode AutoFinisher bypass");
        owner.await(mark,m->m.text().contains("bypass"),WAIT,"建筑保护测试的宽松模式");
        console("tp AutoFinisher 400.5 -60 402.5");console("give AutoFinisher minecraft:diamond_axe");
        MODEL.whenHeard("try to gather protected building wood",lua(
                "local ok,why=pcall(function() numen.work.dig({x=402,y=-60,z=402}) end); "
                +"assert(not ok,tostring(why)); assert(tostring(why):find('protected'),tostring(why)); print('FINISHED_BUILDING_PROTECTED')"),say("Finished building wood is protected."));
        mark=owner.mark();owner.command("msg AutoFinisher try to gather protected building wood");
        owner.await(mark,m->m.text().contains("Finished building wood is protected."),WAIT,"完成建筑不能采集");
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("FINISHED_BUILDING_PROTECTED")));
        assertTrue(console("execute if block 402 -60 402 minecraft:oak_planks").contains("Test passed"));
        coop(other,"protection","false",0,0,0);assertTrue(coopResponse(other,"未执行").get("message").getAsString().contains("只读"));
        MODEL.whenHeard("ask to release this building protection",lua("local d=numen.architecture.draft(); numen.architecture.request_unprotect(d.proposal.revision); print('OWNER_CONFIRMED_PROTECTION_RELEASE')"),say("Protection released after your confirmation."));
        mark=owner.mark();owner.command("msg AutoFinisher ask to release this building protection");
        var permission=owner.await(mark,m->!Text.clickCommands(m.content()).isEmpty(),WAIT,"解除保护的真实确认卡");
        var allow=Text.clickCommands(permission.content()).stream().filter(s->s.startsWith("/numen consent allow ")).findFirst().orElseThrow();
        other.click(allow);
        assertTrue(console("execute if block 402 -60 402 minecraft:oak_planks").contains("Test passed"));
        owner.click(allow);
        owner.await(mark,m->m.text().contains("Protection released after your confirmation."),WAIT,"玩家确认解除保护");
        MODEL.whenHeard("owner released the building for renovation",lua(
                "local r=numen.work.dig({x=402,y=-60,z=402}); assert(r.dug==1); print('OWNER_RELEASE_RENOVATION_WORKS')"),say("Explicitly released block removed."));
        mark=owner.mark();owner.command("msg AutoFinisher owner released the building for renovation");
        owner.await(mark,m->m.text().contains("Explicitly released block removed."),WAIT,"主人解除后允许改建");
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("OWNER_RELEASE_RENOVATION_WORKS")),
                ()->"改建必须有实际执行回执："+MODEL.asked().getLast().toolResults());
        assertWorldAir(402,-60,402);
        // 完成记录保留,新任务有独立标识。
        request(owner,"preview","cooperation_fixture",410,-60,410,0,NONE);var next=view(owner,"cooperation_fixture",draft.revision(),false);
        request(owner,"confirm","",410,-60,410,0,next.revision());view(owner,"cooperation_fixture",draft.revision(),true);
        var restarted=agentAction(owner,"AutoFinisher","track the new construction","numen.cooperate.begin()");
        assertTrue(restarted.getAsJsonObject("plan").get("active").getAsBoolean());
        assertNotEquals(task,restarted.getAsJsonObject("plan").get("taskId").getAsString());
        assertEquals(2,restarted.getAsJsonArray("tasks").size());
    }
    @Test void local_material_survey_reports_real_stock_and_visible_nature_without_hidden_ore() throws Exception {
        console("forceload add 800 800 831 831");
        Bot owner=join("e2e_materials","en_us");summon(owner,"LocalDesigner");
        console("tp e2e_materials 800.5 -60 800.5");console("tp LocalDesigner 800.5 -60 801.5");
        console("give LocalDesigner minecraft:birch_log 3");console("give e2e_materials minecraft:oak_planks 7");
        console("setblock 804 -60 801 minecraft:birch_log");console("setblock 804 -59 801 minecraft:birch_log");
        console("setblock 804 -58 801 minecraft:birch_leaves[persistent=false]");
        console("setblock 802 -60 803 minecraft:stone");
        console("fill 806 -61 806 808 -58 808 minecraft:barrier");console("setblock 807 -60 807 minecraft:diamond_ore");
        MODEL.whenHeard("survey local materials before designing",lua(
                "local m=numen.architecture.materials({radius=12}); assert(m.player_stock_known); "
                +"assert(m.companion_stock['minecraft:birch_log']==3); assert(m.player_stock['minecraft:oak_planks']==7); "
                +"local wood=false; for _,r in ipairs(m.resources) do assert(r.block~='minecraft:diamond_ore'); "
                +"if r.block=='minecraft:birch_log' and r.visible>0 then wood=true end end; assert(wood,'visible local birch must be reported'); "
                +"assert(not m.companion_stock['minecraft:birch_planks']); print('LOCAL_STOCK_AND_VISIBLE_NATURE_VERIFIED')"),say("Local birch can guide the design; it is not already crafted stock."));
        int mark=owner.mark();owner.command("msg LocalDesigner survey local materials before designing");
        owner.await(mark,m->m.text().contains("Local birch can guide the design"),WAIT,"本地材料经真实 API 返回");
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("LOCAL_STOCK_AND_VISIBLE_NATURE_VERIFIED")));
        assertTrue(console("execute if block 804 -60 801 minecraft:birch_log").contains("Test passed"),"材料调查不自动砍树");
        assertTrue(console("execute if block 807 -60 807 minecraft:diamond_ore").contains("Test passed"));
    }
    @Test void three_real_model_recommendations_and_prototypes_do_not_replace_an_approved_world_draft() throws Exception {
        console("forceload add 352 352 383 383");
        Bot owner=join("e2e_chooser","en_us"); register(owner);
        owner.payload("minecraft:register","numen:architecture_thumbnail".getBytes(StandardCharsets.UTF_8));
        request(owner,"refresh","住宅",360,-60,360,0,NONE); response(owner,"已更新");
        request(owner,"preview","catalog_fixture",360,-60,360,0,NONE);
        var original=view(owner,"catalog_fixture",NONE,false);
        request(owner,"confirm","",360,-60,360,0,original.revision()); view(owner,"catalog_fixture",NONE,true);
        summon(owner,"ChooserFriend");
        MODEL.whenHeard("recommend three real house templates",lua("local xs=numen.architecture.recommend('住宅木屋'); assert(#xs==3); print('THREE_REAL_CANDIDATES')"),say("Choose one in the B building screen."));
        int mark=owner.mark(); owner.command("msg ChooserFriend recommend three real house templates");
        owner.await(mark,m->m.text().contains("Choose one in the B building screen."),WAIT,"原生推荐接口");
        var choices=json(owner.awaitPayload(p->p.channel().equals("numen:architecture_catalog")&&json(p).get("kind").getAsString().equals("recommend"),WAIT,"发送给真实玩家的三个候选"));
        assertEquals(3,choices.getAsJsonArray("matches").size());
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("THREE_REAL_CANDIDATES")));
        String id=choices.getAsJsonArray("matches").get(0).getAsJsonObject().getAsJsonObject("building").get("id").getAsString();
        request(owner,"inspect",id,0,0,0,0,NONE);
        var prototype=decode(owner.awaitPayload(p->p.channel().equals("numen:architecture_thumbnail")&&decode(p).name().equals(id),WAIT,"独立原型数据"));
        assertEquals(NONE,prototype.revision()); assertFalse(prototype.confirmed()); assertTrue(prototype.cells()>0);
        assertWorldAir(360,-60,360);
        MODEL.whenHeard("verify chooser kept my approved draft",lua("local p=numen.architecture.approved(); assert(p.design.blueprint=='catalog_fixture'); assert(p.design.origin.x==360); assert(p.design.origin.z==360); print('WORLD_DRAFT_UNCHANGED')"),say("Your approved draft is unchanged."));
        mark=owner.mark(); owner.command("msg ChooserFriend verify chooser kept my approved draft");
        owner.await(mark,m->m.text().contains("Your approved draft is unchanged."),WAIT,"检查确认未被推荐或原型覆盖");
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("WORLD_DRAFT_UNCHANGED")));
        request(owner,"preview",id,370,-60,370,0,NONE);
        var chosen=view(owner,id,original.revision(),false);
        assertEquals(pack(370,-60,370),chosen.origin()); assertWorldAir(370,-60,370);
    }

    @Test void cooperative_roles_real_stock_foundation_scope_and_consented_gestures() throws Exception {
        console("forceload add 288 288 319 319");
        Bot owner=join("e2e_coop_owner","en_us"),other=join("e2e_coop_other","en_us");
        register(owner); register(other);
        owner.payload("minecraft:register","numen:cooperation_state".getBytes(StandardCharsets.UTF_8));
        other.payload("minecraft:register","numen:cooperation_state".getBytes(StandardCharsets.UTF_8));
        request(owner,"refresh","cooperation_fixture",300,-60,300,0,NONE); response(owner,"已更新");
        request(owner,"preview","cooperation_fixture",300,-60,300,0,NONE);
        View draft=view(owner,"cooperation_fixture",NONE,false);
        request(owner,"confirm","",300,-60,300,0,draft.revision()); view(owner,"cooperation_fixture",NONE,true);
        summon(owner,"CoopMason");
        console("tp CoopMason 297.5 -60 300.5"); console("tp e2e_coop_owner 292.5 -60 300.5");
        var started=agentAction(owner,"CoopMason","track foundation from our conversation","numen.cooperate.begin({layers=1})").getAsJsonObject("plan");
        assertEquals("foundation:1",started.get("scope").getAsString());
        UUID npc=UUID.fromString(started.get("companionId").getAsString());
        owner.payload("minecraft:register","numen:appearance_reply".getBytes(StandardCharsets.UTF_8));
        other.payload("minecraft:register","numen:appearance_reply".getBytes(StandardCharsets.UTF_8));
        // 专用测试服没有 YSM:本人得到明确的未安装答复;别人不能借 UI 端点换装。
        assertEquals("no_function",appearance(owner,1,npc,"options").getAsJsonObject("error").get("kind").getAsString());
        assertEquals("denied",appearance(other,2,npc,"apply").getAsJsonObject("error").get("kind").getAsString());
        UUID ownerId=UUID.nameUUIDFromBytes(("OfflinePlayer:"+owner.name()).getBytes(StandardCharsets.UTF_8));
        assertEquals("denied",appearance(owner,3,ownerId,"apply").getAsJsonObject("error").get("kind").getAsString());
        assertEquals("collector",started.getAsJsonObject("playerRole").get("role").getAsString());
        assertEquals("collector",started.getAsJsonObject("companionRole").get("role").getAsString());

        int mark=owner.mark(); owner.command("numen permission mode CoopMason bypass");
        owner.await(mark,m->m.text().contains("bypass"),WAIT,"测试施工权限");
        console("give CoopMason minecraft:oak_planks 12");
        MODEL.whenHeard("prepare a real shared chest",lua("numen.inv.make('minecraft:crafting_table',1); numen.inv.make('minecraft:chest',1); numen.build.place({{name='chest',pos={x=294,y=-60,z=301}}}); assert(numen.inv.count('minecraft:chest')==0); print('REAL_CHEST_CREATED')"),say("The real shared chest is ready."));
        mark=owner.mark(); owner.command("msg CoopMason prepare a real shared chest");
        owner.await(mark,m->m.text().contains("The real shared chest is ready."),WAIT,"原生合成并放置材料箱");
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("REAL_CHEST_CREATED")),
                ()->"准备材料箱实际回执："+MODEL.asked().stream().flatMap(a->a.toolResults().stream()).toList());
        assertTrue(console("execute if block 294 -60 301 minecraft:chest").contains("Test passed"));
        console("item replace block 294 -60 301 container.0 with minecraft:oak_planks 2");
        console("give e2e_coop_owner minecraft:oak_planks 1");
        agentAction(owner,"CoopMason","use this real shared chest","numen.cooperate.bind_chest({x=294,y=-60,z=301})");
        // Refresh is a real server tick observation, not a virtual supply ledger.
        var stocked=json(owner.awaitPayload(p->p.channel().equals("numen:cooperation_state")&&hasStock(json(p),"minecraft:oak_planks",1,2),WAIT,"真实背包和箱子分列库存"));
        assertTrue(hasStock(stocked,"minecraft:oak_planks",1,2));
        agentAction(owner,"CoopMason","record intentions without controlling the executor","numen.cooperate.assign('collector','idle')");
        coop(other,"roles","builder,idle",0,0,0);
        assertTrue(coopResponse(other,"未执行").get("message").getAsString().contains("只读"));
        console("item replace block 294 -60 301 container.0 with minecraft:oak_planks 3");
        console("gamemode survival CoopMason");
        MODEL.whenHeard("cooperative foundation pass",lua("local s=numen.cooperate.status(); assert(s.player_mode=='auto'); assert(s.companion_mode=='auto'); assert(s.companion_role.role=='idle'); assert(numen.inv.fetch({x=294,y=-60,z=301},'minecraft:oak_planks',3)==3); numen.cooperate.place({count=2,layers=1}); numen.cooperate.place({count=2,layers=1}); assert(numen.inv.count('minecraft:oak_planks')==0); local v=numen.cooperate.finish(); assert(not v.complete); assert(v.wrong==1); print('COOP_FOUNDATION_VERIFIED')"),say("Foundation done, upper wall deliberately not built."));
        mark=owner.mark(); owner.command("msg CoopMason cooperative foundation pass");
        owner.await(mark,m->m.text().contains("Foundation done, upper wall deliberately not built."),WAIT,"真实小批地基施工");
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains("COOP_FOUNDATION_VERIFIED")),
                ()->"实际 Lua 回执："+MODEL.asked().stream().flatMap(a->a.toolResults().stream()).toList());
        for(int x=300;x<=302;x++) assertTrue(console("execute if block "+x+" -60 300 minecraft:oak_planks").contains("Test passed"));
        assertWorldAir(300,-59,300);
        assertTrue(console("data get block 294 -60 301 Items").contains("[]"),"生存施工实际取空材料箱，非虚拟库存");

        console("tp CoopMason 296.5 -60 301.5");
        console("tp e2e_coop_owner 297.5 -60 301.5");
        String bodyHealth=console("data get entity CoopMason Health"),ownerHealth=console("data get entity e2e_coop_owner Health");
        agentAction(owner,"CoopMason","offer a consensual high five","numen.social.offer('high_five','一起庆祝一下？')");
        var invitation=coopInvitation(owner);
        String token=invitation.get("token").getAsString();
        coop(other,"accept",token,0,0,0); coopResponse(other,"未执行");
        assertEquals(bodyHealth,console("data get entity CoopMason Health"));
        coop(owner,"accept",token,0,0,0);
        var result=coopResponse(owner,"无伤害");
        assertTrue(result.get("message").getAsString().contains("手势"));
        assertEquals(ownerHealth,console("data get entity e2e_coop_owner Health"));
        assertEquals(bodyHealth,console("data get entity CoopMason Health"));
        assertTrue(MODEL.asked().stream().anyMatch(a->a.body().contains("construction_cooperation")&&a.body().contains("social_interaction")));
        coop(other,"end","",0,0,0); coopResponse(other,"未执行");
        coop(owner,"status","",0,0,0);
        assertTrue(coopResponse(owner,"材料每秒同步").has("plan"),"别人的结束按钮不能删除本人的计划");
        assertFalse(agentAction(owner,"CoopMason","end progress tracking through our conversation","numen.cooperate.close()").has("plan"));
        assertTrue(console("execute if block 300 -60 300 minecraft:oak_planks").contains("Test passed"),"结束协作只删统计，不拆建筑");
    }
    private static JsonObject appearance(Bot bot,long serial,UUID npc,String action) throws Exception {
        Thread.sleep(150);
        bot.payload("numen:appearance_request",encode(b->{
            b.writeLong(serial); b.writeLong(npc.getMostSignificantBits()); b.writeLong(npc.getLeastSignificantBits());
            CODEC.writeString(b,action); CODEC.writeString(b,""); CODEC.writeString(b,"");
        }));
        var payload=bot.awaitPayload(p->p.channel().equals("numen:appearance_reply")&&appearanceMatches(p,serial,npc,action),WAIT,"NPC 外观界面权属校验");
        ByteBuf b=Unpooled.wrappedBuffer(payload.data());
        try {
            assertEquals(serial,b.readLong()); assertEquals(npc,new UUID(b.readLong(),b.readLong()));
            assertEquals(action,CODEC.readString(b)); var result=JsonParser.parseString(CODEC.readString(b)).getAsJsonObject();
            assertEquals(0,b.readableBytes()); return result;
        } finally { b.release(); }
    }
    private static boolean appearanceMatches(Bot.Payload payload,long serial,UUID npc,String action) {
        ByteBuf b=Unpooled.wrappedBuffer(payload.data());
        try { return b.readLong()==serial&&new UUID(b.readLong(),b.readLong()).equals(npc)&&CODEC.readString(b).equals(action); }
        finally { b.release(); }
    }
    private static boolean hasStock(JsonObject view,String item,int player,int chest) {
        if(!view.has("plan")) return false;
        for(var element:view.getAsJsonObject("plan").getAsJsonArray("materials")) {
            var row=element.getAsJsonObject();
            if(row.get("item").getAsString().equals(item)) return row.get("player").getAsInt()==player&&row.get("sharedChest").getAsInt()==chest&&row.get("missing").getAsInt()==0;
        }
        return false;
    }
    @Test void accepted_jump_uses_native_physics() throws Exception { verifyGesture("jump","完成一次跳跃"); }
    @Test void accepted_spin_verifies_a_real_full_turn() throws Exception { verifyGesture("spin","转了一圈"); }
    private void verifyGesture(String kind,String expected) throws Exception {
        Bot owner=join("e2e_"+kind+"_owner","en_us");
        owner.payload("minecraft:register","numen:cooperation_state".getBytes(StandardCharsets.UTF_8));
        String name=kind.equals("jump")?"JumpFriend":"SpinFriend"; summon(owner,name);
        console("tp "+name+" 10.5 -60 10.5"); console("tp "+owner.name()+" 11.5 -60 10.5");
        String health=console("data get entity "+owner.name()+" Health");
        agentAction(owner,name,"offer "+kind+" gesture","numen.social.offer('"+kind+"','一起庆祝一下？')"); var invitation=coopInvitation(owner);
        coop(owner,"accept",invitation.get("token").getAsString(),0,0,0);
        assertTrue(coopResponse(owner,expected).get("message").getAsString().contains(expected));
        assertEquals(health,console("data get entity "+owner.name()+" Health"));
    }
    private void coop(Bot bot,String action,String value,int x,int y,int z) throws Exception {
        Thread.sleep(150);
        cooperationMarks.put(bot,bot.payloadMark());
        bot.payload("numen:cooperation_request",encode(b->{CODEC.writeString(b,action); CODEC.writeString(b,value); b.writeLong(pack(x,y,z));}));
    }
    private JsonObject agentAction(Bot bot,String name,String request,String code) throws Exception {
        MODEL.whenHeard(request,lua(code),say("Agent handled: "+request));
        int mark=bot.mark();bot.command("msg "+name+" "+request);
        bot.await(mark,m->m.text().contains("Agent handled: "+request),WAIT,"由原生 Agent 处理对话请求");
        coop(bot,"status","",0,0,0);
        return coopResponse(bot,"材料每秒同步");
    }
    private JsonObject coopResponse(Bot bot,String contains) {
        return json(bot.awaitPayload(cooperationMarks.getOrDefault(bot,0),p->p.channel().equals("numen:cooperation_state")&&json(p).get("message").getAsString().contains(contains),WAIT,"协作答复 "+contains));
    }
    private JsonObject coopInvitation(Bot bot) {
        return json(bot.awaitPayload(cooperationMarks.getOrDefault(bot,0),p->p.channel().equals("numen:cooperation_state")&&json(p).has("invitation"),WAIT,"实际互动邀请")).getAsJsonObject("invitation");
    }

    @Test void catalog_projection_must_be_owner_confirmed_and_rotation_matches_real_construction() throws Exception {
        console("forceload add 192 192 223 223");
        Bot owner = join("e2e_architect", "en_us");
        Bot stranger = join("e2e_arch_other", "en_us");
        register(owner); register(stranger);
        request(owner, "refresh", "乡村住宅", 200,-60,200,0,NONE);
        JsonObject catalog = response(owner, "已更新");
        var candidates = catalog.getAsJsonArray("matches");
        assertFalse(candidates.isEmpty());
        JsonObject building = candidates.get(0).getAsJsonObject().getAsJsonObject("building");
        assertEquals("catalog_fixture", building.get("id").getAsString());
        assertEquals("SceneAdapt test fixture", building.get("author").getAsString());
        assertEquals(2, building.getAsJsonObject("materials").get("minecraft:oak_slab").getAsInt());

        request(owner,"preview","catalog_fixture",200,-60,200,0,NONE);
        View initial = view(owner, "catalog_fixture", NONE, false);
        assertEquals(3, initial.cells()); assertTrue(initial.credit().contains("SceneAdapt test fixture"));
        assertTrue(initial.credit().contains("https://example.invalid/sceneadapt-fixture"));
        assertWorldAir(200,-60,200);

        request(stranger,"confirm","",200,-60,200,0,initial.revision());
        assertTrue(response(stranger,"操作未执行").get("message").getAsString().contains("投影已更新"));
        assertWorldAir(200,-60,200);

        summon(owner,"Mason");
        console("gamemode creative Mason"); console("tp Mason 198.5 -60 200.5");
        int mark = owner.mark(); owner.command("numen permission mode Mason bypass");
        owner.await(mark, m -> m.text().contains("bypass"), WAIT, "测试同伴权限模式");
        MODEL.whenHeard("try building before placement confirmation", lua(
                "numen.build.place(numen.build.blueprint(\"catalog_fixture\", {x=200,y=-60,z=200}))"), say("Unconfirmed placement was refused."));
        mark = owner.mark(); owner.command("msg Mason try building before placement confirmation");
        owner.await(mark,m -> m.text().contains("Unconfirmed placement was refused."),WAIT,"原生入口不能绕过投影确认");
        assertTrue(MODEL.asked().stream().anyMatch(a -> a.body().contains("须先由玩家")));
        assertWorldAir(200,-60,200);

        request(owner,"move","",210,-60,210,90,initial.revision());
        View rotated = view(owner,"catalog_fixture",initial.revision(),false);
        assertEquals(pack(210,-60,210),rotated.origin()); assertEquals(1,rotated.quarters());
        assertFalse(rotated.confirmed()); assertWorldAir(210,-60,210);
        request(owner,"confirm","",210,-60,210,90,rotated.revision());
        View confirmed = view(owner,"catalog_fixture",NONE,true);
        assertEquals(rotated.revision(),confirmed.revision()); assertTrue(confirmed.confirmed());
        assertWorldAir(210,-60,210);

        console("tp Mason 208.5 -60 210.5");
        MODEL.whenHeard("build the approved catalog plan", lua(
                "local plan = numen.architecture.approved(); assert(plan.design.rotation == 90); "
                        + "assert(plan.building.author == \"SceneAdapt test fixture\"); "
                        + "local placed = numen.build.place(plan.design); print(\"CATALOG_NATIVE_PLACED\")"),
                say("Approved catalog construction completed."));
        mark = owner.mark(); owner.command("msg Mason build the approved catalog plan");
        owner.await(mark,m -> m.text().contains("Approved catalog construction completed."),WAIT,"确认后原生建造");
        assertTrue(console("execute if block 210 -60 210 minecraft:oak_planks").contains("Test passed"));
        assertTrue(console("execute if block 210 -60 211 minecraft:oak_stairs[facing=east]").contains("Test passed"));
        assertTrue(console("execute if block 210 -60 212 minecraft:oak_slab[type=double]").contains("Test passed"));
        assertWorldAir(200,-60,200);
        assertTrue(MODEL.asked().stream().anyMatch(a -> a.body().contains("architecture_catalog")));
    }

    @Test void an_incompatible_model_can_be_inspected_but_never_confirmed() throws Exception {
        console("forceload add 240 240");
        Bot owner = join("e2e_bad_model", "en_us"); register(owner);
        request(owner,"refresh","catalog_incompatible",240,-60,240,0,NONE);
        response(owner,"已更新");
        request(owner,"preview","catalog_incompatible",240,-60,240,0,NONE);
        View draft = view(owner,"catalog_incompatible",NONE,false);
        assertFalse(draft.compatible()); assertEquals(1,draft.cells());
        request(owner,"confirm","",240,-60,240,0,draft.revision());
        assertTrue(response(owner,"操作未执行").get("message").getAsString().contains("supplementaries:book_pile"));
        assertWorldAir(240,-60,240);
    }
    private static void assertWorldAir(int x,int y,int z) throws Exception {
        String result = console("execute if block " + x + " " + y + " " + z + " minecraft:air");
        assertTrue(result.contains("Test passed"), "world condition at " + x + "," + y + "," + z + ": " + result);
    }
    private static void register(Bot bot) {
        bot.payload("minecraft:register", String.join("\0","numen:architecture_catalog","numen:architecture_view")
                .getBytes(StandardCharsets.UTF_8));
    }
    private static void request(Bot bot,String action,String value,int x,int y,int z,int rotation,UUID revision) throws Exception {
        // 与真实玩家点击间隔一样,不以网络洪泛绕过生产协议的速率限制。
        Thread.sleep(150);
        bot.payload("numen:architecture_request",encode(b -> {
            CODEC.writeString(b,action); CODEC.writeString(b,value); b.writeLong(pack(x,y,z));
            CODEC.writeVarInt(b,rotation); b.writeLong(revision.getMostSignificantBits()); b.writeLong(revision.getLeastSignificantBits());
        }));
    }
    private static JsonObject response(Bot bot,String contains) {
        return json(bot.awaitPayload(p -> p.channel().equals("numen:architecture_catalog")
                && json(p).get("message").getAsString().contains(contains),WAIT,"建筑库答复 " + contains));
    }
    private static JsonObject json(Bot.Payload payload) {
        ByteBuf b=Unpooled.wrappedBuffer(payload.data());
        try { return JsonParser.parseString(CODEC.readString(b)).getAsJsonObject(); } finally { b.release(); }
    }
    private record View(UUID revision,String name,long origin,int quarters,boolean confirmed,boolean compatible,String credit,int cells) {}
    private static View view(Bot bot,String name,UUID previous,boolean confirmed) {
        return decode(bot.awaitPayload(p -> p.channel().equals("numen:architecture_view") && decode(p).name().equals(name)
                && !decode(p).revision().equals(previous) && decode(p).confirmed()==confirmed,WAIT,"建筑投影修订"));
    }
    private static View decode(Bot.Payload payload) {
        ByteBuf b=Unpooled.wrappedBuffer(payload.data());
        try {
            UUID revision=new UUID(b.readLong(),b.readLong()); String name=CODEC.readString(b); CODEC.readString(b);
            long origin=b.readLong(); int quarters=CODEC.readVarInt(b); boolean confirmed=b.readBoolean(),compatible=b.readBoolean();
            String credit=CODEC.readString(b); int cells=CODEC.readVarInt(b);
            for(int i=0;i<cells;i++) { b.readLong(); CODEC.readVarInt(b); b.readBoolean(); }
            assertEquals(0,b.readableBytes());
            return new View(revision,name,origin,quarters,confirmed,compatible,credit,cells);
        } finally { b.release(); }
    }
    private static long pack(int x,int y,int z) { return ((long)x&0x3FFFFFFL)<<38 | ((long)z&0x3FFFFFFL)<<12 | y&0xFFFL; }
    private static byte[] encode(Consumer<ByteBuf> writer) {
        ByteBuf b=Unpooled.buffer();
        try { writer.accept(b); byte[] data=new byte[b.readableBytes()]; b.readBytes(data); return data; }
        finally { b.release(); }
    }
}
