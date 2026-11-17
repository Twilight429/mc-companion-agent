package com.dwinovo.numen.api.client.camera;

import com.dwinovo.numen.api.client.data.ClientPrefs;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class VisionSwitchTest {
    @Test void defaultOffAndExplicitChoiceSurvivesRestart(@TempDir Path dir) {
        ClientPrefs.init(dir);
        assertFalse(ClientPrefs.experimentalVisionEnabled());
        ClientPrefs.setExperimentalVisionEnabled(true);
        ClientPrefs.init(dir);
        assertTrue(ClientPrefs.experimentalVisionEnabled());
        ClientPrefs.setExperimentalVisionEnabled(false);
        ClientPrefs.init(dir);
        assertFalse(ClientPrefs.experimentalVisionEnabled());
    }

    @Test void disabledLocalCaptureNeverTouchesTheGameOrQueuesWork() {
        ClientPrefs.setExperimentalVisionEnabled(false);
        CompletableFuture<JsonObject> result=new CompletableFuture<>();
        NpcCameraRenderer.acceptLocal(UUID.randomUUID(),result);
        assertTrue(result.isCompletedExceptionally());
        assertThrows(java.util.concurrent.CompletionException.class,result::join);
    }
}
