package com.dwinovo.numen.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodecHelper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 TCP 上的 Numen 客户端配置协议；不依赖 GameTest 的模拟连接，也不接触真实 API key。 */
class NativeClientTest extends Scenario {
    private static final MinecraftCodecHelper CODEC = new MinecraftCodecHelper();

    @Test void discard_settings_are_per_companion_and_drive_real_inventory_permissions() throws Exception {
        Bot ownerBot = join("e2e_discard", "en_us");
        summon(ownerBot, "Keeper");
        summon(ownerBot, "OtherKeeper");
        Client owner = new Client(ownerBot);
        Client stranger = new Client(join("e2e_dropother", "en_us"));
        var table = owner.request("permission", "list", "{}");
        JsonObject first = discardRow(table, companionId(table, "Keeper"));
        String uuid = first.get("companion").getAsString();
        String otherUuid = companionId(table, "OtherKeeper");
        assertTrue(first.get("wood").getAsBoolean());
        assertTrue(first.get("minerals").getAsBoolean());
        first.remove("row");
        first.getAsJsonArray("keep").add("minecraft:bread");
        first.getAsJsonArray("discard").add("minecraft:gold_ingot");
        assertEquals("", owner.request("permission", "discard", first.toString()).problem());
        assertFalse(stranger.request("permission", "discard", first.toString()).problem().isEmpty(),
                "another player changed someone else's companion settings");
        assertTrue(discardRow(owner.request("permission", "list", "{}"), otherUuid).getAsJsonArray("discard").isEmpty(),
                "one companion's exception leaked to its sibling");
        console("give Keeper minecraft:gold_ingot 4");
        console("give Keeper minecraft:diamond 2");
        console("give Keeper minecraft:bread 3");
        MODEL.whenHeard("discard policy integration", FakeModel.lua("local gold=numen.inv.drop(\"gold_ingot\", {count=4}); "
                + "local diamond=pcall(numen.inv.drop, \"diamond\", {count=2}); "
                + "local bread=pcall(numen.inv.drop, \"bread\", {count=3}); "
                + "assert(gold.dropped==4 and not diamond and not bread); print(\"DISCARD_POLICY_EXECUTED\")"),
                FakeModel.say("Discard policy integration complete."));
        int mark = ownerBot.mark();
        ownerBot.command("msg Keeper discard policy integration");
        ownerBot.await(mark, m -> m.text().contains("Discard policy integration complete."), WAIT, "丢弃执行回执");
        assertTrue(MODEL.asked().stream().flatMap(a -> a.toolResults().stream()).anyMatch(s -> s.contains("DISCARD_POLICY_EXECUTED")),
                "script did not reach the checks after trying all three drops");
        assertTrue(console("clear Keeper minecraft:gold_ingot 0").contains("No items"), "the allowed exception did not drop");
        assertTrue(console("clear Keeper minecraft:diamond 0").contains("2"), "default protected resource was lost");
        assertTrue(console("clear Keeper minecraft:bread 0").contains("3"), "custom protected item was lost");
        MODEL.whenHeard("read discard policy", FakeModel.lua("local p=numen.permission.discard_settings(); "
                + "assert(p.protect_wood and p.protect_minerals); assert(#p.protected_carried >= 2); print(\"DISCARD_READ_CHECKED\"); return p"),
                FakeModel.say("Discard policy read successfully."));
        mark = ownerBot.mark(); ownerBot.command("msg Keeper read discard policy");
        ownerBot.await(mark, m -> m.text().contains("Discard policy read successfully."), WAIT, "模型可读取清单");
        assertTrue(MODEL.asked().stream().flatMap(a -> a.toolResults().stream()).anyMatch(s -> s.contains("DISCARD_READ_CHECKED")));
    }


    /** 真服务端与客户端协议验证截图归属、附图、超时和历史隔离;这里的 PNG 是测试图,不替代 GPU 渲染验收。 */
    @Test void npc_camera_reaches_the_model_only_from_the_owner_and_is_request_scoped() throws Exception {
        Bot ownerBot=join("e2e_camera","en_us");
        Bot stranger=join("e2e_camother","en_us");
        summon(ownerBot,"CameraNPC");
        Client owner=new Client(ownerBot);
        new Client(stranger);
        ownerBot.payload("minecraft:register","numen_api:npc_camera_request".getBytes(StandardCharsets.UTF_8));
        assertEquals("",owner.request("provider","put","{\"name\":\"camera_test\",\"provider\":\"deepseek\",\"protocol\":\"chat\","
                + "\"model\":\"deepseek-flash\",\"base_url\":\"http://127.0.0.1:"+SPEC.modelPort()+"/v1\"}").problem());
        assertEquals("",owner.request("key","put","{\"service\":\"deepseek\",\"key\":\""+SPEC.modelKey()+"\"}").problem());
        int mark=ownerBot.mark();
        ownerBot.command("numen companion CameraNPC profile my:camera_test");
        ownerBot.await(mark,m->m.text().contains("my:camera_test"),WAIT,"camera model binding");
        // 单独运行时也有脚下的地面,避免掉落使测试图里的固定眼睛坐标失效。
        console("fill -8 64 -8 8 64 8 minecraft:stone");
        console("tp CameraNPC 0 65 0"); console("tp e2e_camera 2 65 0");

        var image=new java.awt.image.BufferedImage(640,360,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();
        graphics.setColor(java.awt.Color.CYAN); graphics.fillRect(0,0,640,360); graphics.dispose();
        var pngOut=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image,"png",pngOut);
        byte[] png=pngOut.toByteArray();
        MODEL.script(a->a.body().contains("camera integration first"),FakeModel.say("Camera integration received."));
        int payloadMark=ownerBot.payloadMark(); mark=ownerBot.mark();
        ownerBot.command("msg CameraNPC camera integration first");
        Bot.Payload request=ownerBot.awaitPayload(payloadMark,p->p.channel().equals("numen_api:npc_camera_request"),WAIT,"first-person camera request");
        ByteBuf in=Unpooled.wrappedBuffer(request.data());
        java.util.UUID nonce,companion; String dimension;
        try {
            nonce=new java.util.UUID(in.readLong(),in.readLong());
            companion=new java.util.UUID(in.readLong(),in.readLong());
            dimension=CODEC.readString(in);
        } finally { in.release(); }
        byte[] reply=cameraReply(nonce,companion,dimension,png);
        int calls=MODEL.asked().size();
        stranger.payload("numen_api:npc_camera_reply",reply);
        Thread.sleep(150);
        assertEquals(calls,MODEL.asked().size(),"a stranger's forged frame completed the request");
        ownerBot.payload("numen_api:npc_camera_reply",reply);
        ownerBot.await(mark,m->m.text().contains("Camera integration received."),WAIT,"camera model reply");
        var first=MODEL.asked().stream().filter(a->a.body().contains("camera integration first")&&a.body().contains("image_url")).findFirst().orElseThrow();
        assertTrue(first.body().contains("data:image/png;base64,"));
        assertTrue(first.body().contains(companion.toString()));
        assertEquals(1,pictureCount(first.body()));

        MODEL.script(a->a.body().contains("camera integration timeout"),FakeModel.say("Camera timeout handled."));
        payloadMark=ownerBot.payloadMark(); mark=ownerBot.mark();
        ownerBot.command("msg CameraNPC camera integration timeout");
        ownerBot.awaitPayload(payloadMark,p->p.channel().equals("numen_api:npc_camera_request"),WAIT,"second camera request");
        // 上一轮迟到的帧不能冒充这一轮;本次故意不答,确认 3 秒后能继续规划。
        ownerBot.payload("numen_api:npc_camera_reply",reply);
        ownerBot.await(mark,m->m.text().contains("Camera timeout handled."),WAIT,"honest missing-camera reply");
        var second=MODEL.asked().stream().filter(a->a.body().contains("camera integration timeout")).findFirst().orElseThrow();
        assertEquals(0,pictureCount(second.body()),"previous screenshot was retained in conversation history");
        assertTrue(second.body().contains("NPC camera unavailable"));

        MODEL.script(a->a.body().contains("camera integration disabled"),FakeModel.say("Camera disabled respected."));
        payloadMark=ownerBot.payloadMark(); mark=ownerBot.mark();
        ownerBot.command("msg CameraNPC camera integration disabled");
        request=ownerBot.awaitPayload(payloadMark,p->p.channel().equals("numen_api:npc_camera_request"),WAIT,"disabled camera request");
        in=Unpooled.wrappedBuffer(request.data());
        try {
            nonce=new java.util.UUID(in.readLong(),in.readLong());
            companion=new java.util.UUID(in.readLong(),in.readLong());
            dimension=CODEC.readString(in);
        } finally { in.release(); }
        ownerBot.payload("numen_api:npc_camera_reply",cameraReply(nonce,companion,dimension,new byte[0],
                "visual experiments are disabled in this client"));
        ownerBot.await(mark,m->m.text().contains("Camera disabled respected."),WAIT,"disabled camera model reply");
        var disabled=MODEL.asked().stream().filter(a->a.body().contains("camera integration disabled")).findFirst().orElseThrow();
        assertEquals(0,pictureCount(disabled.body()));
        assertFalse(disabled.body().contains("NPC camera unavailable"),"disabled vision still injected a visual placeholder");
    }

    private static int pictureCount(String body) {
        int count=0;
        for (var entry:JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages")) {
            var content=entry.getAsJsonObject().get("content");
            if (content!=null&&content.isJsonArray()) for (var part:content.getAsJsonArray())
                if (part.isJsonObject()&&part.getAsJsonObject().has("image_url")) count++;
        }
        return count;
    }

    private static byte[] cameraReply(java.util.UUID nonce,java.util.UUID companion,String dimension,byte[] png) {
        return cameraReply(nonce,companion,dimension,png,"");
    }

    private static byte[] cameraReply(java.util.UUID nonce,java.util.UUID companion,String dimension,byte[] png,String problem) {
        return encode(buf->{
            buf.writeLong(nonce.getMostSignificantBits()); buf.writeLong(nonce.getLeastSignificantBits());
            buf.writeLong(companion.getMostSignificantBits()); buf.writeLong(companion.getLeastSignificantBits());
            CODEC.writeString(buf,dimension); buf.writeDouble(0); buf.writeDouble(66.62); buf.writeDouble(0);
            buf.writeFloat(0); buf.writeFloat(0); CODEC.writeVarInt(buf,png.length); buf.writeBytes(png); CODEC.writeString(buf,problem);
        });
    }

    private static String companionId(Table table, String name) {
        for (var e : JsonParser.parseString(table.entries()).getAsJsonArray()) {
            var o = e.getAsJsonObject();
            if (o.has("row") && "mode".equals(o.get("row").getAsString()) && name.equals(o.get("name").getAsString()))
                return o.get("companion").getAsString();
        }
        throw new AssertionError("companion missing: " + name);
    }
    private static JsonObject discardRow(Table table, String companion) {
        assertEquals("", table.problem());
        for (var e : JsonParser.parseString(table.entries()).getAsJsonArray()) {
            var o = e.getAsJsonObject();
            if (o.has("row") && "discard".equals(o.get("row").getAsString()) && companion.equals(o.get("companion").getAsString()))
                return o;
        }
        throw new AssertionError("discard policy missing: " + companion);
    }

    @Test void a_client_can_configure_its_own_model_and_key_without_exposing_them_to_another_player() throws Exception {
        Client owner = new Client(join("e2e_clientcfg", "en_us"));
        Client other = new Client(join("e2e_clientother", "en_us"));
        Table saved = owner.request("provider", "put", "{\"name\":\"cooperation_test\",\"provider\":\"custom\","
                + "\"protocol\":\"chat\",\"model\":\"e2e-fake\",\"base_url\":\"http://127.0.0.1:" + SPEC.modelPort() + "/v1\"}");
        assertEquals("", saved.problem());
        assertTrue(saved.entries().contains("my:cooperation_test"));
        Table key = owner.request("key", "put", "{\"service\":\"custom\",\"key\":\"e2e-client-test-key\"}");
        assertEquals("", key.problem());
        assertTrue(key.entries().contains("e2e-client-test-key"), "the owner did not receive its own saved test key");
        assertFalse(other.request("provider", "list", "{}").entries().contains("my:cooperation_test"));
        assertFalse(other.request("key", "list", "{}").entries().contains("e2e-client-test-key"));
        assertFalse(owner.request("provider", "put", "{\"group\":\"builtin\",\"name\":\"unauthorized\","
                + "\"provider\":\"custom\",\"model\":\"e2e-fake\"}").problem().isEmpty(),
                "ordinary client was allowed to edit the server's shared model profiles");
        assertEquals("", owner.request("key", "delete", "{\"service\":\"custom\"}").problem());
        assertFalse(owner.request("key", "list", "{}").entries().contains("e2e-client-test-key"));
    }

    @Test void the_bundled_cooperation_skill_is_visible_and_can_be_disabled_for_only_one_player() throws Exception {
        Client owner = new Client(join("e2e_clientskill", "en_us"));
        Client other = new Client(join("e2e_skillother", "en_us"));
        JsonObject skill = cooperationSkill(owner.request("skill", "list", "{}"));
        assertTrue(skill.get("enabled").getAsBoolean());
        String id = "{\"id\":\"" + skill.get("id").getAsString() + "\"}";
        assertFalse(cooperationSkill(owner.request("skill", "disable", id)).get("enabled").getAsBoolean());
        assertTrue(cooperationSkill(other.request("skill", "list", "{}")).get("enabled").getAsBoolean(),
                "one player's toggle changed another player's skill");
        assertTrue(cooperationSkill(owner.request("skill", "enable", id)).get("enabled").getAsBoolean());
    }

    private static JsonObject cooperationSkill(Table table) {
        assertEquals("", table.problem());
        JsonArray entries = JsonParser.parseString(table.entries()).getAsJsonArray();
        for (var entry : entries) {
            JsonObject row = entry.getAsJsonObject();
            if (row.has("id") && row.get("id").getAsString().contains("player_cooperation")) return row;
        }
        throw new AssertionError("cooperation skill missing from the real server's native skill library");
    }

    record Table(String kind, String entries, String problem, long request) {}

    /** 对照 HelloPayload、LibraryRequestPayload、LibraryPayload 的固定 1.21.1 线协议。 */
    static final class Client {
        private final Bot bot;
        private long request;

        Client(Bot bot) {
            this.bot = bot;
            bot.payload("minecraft:register", String.join("\0", "numen_api:hello_ack", "numen_api:library")
                    .getBytes(StandardCharsets.UTF_8));
            bot.payload("numen_api:hello", encode(buf -> CODEC.writeVarInt(buf, 3)));
            Bot.Payload ack = bot.awaitPayload(p -> p.channel().equals("numen_api:hello_ack"), WAIT, "原生客户端报到答复");
            ByteBuf buf = Unpooled.wrappedBuffer(ack.data());
            try {
                assertEquals(3, CODEC.readVarInt(buf));
                assertFalse(buf.readBoolean(), "test player unexpectedly has administrator privileges");
            } finally { buf.release(); }
        }

        Table request(String kind, String op, String json) {
            long number = ++request;
            bot.payload("numen_api:library_request", encode(buf -> {
                CODEC.writeString(buf, kind);
                CODEC.writeString(buf, op);
                CODEC.writeString(buf, json);
                CODEC.writeVarLong(buf, number);
            }));
            return table(bot.awaitPayload(p -> p.channel().equals("numen_api:library") && table(p).request() == number,
                    WAIT, "原生配置答复 " + kind + "/" + op));
        }
    }

    private static Table table(Bot.Payload payload) {
        ByteBuf buf = Unpooled.wrappedBuffer(payload.data());
        try {
            return new Table(CODEC.readString(buf), CODEC.readString(buf), CODEC.readString(buf), CODEC.readVarLong(buf));
        } finally { buf.release(); }
    }

    private static byte[] encode(Consumer<ByteBuf> writer) {
        ByteBuf buf = Unpooled.buffer();
        try {
            writer.accept(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally { buf.release(); }
    }
}
