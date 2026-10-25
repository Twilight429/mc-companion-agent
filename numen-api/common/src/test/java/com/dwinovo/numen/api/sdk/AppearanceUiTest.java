package com.dwinovo.numen.api.sdk;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceUiTest {
    @Test void onlyTheRealOwnerCanTargetTheCompanion() {
        UUID owner=UUID.randomUUID();
        assertTrue(AppearanceUi.owns(owner,owner));
        assertFalse(AppearanceUi.owns(UUID.randomUUID(),owner));
        assertFalse(AppearanceUi.owns(owner,null));
        assertFalse(AppearanceUi.owns(null,null));
    }
    @Test void requestsKeepTheTargetAndExactUnicodeModelAndTexture() {
        var sent=new AppearanceUi.Request(21,UUID.randomUUID(),"apply","DS鲸鱼娘flash.ysm","原色贴图");
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            AppearanceUi.Request.CODEC.encode(b,sent);
            assertEquals(sent,AppearanceUi.Request.CODEC.decode(b)); assertEquals(0,b.readableBytes());
        } finally { b.release(); }
    }
    @Test void repliesCorrelateSlowResponsesWithTheirOriginalScreenRequest() {
        var sent=new AppearanceUi.Reply(37,UUID.randomUUID(),"textures","{\"ok\":true,\"value\":[\"原色\",\"深海\"]}");
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            AppearanceUi.Reply.CODEC.encode(b,sent);
            assertEquals(sent,AppearanceUi.Reply.CODEC.decode(b)); assertEquals(0,b.readableBytes());
        } finally { b.release(); }
    }
}
