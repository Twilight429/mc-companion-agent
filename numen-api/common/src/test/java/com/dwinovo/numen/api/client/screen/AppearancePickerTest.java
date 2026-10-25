package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.sdk.AppearanceUi;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AppearancePickerTest {
    private final UUID npc=UUID.randomUUID();
    private final AtomicLong time=new AtomicLong();
    private final List<AppearanceUi.Request> sent=new ArrayList<>();
    private final AppearancePicker picker=new AppearancePicker(npc,()->{},()->true,()->true,time::get,sent::add);
    private void answer(String json) {
        var r=sent.getLast(); AppearanceUi.Reply.onClient.accept(new AppearanceUi.Reply(r.serial(),r.companion(),r.action(),json));
    }
    private void load() {
        picker.refresh();
        answer("{\"ok\":true,\"value\":{\"models\":[\"alex\",\"鲸鱼娘\"],\"textures\":[\"原色\"],\"current_model\":\"alex\",\"current_texture\":\"原色\"}}");
    }
    private void chooseWhale() {
        load(); picker.chooseModel(1); time.set(150); picker.tick();
        answer("{\"ok\":true,\"value\":[\"海蓝\",\"白色\"]}");
    }
    @Test void selectingModelsOnlyQueriesAndNeverChangesTheNpcBeforeSave() {
        chooseWhale(); picker.chooseTexture(1);
        assertEquals(List.of("options","textures"),sent.stream().map(AppearanceUi.Request::action).toList());
        assertTrue(picker.dirty()); assertTrue(picker.canSave());
    }
    @Test void saveTargetsTheExactNpcAndWaitsForRealReadbackBeforeCompleting() {
        chooseWhale(); picker.chooseTexture(1); AtomicInteger committed=new AtomicInteger();
        picker.save(committed::incrementAndGet); assertEquals(0,committed.get());
        time.set(300); picker.tick(); var r=sent.getLast();
        assertEquals("apply",r.action()); assertEquals(npc,r.companion());
        assertEquals("鲸鱼娘",r.model()); assertEquals("白色",r.texture());
        answer("{\"ok\":true}"); assertEquals(1,committed.get()); assertFalse(picker.dirty());
    }
    @Test void cancelDiscardsTheDraftWithoutAnyApplyCall() {
        chooseWhale(); picker.close(); time.set(9999); picker.tick();
        assertEquals(List.of("options","textures"),sent.stream().map(AppearanceUi.Request::action).toList());
    }
    @Test void aFailureDoesNotCloseTheEditorOrCommitOtherSettings() {
        chooseWhale(); AtomicInteger committed=new AtomicInteger(); picker.save(committed::incrementAndGet);
        time.set(300); picker.tick(); answer("{\"ok\":false,\"error\":{\"kind\":\"denied\",\"message\":\"YSM 授权不足\"}}");
        assertEquals(0,committed.get()); assertTrue(picker.dirty()); assertFalse(picker.busy());
        assertEquals("YSM 授权不足",picker.message());
    }
    @Test void oldOrOtherNpcRepliesCannotOverwriteCurrentSelection() {
        load(); picker.chooseModel(1); time.set(150); picker.tick(); var r=sent.getLast();
        AppearanceUi.Reply.onClient.accept(new AppearanceUi.Reply(r.serial()-1,npc,"textures","{\"ok\":true,\"value\":[\"过期\"]}"));
        AppearanceUi.Reply.onClient.accept(new AppearanceUi.Reply(r.serial(),UUID.randomUUID(),"textures","{\"ok\":true,\"value\":[\"他人\"]}"));
        assertTrue(picker.busy()); answer("{\"ok\":true,\"value\":[\"海蓝\"]}");
        assertEquals(List.of("海蓝"),picker.textureLabels());
    }
    @Test void timeoutAllowsManualRefreshWithoutAutomaticallyRetryingAChange() {
        picker.refresh(); time.set(15001); picker.tick();
        assertFalse(picker.busy()); assertEquals(1,sent.size());
        picker.refresh(); assertEquals(2,sent.size()); assertEquals("options",sent.getLast().action());
    }
    @Test void anUnsupportedAppearanceInterfaceDoesNotPreventSavingUnrelatedSettings() {
        var unsupported=new AppearancePicker(npc,()->{},()->true,()->false,time::get,sent::add);
        unsupported.refresh(); AtomicInteger saved=new AtomicInteger(); unsupported.save(saved::incrementAndGet);
        assertTrue(sent.isEmpty()); assertEquals(1,saved.get());
    }
}
