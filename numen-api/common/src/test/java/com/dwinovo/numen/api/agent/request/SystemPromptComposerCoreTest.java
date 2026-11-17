package com.dwinovo.numen.api.agent.request;

import com.dwinovo.numen.api.agent.prompt.NumenPrompts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemPromptComposerCoreTest {
    @Test void request_composer_reads_the_current_module_instructions() {
        String actual=SystemPromptComposer.operatingInstructions();
        assertEquals(NumenPrompts.ENTITY_PROMPT,actual);
        assertTrue(actual.contains("If explore is listed"));
        assertFalse(actual.contains("through it with one tool"));
    }
}
