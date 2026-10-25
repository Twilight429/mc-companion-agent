package com.dwinovo.numen.cooperation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CooperationRolesTest {
    private CooperationRoles.Evidence evidence(boolean collected,boolean built,boolean mining,boolean empty,boolean shortOf,int near,boolean complete) {
        return new CooperationRoles.Evidence(collected,built,mining,empty,shortOf,near,complete);
    }
    @Test void emptyStockLetsBothCollect() {
        var p=CooperationRoles.player("auto",evidence(false,false,false,true,true,0,false));
        assertEquals("collector",p.role());
        assertEquals("collector",CooperationRoles.companion("auto",p,true,true,false).role());
    }
    @Test void manualModeAlwaysWinsEvenAgainstCompletionOrStrongEvidence() {
        var e=evidence(true,true,true,true,true,200,true);
        for(var mode:CooperationRoles.MODES) if(!mode.equals("auto")) {
            assertEquals(mode,CooperationRoles.player(mode,e).role());
            assertEquals(mode,CooperationRoles.companion(mode,CooperationRoles.player("auto",e),true,true,true).role());
        }
    }
    @Test void playerCanCollectAndBuild() {
        assertEquals("both",CooperationRoles.player("auto",evidence(true,true,false,false,true,0,false)).role());
    }
    @Test void proximityIsExplicitlyWeakNotConfirmedIntent() {
        var role=CooperationRoles.player("auto",evidence(false,false,false,false,false,120,false));
        assertEquals("builder",role.role()); assertTrue(role.confidence()<.5);
        assertTrue(role.evidence().contains("not_confirmed"));
    }
    @Test void recentMiningOverridesProximity() {
        assertEquals("collector",CooperationRoles.player("auto",evidence(false,false,true,false,true,200,false)).role());
    }
    @Test void companionReplenishesWhilePlayerBuilds() {
        var player=CooperationRoles.player("builder",evidence(false,false,false,false,true,0,false));
        assertEquals("collector",CooperationRoles.companion("auto",player,false,true,false).role());
    }
    @Test void roleChangesAreNotMutuallyExclusive() {
        var player=CooperationRoles.player("builder",evidence(false,false,false,false,false,0,false));
        assertEquals("both",CooperationRoles.companion("auto",player,false,false,false).role());
    }
    @Test void shortfallKeepsPlayerAndChestDistinctButNeverNegative() {
        assertEquals(6,CooperationRoles.shortfall(20,4,2,8));
        assertEquals(0,CooperationRoles.shortfall(3,4,2,8));
        assertEquals(0,CooperationRoles.shortfall(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->CooperationRoles.shortfall(1,-1,0,0));
    }
    @Test void unknownRolesAndCombatGesturesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->CooperationRoles.mode("fighter"));
        assertThrows(IllegalArgumentException.class,()->SocialInteraction.kind("attack"));
        assertEquals("high_five",SocialInteraction.kind("high_five"));
    }
    @Test void plannedRolesAreNotLocksAndYieldToObservedPlayerAction() {
        var weak=CooperationRoles.player("auto",evidence(false,false,false,false,true,0,false));
        assertEquals("collector",CooperationRoles.assigned("auto","collector",weak,false).role());
        var building=CooperationRoles.player("auto",evidence(false,true,false,false,true,0,false));
        assertEquals("builder",CooperationRoles.assigned("auto","collector",building,false).role());
        var locked=CooperationRoles.player("idle",evidence(false,true,false,false,true,0,false));
        assertEquals("idle",CooperationRoles.assigned("idle","collector",locked,false).role());
    }
}
