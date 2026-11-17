package com.dwinovo.numen.e2e;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;

/** Same native Agent and configured provider; a bounded read-only query in a disposable world. */
public final class LiveEnvironmentProbe extends Scenario {
    private final JsonObject report=new JsonObject();
    private NativeClientTest.Client client;
    private String service;
    private boolean keyInstalled;

    public static void main(String[] args) throws Exception {
        LiveEnvironmentProbe probe=new LiveEnvironmentProbe();
        try { probe.run(); probe.report.addProperty("passed",true); }
        finally {
            try {
                if (probe.keyInstalled) assertEquals("",probe.client.request("key","delete",json("service",probe.service).toString()).problem());
            } finally {
                try { probe.leave(); }
                finally {
                    MODEL.close();
                    Path output=Path.of(System.getProperty("live.environment.report"));
                    Files.createDirectories(output.getParent());
                    Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(probe.report));
                }
            }
        }
    }

    private void run() throws Exception {
        JsonObject profile=JsonParser.parseString(Files.readString(Path.of(System.getenv("NUMEN_LIVE_PROFILE_FILE")))).getAsJsonObject();
        service=profile.get("provider").getAsString();
        String key=Files.readString(Path.of(System.getenv("NUMEN_LIVE_KEY_FILE"))).strip();
        if (key.isEmpty() || key.equals(SPEC.modelKey())) throw new IllegalStateException("A real application key is required");
        Bot owner=join("live_env_owner","zh_cn");
        summon(owner,"LiveEnvNPC");
        client=new NativeClientTest.Client(owner);
        profile.addProperty("name","application_aligned");
        assertEquals("",client.request("provider","put",profile.toString()).problem());
        JsonObject credentials=json("service",service); credentials.addProperty("key",key);
        assertEquals("",client.request("key","put",credentials.toString()).problem());
        keyInstalled=true;
        int mark=owner.mark(); owner.command("numen companion LiveEnvNPC profile my:application_aligned");
        owner.await(mark,m->m.text().contains("my:application_aligned"),WAIT,"application model binding");
        console("fill -6 -61 -6 6 -61 6 minecraft:stone");
        console("fill -5 -60 -5 5 -58 5 minecraft:air");
        console("tp LiveEnvNPC 0.5 -60 0.5"); console("tp live_env_owner -2.5 -60 0.5");
        int count=ThreadLocalRandom.current().nextInt(2,6);
        for(int i=0;i<count;i++) console("setblock "+(i-2)+" -60 2 minecraft:gold_block");
        MODEL.reset();
        long started=System.currentTimeMillis(); mark=owner.mark();
        owner.command("msg LiveEnvNPC 进行一次只读的应用环境校验。请用lua工具调用numen.scan.blocks(\"gold_block\",{radius=6})，把返回的每个簇的count相加。不要移动、挖掘、放置或修改世界。最后只回复 LIVE_NATIVE_COUNT=<查到的总数量>，必须先实际调用工具，不得猜测。");
        owner.await(mark,m->m.text().contains("LIVE_NATIVE_COUNT="+count),Duration.ofSeconds(150),"real model native scan result");
        String log=Files.readString(Path.of(System.getProperty("live.environment.serverLog")));
        assertTrue(log.contains("tool_calls=[lua]"),"No native Lua tool call was observed");
        assertTrue(MODEL.asked().isEmpty(),"The smoke check unexpectedly used the local fake model");
        report.addProperty("provider",service);
        report.addProperty("model",profile.get("model").getAsString());
        report.addProperty("native_scan_count",count);
        report.addProperty("native_lua_call_verified",true);
        report.addProperty("fake_model_calls",MODEL.asked().size());
        report.addProperty("elapsed_ms",System.currentTimeMillis()-started);
        report.addProperty("vision_enabled",false);
        report.addProperty("scope","real model connection and native read-only scan; no RGB or planning comparison yet");
        System.out.println("Application-aligned real model and native scan verified.");
    }
    private static JsonObject json(String key,String value) { JsonObject o=new JsonObject();o.addProperty(key,value);return o; }
}
