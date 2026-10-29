package com.magmaguy.betterstructures.lab;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LabPolicyTest {
    @Test void airCarvesSolidsButPreservesAllExistingAirVariants() {
        for (String source : new String[]{"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}) {
            LabPolicy policy = LabPolicy.forState(source);
            assertFalse(policy.preserves(false, true));
            assertTrue(policy.preserves(true, false));
        }
    }
    @Test void barrierAlwaysPreservesDestination() {
        for (boolean air : new boolean[]{true, false}) for (boolean solid : new boolean[]{true, false})
            assertTrue(LabPolicy.forState("minecraft:barrier").preserves(air, solid));
    }
    @Test void bedrockFillsOnlyNonSolidDestinationUsingTheExplicitPedestal() {
        var policy = LabPolicy.forState("minecraft:bedrock");
        assertEquals(LabPolicy.PEDESTAL, policy);
        assertTrue(policy.preserves(false, true));
        assertFalse(policy.preserves(true, false));
        assertFalse(policy.preserves(false, false));
    }
    @Test void ordinaryPropertyBearingBlocksAreNotMistakenForAirOrMarkers() {
        var policy = LabPolicy.forState("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        assertEquals(LabPolicy.BLOCK, policy);
        assertFalse(policy.preserves(false, true)); assertFalse(policy.preserves(true, false));
    }
}
