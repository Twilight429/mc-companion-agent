package com.dwinovo.numen.cooperation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CooperationCompletionTest {
    @Test void fullRealBlockCompletionClosesWithoutAnyLlmFinishCall() { assertEquals("completed",CooperationCompletion.phase(false,898,0,0,true,0,false)); }
    @Test void unknownOrWrongBlocksNeverCountAsCompletion() {
        assertEquals("active",CooperationCompletion.phase(false,898,0,1,true,0,false));
        assertEquals("active",CooperationCompletion.phase(false,898,1,0,true,0,false));
    }
    @Test void changedProjectionCannotBeAutomaticallyAccepted() { assertEquals("active",CooperationCompletion.phase(true,898,0,0,true,0,false)); }
    @Test void decorativeDataGoesToReviewInsteadOfAnotherGatheringLoop() { assertEquals("review",CooperationCompletion.phase(false,898,0,0,true,0,true)); }
    @Test void droppedSourceCellsAreNotSilentlyCalledCompleted() { assertEquals("review",CooperationCompletion.phase(false,898,0,0,true,4,false)); }
    @Test void finishingAnAgreedFoundationIsNotTheWholeBuilding() { assertEquals("completed",CooperationCompletion.phase(false,3,0,0,false,0,true)); }
    @Test void emptyWorkIsReviewNotAFalseCompletedHouse() { assertEquals("review",CooperationCompletion.phase(false,0,0,0,true,0,false)); }
}
