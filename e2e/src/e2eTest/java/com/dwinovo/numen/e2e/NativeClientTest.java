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

    private record Table(String kind, String entries, String problem, long request) {}

    /** 对照 HelloPayload、LibraryRequestPayload、LibraryPayload 的固定 1.21.1 线协议。 */
    private static final class Client {
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
