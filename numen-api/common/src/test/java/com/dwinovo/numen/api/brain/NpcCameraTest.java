package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.network.Fragments;
import com.dwinovo.numen.api.network.Wire;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mc")
class NpcCameraTest {
    private static byte[] png(int size) {
        byte[] data = new byte[size];
        ByteBuffer.wrap(data).putLong(0x89504e470d0a1a0aL).putInt(13).putInt(0x49484452)
                .putInt(NpcCamera.WIDTH).putInt(NpcCamera.HEIGHT);
        return data;
    }

    @Test void pngHeaderMustMatchTheBoundedCameraResolution() {
        assertTrue(NpcCamera.validPng(png(33)));
        assertFalse(NpcCamera.validPng(new byte[40]));
        assertFalse(NpcCamera.validPng(new byte[5]));
        byte[] wrong = png(33);
        ByteBuffer.wrap(wrong).putInt(16,16000);
        assertFalse(NpcCamera.validPng(wrong));
        assertFalse(NpcCamera.validPng(new byte[NpcCamera.MAX_BYTES+1]));
    }

    @Test void largeFramesCrossTheExistingFragmentProtocolWithTheirIdentityAndPose() {
        UUID nonce=UUID.randomUUID(), npc=UUID.randomUUID();
        var original=new NpcCamera.Reply(nonce,npc,"minecraft:overworld",10,65.62,20,90,12,png(90_000),"");
        var received=Fragments.crossed(Wire.TO_SERVER,NpcCamera.Reply.CODEC,original);
        assertEquals(nonce,received.id());
        assertEquals(npc,received.companion());
        assertEquals("minecraft:overworld",received.dimension());
        assertEquals(65.62,received.y());
        assertEquals(90,received.yaw());
        assertArrayEquals(original.png(),received.png());
        assertTrue(NpcCamera.validPng(received.png()));
    }

    @Test void missingCameraHasNoImageAndOversizedFramesCannotBeQueued() {
        var request=new NpcCamera.Request(UUID.randomUUID(),UUID.randomUUID(),"minecraft:the_nether");
        var missing=NpcCamera.Reply.missing(request,"unloaded");
        assertEquals(0,missing.png().length);
        assertEquals("unloaded",missing.problem());
        assertEquals(request.companion(),missing.companion());
        assertThrows(IllegalArgumentException.class,() -> new NpcCamera.Reply(request.id(),request.companion(),
                request.dimension(),0,0,0,0,0,new byte[NpcCamera.MAX_BYTES+1],""));
    }
}
