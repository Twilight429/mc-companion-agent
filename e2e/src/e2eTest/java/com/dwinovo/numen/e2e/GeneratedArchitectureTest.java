package com.dwinovo.numen.e2e;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodecHelper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static com.dwinovo.numen.e2e.FakeModel.lua;
import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.*;

/** Original geometry goes through the real Lua API, world ghost packets, owner confirmation and native cooperation. */
class GeneratedArchitectureTest extends Scenario {
    private static final MinecraftCodecHelper CODEC=new MinecraftCodecHelper();
    private record View(UUID revision,String name,long origin,int quarters,boolean confirmed,int cells) {}
    @Test void original_design_can_be_revised_after_player_positioning_then_cooperatively_built() throws Exception {
        console("forceload add 480 480 543 543");
        Bot owner=join("e2e_designer","en_us"),other=join("e2e_design_other","en_us");
        register(owner);register(other);summon(owner,"Designer");summon(other,"OtherDesigner");
        console("tp Designer 499.5 -60 500.5");console("tp e2e_designer 480.5 -60 500.5");
        MODEL.whenHeard("design an original medieval cottage",lua(
                "local s=numen.shape; local c=s.box({x=0,y=0,z=0},{x=4,y=0,z=4},'oak_planks'); "
                +"c=c:union(s.box({x=0,y=1,z=0},{x=4,y=3,z=4},'oak_planks',true)); "
                +"c=c:minus({{x=2,y=1,z=0},{x=2,y=2,z=0}}); "
                +"for k=0,2 do c=c:union(s.box({x=k,y=4+k,z=0},{x=4-k,y=4+k,z=4},'spruce_planks')) end; "
                +"local p=numen.architecture.propose('Shared cottage','medieval_rustic','A small cottage for us, with a doorway and gabled roof',c,{x=500,y=-60,z=500}); "
                +"assert(not p.confirmed and p.building.compatible); print('ORIGINAL_GHOST_SAVED')"),say("Original cottage projected, no construction."));
        speak(owner,"Designer","design an original medieval cottage","Original cottage projected, no construction.");
        assertReceipt("ORIGINAL_GHOST_SAVED");
        var initial=decode(owner.awaitPayload(p->p.channel().equals("numen:architecture_view"),WAIT,"原创世界投影"));
        assertFalse(initial.confirmed());assertTrue(initial.cells()>50);air(500,-60,500);
        assertFalse(other.hasPayload(p->p.channel().equals("numen:architecture_view")),"另一个玩家不能收到本人的草稿");

        int pm=owner.payloadMark();request(owner,"move",520,-60,520,90,initial.revision());
        var moved=view(owner,pm,false);assertEquals(1,moved.quarters());assertEquals(pack(520,-60,520),moved.origin());
        pm=owner.payloadMark();request(owner,"confirm",520,-60,520,90,moved.revision());
        assertTrue(view(owner,pm,true).confirmed());
        MODEL.whenHeard("test stale design edit",lua("local ok=pcall(function() numen.architecture.revise('"+initial.revision()+"',{changes={{name='glass',pos={x=1,y=2,z=0}}}}) end); assert(not ok); print('STALE_DESIGN_REFUSED')"),say("Stale change refused."));
        speak(owner,"Designer","test stale design edit","Stale change refused.");assertReceipt("STALE_DESIGN_REFUSED");

        pm=owner.payloadMark();MODEL.whenHeard("add a front window to our design",lua(
                "numen.cooperate.begin({layers=1}); assert(numen.cooperate.status().active); "
                +
                "local d=numen.architecture.draft({limit=512}); assert(d.present and d.editable and d.style=='medieval_rustic'); "
                +"assert(d.proposal.confirmed and d.proposal.origin.x==520 and d.proposal.rotation==90); "
                +"local p=numen.architecture.revise(d.proposal.revision,{changes={{name='glass',pos={x=1,y=2,z=0}}},request='Add a front window and keep our moved position'}); "
                +"assert(not p.confirmed and p.rotation==90 and p.origin.x==520 and p.building.blocks['minecraft:glass']==1); "
                +"assert(not numen.architecture.details('"+initial.name()+"').blocks['minecraft:glass']); "
                +"local ok=pcall(function() numen.architecture.approved() end); assert(not ok); print('REVISED_GHOST_NEEDS_CONFIRMATION')"),say("Window added; please confirm the new ghost."));
        speak(owner,"Designer","add a front window to our design","Window added; please confirm the new ghost.");
        assertReceipt("REVISED_GHOST_NEEDS_CONFIRMATION");var revised=view(owner,pm,false);
        assertNotEquals(initial.name(),revised.name());assertEquals(moved.origin(),revised.origin());air(520,-60,520);
        MODEL.whenHeard("edit someone else's design",lua("local ok=pcall(function() numen.architecture.revise('"+revised.revision()+"',{changes={{name='stone',pos={x=0,y=0,z=0}}}}) end); assert(not ok); print('FOREIGN_DESIGN_REFUSED')"),say("Cannot edit another owner's design."));
        speak(other,"OtherDesigner","edit someone else's design","Cannot edit another owner's design.");assertReceipt("FOREIGN_DESIGN_REFUSED");

        pm=owner.payloadMark();request(owner,"confirm",520,-60,520,90,revised.revision());
        assertTrue(view(owner,pm,true).confirmed());
        int mark=owner.mark();owner.command("numen permission mode Designer bypass");owner.await(mark,m->m.text().contains("bypass"),WAIT,"施工测试权限");
        console("tp Designer 518.5 -60 520.5");console("give Designer minecraft:oak_planks 64");
        MODEL.whenHeard("I collect, build only the foundation",lua(
                "local p=numen.architecture.approved(); assert(p.building.title=='Shared cottage' and p.design.rotation==90); "
                +"numen.cooperate.begin({layers=1}); numen.cooperate.assign('collector','builder'); "
                +"numen.cooperate.close(); numen.cooperate.place({count=64,layers=1}); print('GENERATED_FOUNDATION_BUILT_WITHOUT_TASK_MODE')"),say("Foundation built from our approved original design."));
        speak(owner,"Designer","I collect, build only the foundation","Foundation built from our approved original design.");assertReceipt("GENERATED_FOUNDATION_BUILT_WITHOUT_TASK_MODE");
        for(int x=516;x<=520;x++) for(int z=520;z<=524;z++)
            assertTrue(console("execute if block "+x+" -60 "+z+" minecraft:oak_planks").contains("Test passed"));
        air(520,-59,520);
        assertTrue(MODEL.asked().stream().anyMatch(a->a.body().contains("architecture_draft")&&a.body().contains("building_design")));
    }
    private void speak(Bot owner,String npc,String message,String expected) {
        int mark=owner.mark();owner.command("msg "+npc+" "+message);
        owner.await(mark,m->m.text().contains(expected),WAIT,expected);
    }
    private void assertReceipt(String marker) {
        assertTrue(MODEL.asked().stream().flatMap(a->a.toolResults().stream()).anyMatch(t->t.contains(marker)),
                ()->"Missing actual native result "+marker+": "+MODEL.asked().stream().flatMap(a->a.toolResults().stream()).toList());
    }
    private void air(int x,int y,int z) throws Exception { assertTrue(console("execute if block "+x+" "+y+" "+z+" minecraft:air").contains("Test passed")); }
    private static void register(Bot bot) {
        bot.payload("minecraft:register",String.join("\0","numen:architecture_view","numen:architecture_catalog","numen:cooperation_state").getBytes(StandardCharsets.UTF_8));
    }
    private static void request(Bot bot,String action,int x,int y,int z,int rotation,UUID revision) throws Exception {
        Thread.sleep(150);ByteBuf b=Unpooled.buffer();
        try { CODEC.writeString(b,action);CODEC.writeString(b,"");b.writeLong(pack(x,y,z));CODEC.writeVarInt(b,rotation);b.writeLong(revision.getMostSignificantBits());b.writeLong(revision.getLeastSignificantBits());
            byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);bot.payload("numen:architecture_request",bytes);
        } finally { b.release(); }
    }
    private static View view(Bot bot,int mark,boolean confirmed) {
        return decode(bot.awaitPayload(mark,p->p.channel().equals("numen:architecture_view")&&decode(p).confirmed()==confirmed,WAIT,"新版本投影"));
    }
    private static View decode(Bot.Payload payload) {
        ByteBuf b=Unpooled.wrappedBuffer(payload.data());
        try { UUID revision=new UUID(b.readLong(),b.readLong());String name=CODEC.readString(b);CODEC.readString(b);long origin=b.readLong();
            int quarters=CODEC.readVarInt(b);boolean confirmed=b.readBoolean();b.readBoolean();CODEC.readString(b);int cells=CODEC.readVarInt(b);
            for(int i=0;i<cells;i++){b.readLong();CODEC.readVarInt(b);b.readBoolean();} assertEquals(0,b.readableBytes());
            return new View(revision,name,origin,quarters,confirmed,cells);
        } finally { b.release(); }
    }
    private static long pack(int x,int y,int z) { return ((long)x&0x3FFFFFFL)<<38|((long)z&0x3FFFFFFL)<<12|y&0xFFFL; }
}
