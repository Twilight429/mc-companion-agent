package com.dwinovo.numen.api.client.camera;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class NpcCameraHttpTest {
    @Test void experimentControlsRequireAuthenticationAndExplicitLaunchOptIn() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String token="Bearer unit-test-token-with-enough-length";
        server.createContext("/experiment/", ex -> NpcCameraHttp.control(ex,token.getBytes(StandardCharsets.UTF_8)));
        server.start();
        String previous=System.getProperty("numen.experiment.control");
        System.clearProperty("numen.experiment.control");
        try (HttpClient client=HttpClient.newHttpClient()) {
            URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/experiment/connect");
            assertEquals(401,client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(503,client.send(HttpRequest.newBuilder(uri).header("Authorization",token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop(0);
            if (previous!=null) System.setProperty("numen.experiment.control",previous);
        }
    }
    @Test void rejectsUnauthenticatedMethodsAndMalformedIdentitiesBeforeTouchingGame() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String token="Bearer unit-test-token-with-enough-length";
        server.createContext("/npc/camera", ex -> NpcCameraHttp.handle(ex,token.getBytes(StandardCharsets.UTF_8)));
        server.start();
        try (HttpClient client=HttpClient.newHttpClient()) {
            URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/npc/camera?companion=invalid");
            var noAuth=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,noAuth.statusCode());
            assertEquals("no-store",noAuth.headers().firstValue("Cache-Control").orElseThrow());
            var badId=client.send(HttpRequest.newBuilder(uri).header("Authorization",token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,badId.statusCode());
            var post=client.send(HttpRequest.newBuilder(uri).header("Authorization",token).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(405,post.statusCode());
            URI valid=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/npc/camera?companion="+java.util.UUID.randomUUID());
            var disabled=client.send(HttpRequest.newBuilder(valid).header("Authorization",token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(503,disabled.statusCode());
            assertTrue(disabled.body().contains("disabled"));
        } finally { server.stop(0); }
    }
}
