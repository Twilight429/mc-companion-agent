package com.dwinovo.numen.plugins.sceneadapt;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.api.sdk.ApiTester;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 Numen 登记处核验扩展协议，不以自造的 API 桩代替宿主。 */
class PlayerApiTest {
    @TempDir Path world;

    @BeforeAll static void install() throws Exception {
        NumenApiFixture.install();
        Object scan = ApiRegistry.function("numen.scan.blocks");
        NumenSceneAdapt.install(Path.of(PlayerApiTest.class.getClassLoader()
                .getResource("plugins/sceneadapt/numen").toURI()));
        assertSame(scan, ApiRegistry.function("numen.scan.blocks"), "extension must not replace native scanning");
    }

    @Test void publicPluginDoorRegistersOnlyPlayerContextAndProposals() {
        assertNotNull(ApiRegistry.function("sceneadapt.player.context"));
        assertNotNull(ApiRegistry.function("sceneadapt.player.propose_memory"));
        assertNotNull(ApiRegistry.function("sceneadapt.player.propose_task"));
        assertEquals(3, ApiRegistry.group("sceneadapt.player").functions().size());
        assertNull(ApiRegistry.function("sceneadapt.player.accept"), "model must not confirm its own memory");
        assertNull(ApiRegistry.function("sceneadapt.player.teleport"));
    }

    @Test void functionExamplesAndBundledSkillAgreeWithTheActualRegistry() throws Exception {
        var issues = ApiTester.lint().stream().filter(l -> l.where().startsWith("sceneadapt.")).toList();
        assertTrue(issues.isEmpty(), issues.toString());
        Path skill = Path.of(PlayerApiTest.class.getClassLoader()
                .getResource("plugins/sceneadapt/numen/skills/sceneadapt/player_cooperation/SKILL.md").toURI());
        var docs = ApiTester.lint(java.util.List.of(new ApiTester.Text("sceneadapt skill", Files.readString(skill))));
        assertTrue(docs.isEmpty(), docs.toString());
    }

    @Test void modelContextSeparatesUnapprovedAndApprovedMemory() {
        UUID owner = UUID.randomUUID(), companion = UUID.randomUUID();
        PlayerStore store = new PlayerStore(world, owner);
        var proposal = store.proposeMemory(companion, "style", "quiet", "owner words");
        var pending = PlayerApi.context(store.view(companion, ""));
        assertTrue(pending.preferences().isEmpty());
        assertEquals(1, pending.pending().size());
        store.accept(proposal.id());
        var accepted = PlayerApi.context(store.view(companion, ""));
        assertEquals("quiet", accepted.preferences().getFirst().value());
        assertTrue(accepted.pending().isEmpty());
        assertEquals(owner.toString(), accepted.owner());
    }

    @Test void runtimeStateSerializesEmptyAndPresentDivisionWithoutReflectingIntoJdkOptional() {
        UUID owner = UUID.randomUUID(), companion = UUID.randomUUID();
        PlayerStore store = new PlayerStore(world, owner);
        assertTrue(NumenSceneAdapt.stateText(PlayerApi.context(store.view(companion, ""))).contains("sceneadapt_player_data"));
        store.accept(store.proposeContract(companion, "Shelter", "Build walls", "Bring planks").id());
        String state = NumenSceneAdapt.stateText(PlayerApi.context(store.view(companion, "")));
        assertTrue(state.contains("Bring planks"));
        assertTrue(state.contains("Shelter"));
    }
}
