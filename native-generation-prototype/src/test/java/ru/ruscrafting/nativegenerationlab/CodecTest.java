package ru.ruscrafting.nativegenerationlab;

import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodecTest {
    @Test void bothVersionsDecodeTheirRealGzipFixturesAndOrdering() throws Exception {
        for (int version : new int[]{2, 3}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            BinaryTagIO.writer().write(Fixtures.tag(32, true, version), output, BinaryTagIO.Compression.GZIP);
            var result = SchematicCodec.read(new ByteArrayInputStream(output.toByteArray()));
            assertEquals(32768, result.volume()); assertEquals(1, result.block(0)); assertEquals(0, result.block(1));
            assertEquals(31, result.x(31)); assertEquals(1, result.z(32)); assertEquals(1, result.y(1024));
        }
    }
    @Test void rejectsEntitiesInsteadOfSilentlyLosingThem() {
        var tag = Fixtures.tag(1, false, 2).put("Entities", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND,
                List.of(CompoundBinaryTag.empty())));
        assertThrows(IOException.class, () -> SchematicCodec.decode(tag));
    }
    @Test void rejectsBlockEntitiesAndBiomePayloads() {
        var list = ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(CompoundBinaryTag.empty()));
        assertThrows(IOException.class, () -> SchematicCodec.decode(Fixtures.tag(1, false, 2).put("BlockEntities", list)));
        assertThrows(IOException.class, () -> SchematicCodec.decode(Fixtures.tag(1, false, 2).putByteArray("BiomeData", new byte[]{0})));
    }
    @Test void validatesDimensionsAndUnsignedShorts() {
        assertThrows(IOException.class, () -> SchematicCodec.decode(Fixtures.tag(1, false, 2).putShort("Width", (short) -1)));
        assertThrows(IOException.class, () -> SchematicCodec.decode(Fixtures.tag(1, false, 2).putShort("Height", (short) 0)));
        assertThrows(IOException.class, () -> SchematicCodec.decode(Fixtures.tag(1, false, 2)
                .putShort("Width", (short) 128).putShort("Height", (short) 128).putShort("Length", (short) 128)));
    }
    @Test void rejectsTruncatedOverflowUnknownAndTrailingVarints() {
        String[] palette = {"minecraft:air"};
        for (byte[] input : new byte[][]{{(byte) 128}, {(byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255}, {1}, {0, 0}})
            assertThrows(IOException.class, () -> SchematicCodec.varints(input, 1, palette));
    }
    @Test void acceptsSparsePaletteIdsAndMultibyteVarints() throws Exception {
        var tag = Fixtures.tag(1, false, 2).put("Palette", CompoundBinaryTag.builder().putInt("minecraft:stone", 128).build())
                .putByteArray("BlockData", new byte[]{(byte) 128, 1});
        var result = SchematicCodec.decode(tag);
        assertEquals(128, result.block(0)); assertEquals("minecraft:stone", result.palette.get(128));
        assertEquals(1, result.placementCount()); assertEquals(0, result.placementPosition(0));
    }
    @Test void compactionPreservesDenseAndSparseSpongeCoordinatesAcrossVersions() throws Exception {
        for (int version : new int[]{2, 3}) for (boolean sparse : new boolean[]{false, true}) {
            var result = SchematicCodec.decode(Fixtures.tag(32, sparse, version));
            int step = sparse ? 8 : 1;
            assertEquals(32768, result.volume()); // The full reset footprint remains available.
            assertEquals(32768 / step, result.placementCount());
            assertEquals(32768 - 32768 / step, result.airCount());
            for (int cursor = 0; cursor < result.placementCount(); cursor++) {
                int position = cursor * step;
                assertEquals(position, result.placementPosition(cursor));
                assertEquals((position % 2) + 1, result.block(position));
                assertEquals(position % 32, result.x(position));
                assertEquals(position / 32 % 32, result.z(position));
                assertEquals(position / 1024, result.y(position));
            }
        }
    }
    @Test void compactionSkipsOnlyTheThreeVanillaAirTypesIncludingDefaultNamespace() throws Exception {
        String[] states = {"air", "minecraft:air", "cave_air", "minecraft:cave_air", "void_air", "minecraft:void_air",
                "minecraft:structure_void", "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]"};
        var palette = CompoundBinaryTag.builder();
        for (int index = 0; index < states.length; index++) palette.putInt(states[index], index);
        var tag = Fixtures.tag(2, false, 2).put("Palette", palette.build())
                .putByteArray("BlockData", new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        var result = SchematicCodec.decode(tag);
        assertEquals(6, result.airCount()); assertEquals(2, result.placementCount());
        assertEquals(6, result.placementPosition(0)); assertEquals(7, result.placementPosition(1));
        assertEquals(states[7], result.palette.get(result.block(result.placementPosition(1))));
    }
    @Test void allAirHasNoPlacementWorkButKeepsFullResetVolume() throws Exception {
        var tag = Fixtures.tag(2, false, 2)
                .put("Palette", CompoundBinaryTag.builder().putInt("minecraft:air", 0).build())
                .putByteArray("BlockData", new byte[8]);
        var result = SchematicCodec.decode(tag);
        assertEquals(0, result.placementCount()); assertEquals(8, result.airCount()); assertEquals(8, result.volume());
        for (int position = 0; position < result.volume(); position++) assertEquals(0, result.block(position));
    }
    @Test void negativeCoordinatesAndOffsetsPrepareEntireFootprint() throws Exception {
        var tag = Fixtures.tag(32, false, 2).putIntArray("Offset", new int[]{-1, 0, -1});
        var result = SchematicCodec.decode(tag);
        var footprint = result.footprint(0, 0);
        assertEquals(9, footprint.size());
        assertTrue(footprint.contains(new SchematicCodec.ChunkPos(-1, -1)));
        assertTrue(footprint.contains(new SchematicCodec.ChunkPos(1, 1)));
        assertThrows(ArithmeticException.class, () -> result.footprint(Integer.MIN_VALUE, 0));
    }
    @Test void compressedNbtAllocationIsBounded() throws Exception {
        var tag = CompoundBinaryTag.builder().putByteArray("excess", new byte[(int) SchematicCodec.MAX_NBT_BYTES + 1]).build();
        var output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(tag, output, BinaryTagIO.Compression.GZIP);
        assertThrows(IOException.class, () -> SchematicCodec.read(new ByteArrayInputStream(output.toByteArray())));
    }
}
