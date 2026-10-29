package ru.ruscrafting.nativegenerationlab;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryTest {
    @Test void verificationBudgetBoundsTimeAndSkippedPositionIterations() {
        AtomicLong time = new AtomicLong();
        SliceBudget budget = new SliceBudget(time::get, 100, 4);
        assertTrue(budget.next()); assertTrue(budget.next());
        time.set(100); assertFalse(budget.next()); assertEquals(2, budget.used());
        budget = new SliceBudget(() -> 0, 100, 4);
        for (int i = 0; i < 4; i++) assertTrue(budget.next());
        assertFalse(budget.next());
    }
    @Test void cancelledCompletionsCannotEnterTheNextRun() {
        RunFence fence = new RunFence(); long old = fence.begin();
        assertTrue(fence.accepts(old)); fence.cancel(); assertFalse(fence.accepts(old));
        long current = fence.begin(); assertFalse(fence.accepts(old)); assertTrue(fence.accepts(current));
    }
    @Test void strictLabBoundaryRejectsAnyOtherListenerOrWorld() {
        assertDoesNotThrow(() -> LabGuard.server("127.0.0.1", 54295));
        assertThrows(IllegalStateException.class, () -> LabGuard.server("0.0.0.0", 54295));
        assertThrows(IllegalStateException.class, () -> LabGuard.server("127.0.0.1", 25565));
        assertThrows(IllegalArgumentException.class, () -> LabGuard.world("survival"));
        assertDoesNotThrow(() -> LabGuard.world("rc_native_generation_lab"));
    }
    @Test void verificationHashDetectsWrongStateAndWrongCoordinate() {
        BlockHash expected = new BlockHash(), matching = new BlockHash(), wrongState = new BlockHash(), wrongPosition = new BlockHash();
        expected.add(-1, 200, 17, "minecraft:stone"); matching.add(-1, 200, 17, "minecraft:stone");
        wrongState.add(-1, 200, 17, "minecraft:air"); wrongPosition.add(0, 200, 17, "minecraft:stone");
        assertEquals(expected.hex(), matching.hex()); assertNotEquals(expected.hex(), wrongState.hex());
        assertNotEquals(expected.hex(), wrongPosition.hex());
    }
}
