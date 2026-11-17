package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.FakeWorld;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Verdict;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("mc")
class DiscardPolicyTest {
    @BeforeAll static void boot() { assertTrue(FakeWorld.boot(), "Minecraft registry must be available"); }

    @Test void defaultsProtectWoodOresAndRefinedResourcesButAllowOtherItems() {
        for (var item : List.of(Items.OAK_LOG, Items.STRIPPED_OAK_WOOD, Items.CHERRY_PLANKS, Items.BAMBOO_BLOCK,
                Items.WARPED_STEM, Items.STICK, Items.OAK_STAIRS, Items.DIAMOND, Items.IRON_ORE,
                Items.DEEPSLATE_COPPER_ORE, Items.RAW_IRON, Items.GOLD_INGOT, Items.COAL,
                Items.NETHERITE_SCRAP, Items.REDSTONE, Items.QUARTZ, Items.EMERALD_BLOCK))
            assertTrue(DiscardPolicy.DEFAULT.protects(item), item.toString());
        for (var item : List.of(Items.DIRT, Items.COBBLESTONE, Items.ROTTEN_FLESH, Items.WOODEN_PICKAXE, Items.BREAD))
            assertFalse(DiscardPolicy.DEFAULT.protects(item), item.toString());
    }

    @Test void individualChoicesOverrideCategorySwitchesAndCanBeRemoved() {
        var policy = new DiscardPolicy(true, true, Set.of("minecraft:bread"), Set.of("minecraft:oak_log"));
        assertTrue(policy.protects(Items.BREAD));
        assertFalse(policy.protects(Items.OAK_LOG));
        assertTrue(policy.protects(Items.DIAMOND));
        var disabled = new DiscardPolicy(false, false, Set.of("minecraft:oak_log"), Set.of());
        assertTrue(disabled.protects(Items.OAK_LOG));
        assertFalse(disabled.protects(Items.DIAMOND));
        assertTrue(DiscardPolicy.DEFAULT.protects(Items.OAK_LOG));
    }

    private static Gate gate(Mode mode, DiscardPolicy policy, RuleSet owner, List<ConsentItem> grants) {
        return new Gate(Actor.NOBODY, Layers.policy(mode, FactoryRules.hard(), owner, FactoryRules.layer()),
                new PlacedBlocks(), Structures.NONE, false, grants, Hits.NONE, null, policy);
    }
    @Test void protectedItemsCannotBeDroppedEvenInBypassOrWithBroadAllowRules() {
        var allowAll = new RuleSet(List.of(), List.of(), List.of(Groups.parse("drop(*)")));
        for (Mode mode : List.of(Mode.ASK, Mode.BYPASS)) {
            var verdict = gate(mode, DiscardPolicy.DEFAULT, allowAll, List.of())
                    .judge(Action.drop(Items.DIAMOND), new FakeWorld());
            assertEquals(Verdict.Kind.DENY, verdict.kind());
            assertEquals("drop(kept_item)", verdict.rule().toString());
        }
    }
    @Test void ordinaryCleanupIsAllowedButExplicitAskDenyAndObserveStillWin() {
        var world = new FakeWorld();
        assertTrue(gate(Mode.ASK, DiscardPolicy.DEFAULT, RuleSet.EMPTY, List.of()).judge(Action.drop(Items.DIRT), world).allowed());
        var ask = new RuleSet(List.of(), List.of(Groups.parse("drop(*)")), List.of());
        assertTrue(gate(Mode.ASK, DiscardPolicy.DEFAULT, ask, List.of()).judge(Action.drop(Items.DIRT), world).asks());
        var deny = new RuleSet(List.of(Groups.parse("drop(*)")), List.of(), List.of());
        assertEquals(Verdict.Kind.DENY, gate(Mode.ASK, DiscardPolicy.DEFAULT, deny, List.of()).judge(Action.drop(Items.DIRT), world).kind());
        assertEquals(Verdict.Kind.DENY, gate(Mode.OBSERVE, DiscardPolicy.DEFAULT, RuleSet.EMPTY, List.of()).judge(Action.drop(Items.DIRT), world).kind());
        assertTrue(gate(Mode.ASK, new DiscardPolicy(true, true, Set.of(), Set.of("minecraft:diamond")), RuleSet.EMPTY,
                List.of()).judge(Action.drop(Items.DIAMOND), world).allowed());
    }

    @Test void perCompanionSettingsSurviveSaveLoadAndRulesResetWithoutLeaking() {
        var store = new PermissionStore();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var custom = new DiscardPolicy(false, true, Set.of("minecraft:bread"), Set.of("minecraft:diamond"));
        store.setDiscard(first, custom);
        store.reset();
        var loaded = PermissionStore.load(store.save(new CompoundTag(), null), null);
        assertEquals(custom.json(), loaded.discardOf(first).json());
        assertEquals(DiscardPolicy.DEFAULT.json(), loaded.discardOf(second).json());
        assertFalse(loaded.discardOf(first).protects(Items.DIAMOND));
        assertTrue(loaded.discardOf(second).protects(Items.DIAMOND));
        CompoundTag old = new CompoundTag(); old.put("modes", new CompoundTag());
        assertEquals(DiscardPolicy.DEFAULT.json(), PermissionStore.load(old, null).discardOf(first).json());
    }

    @Test void libraryAcceptsOnlyOwnersCompanionsAndValidRegisteredItems() {
        var store = new PermissionStore();
        UUID companion = UUID.randomUUID();
        var ground = new PermissionLibrary.Ground() {
            public PermissionStore store() { return store; }
            public boolean hardRules() { return true; }
            public Map<UUID, String> companions() { return Map.of(companion, "Test"); }
        };
        JsonObject body = new DiscardPolicy(true, true, Set.of("minecraft:bread"), Set.of()).json();
        body.addProperty("companion", companion.toString());
        assertNull(PermissionLibrary.write(ground, PermissionLibrary.DISCARD, body).problem());
        assertTrue(store.discardOf(companion).protects(Items.BREAD));
        var rows = PermissionLibrary.view(ground);
        assertTrue(java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .map(e -> e.getAsJsonObject()).anyMatch(o -> o.has("row") && "discard".equals(o.get("row").getAsString())));
        body.addProperty("companion", UUID.randomUUID().toString());
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.DISCARD, body).problem());
        body.addProperty("companion", companion.toString());
        body.getAsJsonArray("keep").add("minecraft:not_a_real_item");
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.DISCARD, body).problem());
        assertEquals(Set.of("minecraft:bread"), store.discardOf(companion).keep(), "invalid writes must be atomic");
        assertNotNull(PermissionLibrary.write(ground, PermissionLibrary.DISCARD, new JsonObject()).problem());
    }

    @Test void settingsRejectOverlapsMalformedIdsAndOversizedLists() {
        assertThrows(IllegalArgumentException.class, () -> new DiscardPolicy(true, true, Set.of("minecraft:dirt"), Set.of("minecraft:dirt")));
        assertThrows(IllegalArgumentException.class, () -> new DiscardPolicy(true, true, Set.of("drop(*)"), Set.of()));
        Set<String> tooMany = IntStream.range(0, DiscardPolicy.MAX_EXCEPTIONS + 1)
                .mapToObj(n -> "example:item" + n).collect(Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> new DiscardPolicy(true, true, tooMany, Set.of()));
        assertEquals(DiscardPolicy.DEFAULT.json(), DiscardPolicy.fromJson(DiscardPolicy.DEFAULT.json(), true).json());
    }
}
