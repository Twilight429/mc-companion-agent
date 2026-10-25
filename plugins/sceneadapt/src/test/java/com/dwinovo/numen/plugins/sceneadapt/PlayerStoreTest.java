package com.dwinovo.numen.plugins.sceneadapt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStoreTest {
    @TempDir Path world;
    private final UUID owner = UUID.randomUUID();
    private final UUID npc = UUID.randomUUID();

    @Test void emptyStateNeedsNoTrainingOrFile() {
        PlayerStore store = new PlayerStore(world, owner);
        assertEquals(0, store.view(npc, "").revision());
        assertEquals(0.5, store.view(npc, "").feedback().posteriorMean());
        assertTrue(store.view(npc, "").preferences().isEmpty());
        assertFalse(Files.exists(world.resolve("sceneadapt")));
    }

    @Test void proposedMemoryIsNotConfirmedUntilOwnerAccepts() {
        PlayerStore store = new PlayerStore(world, owner);
        PlayerStore.Proposal p = store.proposeMemory(npc, "采矿方式", "让我先发现矿石", "玩家说让我先发现矿石");
        assertTrue(store.view(npc, "").preferences().isEmpty());
        assertEquals(1, store.view(npc, "").pending().size());
        store.accept(p.id());
        assertEquals("owner_confirmed", store.view(npc, "").preferences().getFirst().source());
        assertTrue(store.view(npc, "").pending().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.accept(p.id()));
    }

    @Test void rejectDoesNotCreateMemory() {
        PlayerStore store = new PlayerStore(world, owner);
        PlayerStore.Proposal p = store.proposeMemory(npc, "mining", "mine alone", "owner words");
        store.reject(p.id());
        assertTrue(store.view(npc, "").preferences().isEmpty());
        assertTrue(store.view(npc, "").pending().isEmpty());
    }

    @Test void explicitUpdateInvalidatesOlderProposal() {
        PlayerStore store = new PlayerStore(world, owner);
        PlayerStore.Proposal p = store.proposeMemory(npc, "mining", "mine alone", "owner words");
        store.remember("mining", "follow player");
        assertThrows(IllegalArgumentException.class, () -> store.accept(p.id()));
        assertEquals("follow player", store.view(npc, "").preferences().getFirst().value());
    }

    @Test void forgettingAlsoRemovesProposalsForThatFact() {
        PlayerStore store = new PlayerStore(world, owner);
        store.remember("mining", "follow");
        PlayerStore.Proposal p = store.proposeMemory(npc, "mining", "gather", "owner words");
        store.forget("mining");
        assertThrows(IllegalArgumentException.class, () -> store.accept(p.id()));
        assertTrue(store.view(npc, "").preferences().isEmpty());
    }

    @Test void repeatedIdenticalProposalIsDeduplicated() {
        PlayerStore store = new PlayerStore(world, owner);
        PlayerStore.Proposal first = store.proposeMemory(npc, "style", "quiet", "owner words");
        long revision = store.view(npc, "").revision();
        assertEquals(first.id(), store.proposeMemory(npc, "style", "quiet", "owner words").id());
        assertEquals(revision, store.view(npc, "").revision());
    }

    @Test void conflictingConfirmationsCannotOverwriteTheWinner() {
        PlayerStore store = new PlayerStore(world, owner);
        PlayerStore.Proposal first = store.proposeMemory(npc, "style", "quiet", "owner words");
        PlayerStore.Proposal second = store.proposeMemory(UUID.randomUUID(), "style", "talkative", "owner words");
        store.accept(first.id());
        assertThrows(IllegalArgumentException.class, () -> store.accept(second.id()));
        assertEquals("quiet", store.view(npc, "").preferences().getFirst().value());
    }

    @Test void contractsArePerCompanionAndRequireApproval() {
        PlayerStore store = new PlayerStore(world, owner);
        UUID second = UUID.randomUUID();
        PlayerStore.Proposal p = store.proposeContract(npc, "Build shelter", "Build walls", "Bring wood only");
        assertNull(store.view(npc, "").contract());
        store.accept(p.id());
        assertEquals("Bring wood only", store.view(npc, "").contract().npcRole());
        assertNull(store.view(second, "").contract());
        store.finishContract(npc);
        assertNull(store.view(npc, "").contract());
    }

    @Test void persistenceSurvivesNewStoreAndKeepsFeedbackSeparate() {
        PlayerStore store = new PlayerStore(world, owner);
        store.remember("style", "quiet");
        store.feedback(true); store.feedback(false); store.feedback(true);
        store.accept(store.proposeContract(npc, "Mine together", "Lead", "Follow").id());
        PlayerStore.View loaded = new PlayerStore(world, owner).view(npc, "");
        assertEquals("quiet", loaded.preferences().getFirst().value());
        assertEquals("Follow", loaded.contract().npcRole());
        assertEquals(4.0 / 7, loaded.feedback().posteriorMean());
    }

    @Test void ownersAndWorldsAreIsolated() {
        PlayerStore first = new PlayerStore(world, owner);
        first.remember("style", "quiet");
        assertTrue(new PlayerStore(world, UUID.randomUUID()).view(npc, "").preferences().isEmpty());
        assertTrue(new PlayerStore(world.resolve("other-world"), owner).view(npc, "").preferences().isEmpty());
    }

    @Test void relevantMemoryIsOrderedWithoutDiscardingOtherPreferences() {
        PlayerStore store = new PlayerStore(world, owner);
        store.remember("mining", "let player find ore");
        store.remember("building", "bring stone");
        assertEquals("mining", store.view(npc, "ore").preferences().getFirst().key());
        assertEquals(2, store.view(npc, "ore").preferences().size());
    }

    @Test void credentialsAndControlCharactersAreRejectedBeforePersistence() {
        PlayerStore store = new PlayerStore(world, owner);
        assertThrows(IllegalArgumentException.class, () -> store.remember("key", "sk-abcdefghijklmnop"));
        assertThrows(IllegalArgumentException.class, () -> store.remember("key", "api_key=secret-value"));
        assertThrows(IllegalArgumentException.class, () -> store.remember("key", "line\nline"));
        assertThrows(IllegalArgumentException.class, () -> store.remember("../owner", "value"));
        assertEquals(0, store.view(npc, "").revision());
    }

    @Test void limitsRejectWithoutEvictingConfirmedMemory() {
        PlayerStore store = new PlayerStore(world, owner);
        for (int i = 0; i < 64; i++) store.remember("key" + i, "value");
        assertThrows(IllegalArgumentException.class, () -> store.remember("extra", "value"));
        store.remember("key0", "updated");
        assertEquals(64, store.view(npc, "").preferences().size());
        for (int i = 0; i < 16; i++) store.proposeMemory(npc, "p" + i, "value", "owner words");
        assertThrows(IllegalArgumentException.class, () -> store.proposeMemory(npc, "extra", "value", "owner words"));
    }

    @Test void clearRevokesAllExtensionDataButDoesNotTouchNativeNotes() throws Exception {
        Path nativeNote = world.resolve("numen-native-note.txt");
        Files.writeString(nativeNote, "native memory");
        PlayerStore store = new PlayerStore(world, owner);
        store.remember("style", "quiet"); store.feedback(false);
        store.proposeMemory(npc, "style", "talkative", "owner words");
        store.accept(store.proposeContract(npc, "build", "walls", "wood").id());
        store.clear();
        PlayerStore.View view = new PlayerStore(world, owner).view(npc, "");
        assertTrue(view.preferences().isEmpty()); assertTrue(view.pending().isEmpty()); assertNull(view.contract());
        assertEquals(0.5, view.feedback().posteriorMean());
        assertEquals("native memory", Files.readString(nativeNote));
    }

    @Test void corruptOrWrongOwnerDocumentDoesNotGetOverwritten() throws Exception {
        PlayerStore store = new PlayerStore(world, owner);
        store.remember("style", "quiet");
        Path file = world.resolve("sceneadapt").resolve(owner + ".json");
        String corrupt = Files.readString(file).replace(owner.toString(), UUID.randomUUID().toString());
        Files.writeString(file, corrupt);
        assertThrows(IllegalArgumentException.class, () -> new PlayerStore(world, owner));
        assertEquals(corrupt, Files.readString(file));
    }

    @Test void persistenceFailureDoesNotPublishAnInMemorySuccess() throws Exception {
        PlayerStore store = new PlayerStore(world, owner);
        Path target = world.resolve("sceneadapt").resolve(owner + ".json");
        Files.createDirectories(target);
        Files.writeString(target.resolve("sentinel"), "preserve");
        assertThrows(java.io.UncheckedIOException.class, () -> store.remember("style", "quiet"));
        assertEquals(0, store.view(npc, "").revision());
        assertTrue(store.view(npc, "").preferences().isEmpty());
        assertEquals("preserve", Files.readString(target.resolve("sentinel")));
    }

    @Test void malformedJsonIsReportedAndPreserved() throws Exception {
        PlayerStore store = new PlayerStore(world, owner);
        store.remember("style", "quiet");
        Path target = world.resolve("sceneadapt").resolve(owner + ".json");
        Files.writeString(target, "{ broken");
        assertThrows(IllegalArgumentException.class, () -> new PlayerStore(world, owner));
        assertEquals("{ broken", Files.readString(target));
    }
}
