package ru.ruscrafting.nativegenerationlab;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UpstreamPreparationCompletionTest {
    @Test void zeroTicketsDoNotCompleteWhilePreparationIsQueuedOrPending() {
        Map<String, Object> state = new HashMap<>(Map.of("enabled", true, "failed", false,
                "failures", 0L, "requestPending", false, "queued", 0, "held", 0, "activeJob", false));
        assertTrue(PreparationState.settled(state));
        state.put("activeJob", true);
        assertFalse(PreparationState.settled(state));
        state.put("activeJob", false);
        state.put("queued", 1);
        assertFalse(PreparationState.settled(state));
        state.put("queued", 0); state.put("requestPending", true);
        assertFalse(PreparationState.settled(state));
        state.put("requestPending", false); state.put("held", 1);
        assertFalse(PreparationState.settled(state));
        state.put("held", 0); state.put("failed", true);
        assertThrows(IllegalStateException.class, () -> PreparationState.settled(state));
        state.put("failed", false); state.put("failures", 1);
        assertThrows(IllegalStateException.class, () -> PreparationState.settled(state));
    }
}
