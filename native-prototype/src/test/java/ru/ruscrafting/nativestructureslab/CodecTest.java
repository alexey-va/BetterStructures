package ru.ruscrafting.nativestructureslab;

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
