package com.dwinovo.numen.api.client.camera;

import com.dwinovo.numen.api.Constants;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 可选的本机只读桥。-Dnumen.camera.port=8766 和 NUMEN_CAMERA_TOKEN 启用。 */
public final class NpcCameraHttp {
    private static HttpServer server;
    private static final Semaphore CAPTURES = new Semaphore(1);
    private NpcCameraHttp() {}

    public static synchronized void install() {
        if (!com.dwinovo.numen.api.client.data.ClientPrefs.experimentalVisionEnabled()) return;
        int port=Integer.getInteger("numen.camera.port",0);
        if (port==0 || server!=null) return;
        String token=System.getenv("NUMEN_CAMERA_TOKEN");
        if (token==null || token.length()<24) throw new IllegalStateException("NUMEN_CAMERA_TOKEN must contain at least 24 characters");
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),8);
            byte[] expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
            server.createContext("/npc/camera", ex -> handle(ex,expected));
            if (Boolean.getBoolean("numen.experiment.control"))
                server.createContext("/experiment/", ex -> control(ex,expected));
            server.setExecutor(command -> Thread.ofVirtual().name("numen-camera-http").start(command));
            Thread starter=new Thread(server::start,"numen-camera-http-start");
            starter.setDaemon(true); starter.start(); starter.join();
            Runtime.getRuntime().addShutdownHook(new Thread(NpcCameraHttp::stop,"numen-camera-http-stop"));
            Constants.LOG.info("[numen-camera] local RGB/surface endpoint on 127.0.0.1:{}",port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Camera endpoint startup interrupted",e);
        } catch (IOException e) { throw new IllegalStateException("Cannot bind local camera endpoint",e); }
    }

    /** 仅显式实验会话开放,连接目的地固定为独立测试服。 */
    static void control(HttpExchange ex, byte[] expected) throws IOException {
        try (ex) {
            ex.getResponseHeaders().set("Cache-Control","no-store");
            String auth=ex.getRequestHeaders().getFirst("Authorization");
            if (auth==null || !MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8))) {
                send(ex,401,"Bearer token required"); return;
            }
            if (!Boolean.getBoolean("numen.experiment.control")
                    || !com.dwinovo.numen.api.client.data.ClientPrefs.experimentalVisionEnabled()) {
                send(ex,503,"Experiment control disabled"); return;
            }
            String path=ex.getRequestURI().getPath();
            if (!(path.equals("/experiment/connect") && ex.getRequestMethod().equals("POST"))
                    && !(path.equals("/experiment/state") && ex.getRequestMethod().equals("GET"))) {
                send(ex,400,"Use POST /experiment/connect or GET /experiment/state"); return;
            }
            CompletableFuture<JsonObject> result=new CompletableFuture<>();
            Minecraft.getInstance().execute(() -> {
                try {
                    var mc=Minecraft.getInstance();
                    if (path.equals("/experiment/connect")) {
                        if (mc.getConnection()!=null) throw new IllegalStateException("Disconnect existing world before reconnect");
                        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                                new net.minecraft.client.gui.screens.TitleScreen(),mc,
                                net.minecraft.client.multiplayer.resolver.ServerAddress.parseString("127.0.0.1:25566"),
                                new net.minecraft.client.multiplayer.ServerData("Numen experiment","127.0.0.1:25566",
                                        net.minecraft.client.multiplayer.ServerData.Type.OTHER),false,null);
                    }
                    JsonObject state=new JsonObject();
                    state.addProperty("connected",mc.getConnection()!=null);
                    state.addProperty("world_loaded",mc.level!=null);
                    result.complete(state);
                } catch (RuntimeException e) { result.completeExceptionally(e); }
            });
            try {
                byte[] bytes=result.get(5,TimeUnit.SECONDS).toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); send(ex,503,"Control interrupted");
            } catch (java.util.concurrent.ExecutionException | TimeoutException e) { send(ex,503,"Client control unavailable"); }
        }
    }

    public static synchronized void stop() {
        if (server!=null) { server.stop(0); server=null; }
    }

    static void handle(HttpExchange ex, byte[] expected) throws IOException {
        try (ex) {
            ex.getResponseHeaders().set("Cache-Control","no-store");
            String auth=ex.getRequestHeaders().getFirst("Authorization");
            if (auth==null || !MessageDigest.isEqual(expected,auth.getBytes(StandardCharsets.UTF_8))) {
                send(ex,401,"Bearer token required"); return;
            }
            if (!ex.getRequestURI().getPath().equals("/npc/camera")) { send(ex,404,"Unknown endpoint"); return; }
            if (!ex.getRequestMethod().equals("GET")) { ex.getResponseHeaders().set("Allow","GET"); send(ex,405,"GET required"); return; }
            UUID companion;
            try {
                String query=ex.getRequestURI().getRawQuery();
                if (query==null || !query.startsWith("companion=") || query.contains("&")) throw new IllegalArgumentException();
                companion=UUID.fromString(query.substring("companion=".length()));
            } catch (IllegalArgumentException e) { send(ex,400,"Use ?companion=<UUID>"); return; }
            if (!com.dwinovo.numen.api.client.data.ClientPrefs.experimentalVisionEnabled()) {
                send(ex,503,"Visual experiments are disabled in this client"); return;
            }
            if (!CAPTURES.tryAcquire()) { send(ex,429,"A capture is already pending"); return; }
            CompletableFuture<JsonObject> frame=new CompletableFuture<>();
            try {
                Minecraft.getInstance().execute(() -> NpcCameraRenderer.acceptLocal(companion,frame));
                JsonObject result=frame.get(3,TimeUnit.SECONDS);
                byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes);
            } catch (TimeoutException e) {
                send(ex,504,"No fresh frame within 3 seconds; game must be rendering");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); send(ex,503,"Capture interrupted");
            } catch (java.util.concurrent.ExecutionException e) {
                send(ex,503,e.getCause().getMessage());
            } finally { frame.cancel(false); CAPTURES.release(); }
        }
    }

    private static void send(HttpExchange ex,int status,String message) throws IOException {
        JsonObject error=new JsonObject(); error.addProperty("error",message);
        byte[] bytes=error.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        ex.sendResponseHeaders(status,bytes.length); ex.getResponseBody().write(bytes);
    }
}
