package ru.ruscrafting.nativegenerationlab;

import net.kyori.adventure.nbt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class Fixtures {
    record Fixture(String name, SchematicCodec.Schematic schematic, long decodeNanos, int expectedWrites) {}
    static List<Fixture> prepare(Path folder, int dataVersion) throws IOException {
        Files.createDirectories(folder);
        List<Fixture> fixtures = new ArrayList<>();
        for (int side : new int[]{32, 64}) for (boolean sparse : new boolean[]{false, true}) {
            String name = (sparse ? "sparse" : "dense") + side;
            Path file = folder.resolve("fixture-" + name + ".schem");
            CompoundBinaryTag generated = tag(side, sparse, side == 32 ? 2 : 3);
            if (side == 32) generated = generated.putInt("DataVersion", dataVersion);
            else generated = generated.put("Schematic", generated.getCompound("Schematic").putInt("DataVersion", dataVersion));
            BinaryTagIO.writer().write(generated, file, BinaryTagIO.Compression.GZIP);
            long start = System.nanoTime();
            SchematicCodec.Schematic decoded = SchematicCodec.read(file);
            fixtures.add(new Fixture(name, decoded, System.nanoTime() - start, sparse ? side * side * side / 8 : side * side * side));
        }
        return List.copyOf(fixtures);
    }
    static CompoundBinaryTag tag(int side, boolean sparse, int version) {
        byte[] data = new byte[side * side * side];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (sparse && i % 8 != 0 ? 0 : (i % 2) + 1);
        CompoundBinaryTag palette = CompoundBinaryTag.builder().putInt("minecraft:air", 0)
                .putInt("minecraft:stone", 1).putInt("minecraft:deepslate", 2).build();
        var builder = CompoundBinaryTag.builder().putInt("Version", version).putInt("DataVersion", 4671)
                .putShort("Width", (short) side).putShort("Height", (short) side).putShort("Length", (short) side)
                .putIntArray("Offset", new int[]{0, 0, 0});
        if (version == 2) return builder.put("Palette", palette).putInt("PaletteMax", 3).putByteArray("BlockData", data).build();
        return CompoundBinaryTag.builder().put("Schematic", builder.put("Blocks", CompoundBinaryTag.builder()
                .put("Palette", palette).putByteArray("Data", data).build()).build()).build();
    }
}
