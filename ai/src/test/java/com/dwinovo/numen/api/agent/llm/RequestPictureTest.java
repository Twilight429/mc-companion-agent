package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.provider.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestPictureTest {
    private static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==";

    private static LlmEndpoint endpoint(String model, String url) {
        return new LlmEndpoint("deepseek",url,Map.of(),"test-key",null,
                new ModelSpec(model,ThinkingTable.AUTO_ONLY,true),100000,
                Compats.resolve(Api.CHAT,"deepseek",model,url,ThinkingTable.AUTO_ONLY,null),RequestOptions.NONE);
    }

    @Test void onlyKnownDeepSeekVisionModelsAreEligible() {
        for (String model : List.of("deepseek-flash","deepseek-v4-flash","deepseek-v4-flash-vision-exp"))
            assertTrue(RequestPicture.supported(endpoint(model,"http://localhost")));
        for (String model : List.of("deepseek-v4-pro","deepseek-chat","some-other-model"))
            assertFalse(RequestPicture.supported(endpoint(model,"http://localhost")));
        assertFalse(RequestPicture.supported(LlmEndpoint.unresolved(null)));
        assertThrows(IllegalArgumentException.class,() -> new RequestPicture("camera","http://arbitrary-image"));
        assertFalse(new RequestPicture("camera",PNG).toString().contains("iVBOR"));
    }

    @Test void imageIsAUserPartAndSurvivesToolsButNeverLeaksIntoTheNextRequestOrHistory() throws Exception {
        List<JsonObject> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions", exchange -> {
            received.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] sse = ("data: {\"choices\":[{\"delta\":{\"content\":\"seen\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,sse.length);
            try (var out = exchange.getResponseBody()) { out.write(sse); }
            exchange.close();
        });
        server.start();
        try {
            var client = NumenLlmClient.forEndpoint(endpoint("deepseek-flash","http://127.0.0.1:"+server.getAddress().getPort()));
            var turn = new AssistantTurn("",List.of(new LlmToolCall("call-1","look","{}")),new JsonObject(),"");
            List<ConvoState.Msg> history = List.of(new ConvoState.Msg.User("look around"),
                    new ConvoState.Msg.Assistant(turn),new ConvoState.Msg.Tool("call-1","visible blocks"));
            client.chatStreaming(history,List.of(),"system","npc-a",new CancelToken(),null,
                    new RequestPicture("NPC eye view at this instant",PNG)).get(5,TimeUnit.SECONDS);
            client.chatStreaming(history,List.of(),"system","npc-a",new CancelToken(),null).get(5,TimeUnit.SECONDS);
            JsonArray messages = received.getFirst().getAsJsonArray("messages");
            var camera = messages.get(messages.size()-1).getAsJsonObject();
            assertEquals("user",camera.get("role").getAsString());
            var content = camera.getAsJsonArray("content");
            assertEquals(2,content.size());
            assertEquals("NPC eye view at this instant",content.get(0).getAsJsonObject().get("text").getAsString());
            var image = content.get(1).getAsJsonObject();
            assertEquals("image_url",image.get("type").getAsString());
            assertEquals(PNG,image.getAsJsonObject("image_url").get("url").getAsString());
            assertEquals("tool",messages.get(messages.size()-2).getAsJsonObject().get("role").getAsString());
            assertFalse(received.get(1).toString().contains("image_url"));
            assertEquals(3,history.size());
            assertEquals("look around",((ConvoState.Msg.User)history.getFirst()).content());
            assertEquals("seen",client.provider().parseResponseBody(JsonParser.parseString(
                    "{\"choices\":[{\"message\":{\"content\":\"seen\"}}]}").getAsJsonObject()).content());
        } finally { server.stop(0); NumenLlmClient.reset(); }
    }

    @Test void unavailableCameraIsHonestTextAndCancellationDoesNotSend() {
        var client = NumenLlmClient.forEndpoint(endpoint("deepseek-flash","http://127.0.0.1:1"));
        var missing = new RequestPicture("NPC camera unavailable: unloaded chunk; no image attached.",null);
        JsonObject message = client.provider().buildUserMessage(missing);
        assertEquals("user",message.get("role").getAsString());
        assertTrue(message.get("content").getAsString().contains("unavailable"));
        assertFalse(message.toString().contains("image_url"));
        CancelToken cancel = new CancelToken(); cancel.cancel();
        assertThrows(CancellationException.class,() -> client.chatStreaming(List.of(),List.of(),"","npc",cancel,null,
                new RequestPicture("camera",PNG)).join());
        assertThrows(IllegalArgumentException.class,() -> NumenLlmClient.forEndpoint(endpoint("deepseek-v4-pro","http://127.0.0.1:1"))
                .chatStreaming(List.of(),List.of(),"","npc",new CancelToken(),null,new RequestPicture("camera",PNG)));
    }
}
