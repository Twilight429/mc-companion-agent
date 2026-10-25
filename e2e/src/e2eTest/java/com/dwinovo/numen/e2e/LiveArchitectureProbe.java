package com.dwinovo.numen.e2e;

import com.google.gson.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodecHelper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in acceptance against the owner's configured model, inside the disposable test world only. */
public final class LiveArchitectureProbe extends Scenario {
    private static final MinecraftCodecHelper CODEC=new MinecraftCodecHelper();
    private static final Duration LIVE_WAIT=Duration.ofMinutes(6);
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final JsonObject report=new JsonObject();
    private Bot owner;
    private long serial;
    private String service;
    public static void main(String[] args) throws Exception {
        var probe=new LiveArchitectureProbe();
        try { probe.run(); probe.report.addProperty("passed",true); }
        finally {
            try { if(probe.owner!=null&&probe.service!=null) probe.library("key","delete",object("service",probe.service)); }
            finally {
                try {
                    probe.leave();
                    Path output=Path.of(System.getProperty("live.design.report"));Files.createDirectories(output.getParent());
                    Files.writeString(output,JSON.toJson(probe.report));
                } finally { MODEL.close(); }
            }
        }
    }
    private void run() throws Exception {
        var source=JsonParser.parseString(Files.readString(Path.of(System.getenv("NUMEN_DESIGN_PROFILE_FILE")))).getAsJsonObject();
        JsonObject profile=new JsonObject();
        for(String field:new String[]{"provider","protocol","model","base_url","temperature","max_tokens","reasoning_effort","service"})
            if(source.has(field)) profile.add(field,source.get(field));
        profile.addProperty("name","live_architecture");
        service=source.has("service")?source.get("service").getAsString():source.get("provider").getAsString();
        String key=Files.readString(Path.of(System.getenv("NUMEN_DESIGN_KEY_FILE"))).strip();
        if(key.isEmpty()) throw new IllegalStateException("Configured key file is empty");
        owner=join("live_arch_owner","zh_cn");
        // Before mod hello the server renders translations; this protocol-only observer has no client language resources.
        summon(owner,"LiveArchitect");
        owner.payload("minecraft:register",String.join("\0","numen_api:hello_ack","numen_api:library","numen:architecture_view","numen:architecture_catalog","numen:cooperation_state").getBytes(StandardCharsets.UTF_8));
        owner.payload("numen_api:hello",encode(b->CODEC.writeVarInt(b,3)));
        owner.awaitPayload(p->p.channel().equals("numen_api:hello_ack"),WAIT,"原生客户端报到");
        library("provider","put",profile);
        var credentials=object("service",service);credentials.addProperty("key",key);library("key","put",credentials);
        int mark=owner.mark();owner.command("numen companion LiveArchitect profile my:live_architecture");
        owner.await(mark,m->m.text().contains("my:live_architecture"),WAIT,"绑定本地已配置模型");
        console("forceload add 688 688 735 735");console("tp LiveArchitect 699.5 -60 700.5");console("tp live_arch_owner 690.5 -60 700.5");
        report.addProperty("model",profile.get("model").getAsString());
        long started=System.currentTimeMillis();
        int pm=owner.payloadMark();
        owner.command("msg LiveArchitect 请为我们两个人原创设计一栋中世纪村舍，不要找下载模板。占地7×7左右、单层、墙高至少3格，有可通行入口、玻璃窗、坡屋顶、两张床和工作台。主要用橡木、云杉木与少量石材。投影原点在700,-60,700，朝向0度。选择已足够明确，不用再问我；请阅读building_design及medieval_rustic参考、原生architecture与shape帮助，用Cells设计完整建筑并调用architecture.propose展示。只做投影，不采集、不移动、不施工，也不能用指令改世界。");
        var first=projection(owner.awaitPayload(pm,p->p.channel().equals("numen:architecture_view"),LIVE_WAIT,"真实模型设计并投放世界投影"));
        assertFalse(first.get("confirmed").getAsBoolean());assertTrue(first.get("cells").getAsInt()>80);
        var entry=entry(first.get("name").getAsString());report.add("initial",entry.deepCopy());
        assertTrue(entry.get("compatible").getAsBoolean());
        assertTrue(entry.getAsJsonObject("blocks").entrySet().stream().anyMatch(e->e.getKey().contains("bed")),"请求的床没有被设计");
        assertTrue(entry.getAsJsonObject("blocks").entrySet().stream().anyMatch(e->e.getKey().contains("glass")),"请求的窗户没有被设计");
        assertTrue(console("execute if block 700 -60 700 minecraft:air").contains("Test passed"));
        waitForReply();
        pm=owner.payloadMark();
        owner.command("msg LiveArchitect 请修改刚才的原创草稿：把屋顶所有云杉木材质替换成深色橡木的对应方块，保留其他设计、当前位置和朝向。先读最新draft，可分页读取Cells，再用revise或带最新revision的propose发布。不要建造、采集或移动，只更新投影，并说明改动。");
        String firstRevision=first.get("revision").getAsString();
        var edited=projection(owner.awaitPayload(pm,p->p.channel().equals("numen:architecture_view")&&!projection(p).get("revision").getAsString().equals(firstRevision),LIVE_WAIT,"真实模型对话修改投影"));
        assertFalse(edited.get("confirmed").getAsBoolean());assertEquals(first.get("origin"),edited.get("origin"));
        var next=entry(edited.get("name").getAsString());report.add("revised",next.deepCopy());
        assertTrue(next.getAsJsonObject("blocks").entrySet().stream().anyMatch(e->e.getKey().contains("dark_oak")),"修改后的投影没有包含要求的深色橡木");
        waitForReply();
        UUID revision=UUID.fromString(edited.get("revision").getAsString());
        owner.payload("numen:architecture_request",encode(b->{CODEC.writeString(b,"confirm");CODEC.writeString(b,"");b.writeLong(0);CODEC.writeVarInt(b,0);b.writeLong(revision.getMostSignificantBits());b.writeLong(revision.getLeastSignificantBits());}));
        owner.awaitPayload(p->p.channel().equals("numen:architecture_view")&&projection(p).get("confirmed").getAsBoolean(),WAIT,"玩家确认修改后的投影");
        pm=owner.payloadMark();
        owner.command("msg LiveArchitect 我已确认投影。现在我们合作建造，我负责采集，你只负责最低一层地基。先通过cooperate.begin和assign建立合作任务并报告缺料；这轮不要实际施工、采集或移动，等我交材料。");
        var task=owner.awaitPayload(pm,p->{if(!p.channel().equals("numen:cooperation_state")) return false;var v=json(p);return v.has("plan")&&v.getAsJsonObject("plan").get("active").getAsBoolean()
                &&v.getAsJsonObject("plan").get("scope").getAsString().equals("foundation:1");},LIVE_WAIT,"真实模型开启约定地基范围的合作任务");
        var plan=json(task).getAsJsonObject("plan");assertEquals("foundation:1",plan.get("scope").getAsString());
        report.add("cooperation",plan.deepCopy());report.addProperty("elapsedSeconds",(System.currentTimeMillis()-started)/1000);
        Path artifacts=Path.of(System.getProperty("live.design.report")).getParent().resolve("artifacts");Files.createDirectories(artifacts);
        for(var version:new JsonObject[]{first,edited}) {
            String name=version.get("name").getAsString();
            for(String suffix:new String[]{".nbt",".catalog.json"})
                Files.copy(Path.of(System.getProperty("live.design.world"),"schematics",name+suffix),artifacts.resolve(name+suffix));
        }
        assertTrue(console("execute if block 700 -60 700 minecraft:air").contains("Test passed"));
        System.out.println("Live original design → revised projection → owner confirmation → cooperation verified.");
    }
    private void waitForReply() throws Exception {
        // A ghost is a tool result, not the end of a model turn. Let the bounded final explanation finish before new feedback.
        long end=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        int unchanged=0,last=owner.mark();
        while(System.nanoTime()<end&&unchanged<5){Thread.sleep(1000);int now=owner.mark();unchanged=now==last?unchanged+1:0;last=now;}
    }
    private JsonObject entry(String name) throws Exception {
        var entries=JsonParser.parseString(Files.readString(Path.of(System.getProperty("live.design.world"),"schematics","architecture-index.json"))).getAsJsonArray();
        for(var e:entries) if(e.getAsJsonObject().get("id").getAsString().equals(name)) return e.getAsJsonObject();
        throw new AssertionError("Published projection is not indexed");
    }
    private void library(String kind,String op,JsonObject body) {
        long number=++serial;
        owner.payload("numen_api:library_request",encode(b->{CODEC.writeString(b,kind);CODEC.writeString(b,op);CODEC.writeString(b,body.toString());CODEC.writeVarLong(b,number);}));
        var p=owner.awaitPayload(p1->{if(!p1.channel().equals("numen_api:library"))return false;ByteBuf b=Unpooled.wrappedBuffer(p1.data());
            try{CODEC.readString(b);CODEC.readString(b);CODEC.readString(b);return CODEC.readVarLong(b)==number;}finally{b.release();}},WAIT,"测试模型配置确认");
        ByteBuf b=Unpooled.wrappedBuffer(p.data());
        try{CODEC.readString(b);CODEC.readString(b);assertEquals("",CODEC.readString(b),"测试模型配置未受理");}finally{b.release();}
    }
    private static JsonObject object(String key,String value){var o=new JsonObject();o.addProperty(key,value);return o;}
    private static JsonObject json(Bot.Payload p){ByteBuf b=Unpooled.wrappedBuffer(p.data());try{return JsonParser.parseString(CODEC.readString(b)).getAsJsonObject();}finally{b.release();}}
    private static JsonObject projection(Bot.Payload p){ByteBuf b=Unpooled.wrappedBuffer(p.data());try{
        var o=new JsonObject();o.addProperty("revision",new UUID(b.readLong(),b.readLong()).toString());o.addProperty("name",CODEC.readString(b));CODEC.readString(b);
        o.addProperty("origin",b.readLong());CODEC.readVarInt(b);o.addProperty("confirmed",b.readBoolean());b.readBoolean();CODEC.readString(b);o.addProperty("cells",CODEC.readVarInt(b));return o;
    }finally{b.release();}}
    private static byte[] encode(Consumer<ByteBuf> writer){ByteBuf b=Unpooled.buffer();try{writer.accept(b);byte[] a=new byte[b.readableBytes()];b.readBytes(a);return a;}finally{b.release();}}
}
