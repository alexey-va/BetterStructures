package ru.ruscrafting.nativegenerationlab;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationPlanTest {
    @Test void crossChunkPartitionIsCompleteWithoutDuplicatesRegardlessOfChunkOrder() throws Exception {
        for (int side : new int[]{32, 64}) for (boolean sparse : new boolean[]{false, true}) {
            var schematic = SchematicCodec.decode(Fixtures.tag(side, sparse, side == 32 ? 2 : 3));
            var plan = new GenerationPlan("fixture", schematic, -17, 200, -33);
            var shuffled = new ArrayList<>(plan.slices); Collections.shuffle(shuffled, new Random(37));
            BitSet visited = new BitSet(schematic.volume());
            int air = 0, placements = 0;
            for (var slice : shuffled) {
                air += slice.skippedAir;
                for (int cursor = 0; cursor < slice.size(); cursor++) {
                    int position = slice.position(cursor);
                    assertFalse(visited.get(position), "A block may not belong to two population callbacks");
                    visited.set(position); placements++;
                    int expectedX = -17 + position % side;
                    int expectedZ = -33 + (position / side) % side;
                    int expectedY = 200 + position / (side * side);
                    assertEquals(expectedX, plan.worldX(position)); assertEquals(expectedY, plan.worldY(position));
                    assertEquals(expectedZ, plan.worldZ(position));
                    assertEquals(slice.chunkX, Math.floorDiv(expectedX, 16));
                    assertEquals(slice.chunkZ, Math.floorDiv(expectedZ, 16));
                    assertNotEquals(0, schematic.block(position));
                }
            }
            int expected = side * side * side / (sparse ? 8 : 1);
            assertEquals(expected, placements); assertEquals(expected, plan.expectedWrites());
            assertEquals(schematic.volume() - expected, air); assertEquals(air, plan.expectedSkippedAir());
            // Independent full-volume oracle also checks that no non-air position was omitted.
            for (int position = 0; position < schematic.volume(); position++)
                assertEquals(schematic.block(position) != 0, visited.get(position));
        }
    }
    @Test void offsetsAndUnalignedBoundariesDoNotShiftTheStructure() throws Exception {
        var schematic = SchematicCodec.decode(Fixtures.tag(32, false, 2).putIntArray("Offset", new int[]{-2, 7, 3}));
        var plan = new GenerationPlan("offset", schematic, 1, 200, -17);
        assertEquals(-1, plan.worldX(0)); assertEquals(207, plan.worldY(0)); assertEquals(-14, plan.worldZ(0));
        assertEquals(9, plan.slices.size());
        assertEquals(32768, plan.slices.stream().mapToInt(GenerationPlan.Slice::size).sum());
        assertEquals(0, plan.slices.stream().mapToInt(slice -> slice.skippedAir).sum());
    }
    @Test void emptyAndControlChunksRemainInTheRequiredPopulationReceiptFootprint() throws Exception {
        var tag = Fixtures.tag(32, false, 2)
                .put("Palette", CompoundBinaryTag.builder().putInt("minecraft:air", 0).build())
                .putByteArray("BlockData", new byte[32768]);
        var plan = new GenerationPlan("empty", SchematicCodec.decode(tag), 0, 200, 0);
        assertEquals(4, plan.slices.size()); assertEquals(0, plan.expectedWrites());
        assertEquals(32768, plan.slices.stream().mapToInt(slice -> slice.skippedAir).sum());
        assertTrue(plan.slices.stream().allMatch(slice -> slice.size() == 0));
        var control = GenerationPlan.control(-64, -64);
        assertTrue(control.control()); assertEquals(16, control.slices.size());
        assertTrue(control.slices.stream().allMatch(slice -> slice.size() == 0 && slice.skippedAir == 0));
        assertEquals(16, control.slices.stream().map(GenerationPlan.Slice::key).distinct().count());
    }
    @Test void coordinateOverflowAndUnsupportedBoundsAreRejectedBeforeGeneration() throws Exception {
        var schematic = SchematicCodec.decode(Fixtures.tag(32, false, 2));
        assertThrows(ArithmeticException.class, () -> new GenerationPlan("overflow", schematic, Integer.MAX_VALUE, 200, 0));
        assertThrows(ArithmeticException.class, () -> new GenerationPlan("overflowY", schematic, 0, Integer.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> new GenerationPlan("outside", schematic, 29_999_001, 200, 0));
        assertThrows(IllegalArgumentException.class, () -> GenerationPlan.control(1, 0));
    }
}
