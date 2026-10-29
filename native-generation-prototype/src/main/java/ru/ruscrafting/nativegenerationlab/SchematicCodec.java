package ru.ruscrafting.nativegenerationlab;

import net.kyori.adventure.nbt.*;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;

/** Pure bounded Sponge v2/v3 palette decoder. No Bukkit objects enter this worker. */
final class SchematicCodec {
    static final int MAX_VOLUME = 1_048_576, MAX_SIDE = 128, MAX_PALETTE = 4096;
    static final long MAX_FILE_BYTES = 8L << 20, MAX_NBT_BYTES = 16L << 20;

    record ChunkPos(int x, int z) {
        long key() { return ((long) x << 32) | (z & 0xffffffffL); }
    }
    static final class Schematic {
        final int width, height, length, dataVersion, offsetX, offsetY, offsetZ;
        final List<String> palette;
        private final int[] blocks;
        private final int[] placementPositions;
        Schematic(int w, int h, int l, int dataVersion, int[] offset, List<String> palette, int[] blocks,
                  int[] placementPositions) {
            width = w; height = h; length = l; this.dataVersion = dataVersion;
            offsetX = offset[0]; offsetY = offset[1]; offsetZ = offset[2];
            this.palette = Collections.unmodifiableList(new ArrayList<>(palette)); this.blocks = blocks.clone();
            this.placementPositions = placementPositions.clone();
        }
        int volume() { return blocks.length; }
        int block(int position) { return blocks[position]; }
        int placementCount() { return placementPositions.length; }
        int placementPosition(int cursor) { return placementPositions[cursor]; }
        int airCount() { return blocks.length - placementPositions.length; }
        int x(int position) { return position % width; }
        int z(int position) { return position / width % length; }
        int y(int position) { return position / (width * length); }
        List<ChunkPos> footprint(int anchorX, int anchorZ) {
            int minX = Math.addExact(anchorX, offsetX), minZ = Math.addExact(anchorZ, offsetZ);
            int maxX = Math.addExact(minX, width - 1), maxZ = Math.addExact(minZ, length - 1);
            List<ChunkPos> result = new ArrayList<>();
            for (int x = Math.floorDiv(minX, 16); x <= Math.floorDiv(maxX, 16); x++)
                for (int z = Math.floorDiv(minZ, 16); z <= Math.floorDiv(maxZ, 16); z++) result.add(new ChunkPos(x, z));
            return List.copyOf(result);
        }
    }

    static Schematic read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Compressed schematic exceeds 8 MiB");
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            return read(input);
        }
    }
    static Schematic read(InputStream input) throws IOException {
        return decode(BinaryTagIO.reader(MAX_NBT_BYTES).read(input, BinaryTagIO.Compression.GZIP));
    }
    static Schematic decode(CompoundBinaryTag root) throws IOException {
        CompoundBinaryTag tag = root.get("Schematic") instanceof CompoundBinaryTag nested ? nested : root;
        int version = tag.getInt("Version", -1);
        if (version != 2 && version != 3) throw new IOException("Only Sponge .schem versions 2 and 3 are supported");
        int dataVersion = tag.getInt("DataVersion", -1);
        if (dataVersion < 0) throw new IOException("DataVersion is required; automatic data conversion is unsupported");
        int w = dimension(tag, "Width"), h = dimension(tag, "Height"), l = dimension(tag, "Length");
        long volume = (long) w * h * l;
        if (volume > MAX_VOLUME) throw new IOException("Schematic volume exceeds " + MAX_VOLUME);
        int[] offset = tag.getIntArray("Offset", new int[]{0, 0, 0});
        if (offset.length != 3 || Arrays.stream(offset).anyMatch(v -> Math.abs((long) v) > 1_000_000))
            throw new IOException("Invalid or excessive Offset");
        rejectList(tag, "Entities"); rejectList(tag, "BlockEntities"); rejectList(tag, "TileEntities");
        if (tag.get("Biomes") != null || tag.get("BiomeData") != null)
            throw new IOException("Biome payloads are unsupported in this block-only prototype");
        CompoundBinaryTag blocks = version == 3 ? tag.getCompound("Blocks") : tag;
        rejectList(blocks, "BlockEntities");
        CompoundBinaryTag paletteTag = blocks.getCompound("Palette");
        if (paletteTag.size() == 0 || paletteTag.size() > MAX_PALETTE) throw new IOException("Missing or excessive block palette");
        String[] palette = new String[MAX_PALETTE];
        int highest = -1;
        for (var entry : paletteTag) {
            if (!(entry.getValue() instanceof IntBinaryTag number)) throw new IOException("Palette IDs must be integers");
            int id = number.value(); String state = entry.getKey();
            if (id < 0 || id >= MAX_PALETTE || palette[id] != null || state.isEmpty() || state.length() > 512)
                throw new IOException("Invalid palette ID or state");
            palette[id] = state; highest = Math.max(highest, id);
        }
        byte[] data = blocks.getByteArray(version == 3 ? "Data" : "BlockData");
        int[] indices = varints(data, (int) volume, palette);
        int[] placementPositions = compactNonAir(indices, palette);
        return new Schematic(w, h, l, dataVersion, offset, Arrays.asList(Arrays.copyOf(palette, highest + 1)),
                indices, placementPositions);
    }
    private static int[] compactNonAir(int[] blocks, String[] palette) throws InterruptedIOException {
        boolean[] air = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            String state = palette[i];
            if (state == null) continue;
            int properties = state.indexOf('[');
            String key = properties < 0 ? state : state.substring(0, properties);
            // Bukkit validates the entire palette later, including any malformed air properties.
            air[i] = switch (key) {
                case "air", "minecraft:air", "cave_air", "minecraft:cave_air", "void_air", "minecraft:void_air" -> true;
                default -> false;
            };
        }
        int[] positions = new int[blocks.length];
        int size = 0;
        for (int position = 0; position < blocks.length; position++) {
            if ((position & 4095) == 0 && Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Compaction cancelled");
            if (!air[blocks[position]]) positions[size++] = position;
        }
        return Arrays.copyOf(positions, size);
    }
    private static int dimension(CompoundBinaryTag tag, String key) throws IOException {
        if (!(tag.get(key) instanceof ShortBinaryTag value)) throw new IOException("Missing short dimension " + key);
        int result = value.value() & 0xffff;
        if (result < 1 || result > MAX_SIDE) throw new IOException("Dimension outside 1..128: " + key);
        return result;
    }
    private static void rejectList(CompoundBinaryTag tag, String key) throws IOException {
        BinaryTag value = tag.get(key);
        if (value != null && (!(value instanceof ListBinaryTag list) || list.size() != 0))
            throw new IOException(key + " is unsupported; entities and block entities are never silently discarded");
    }
    static int[] varints(byte[] data, int volume, String[] palette) throws IOException {
        if (data.length > (long) volume * 5) throw new IOException("Excess block data");
        int[] result = new int[volume]; int cursor = 0;
        for (int position = 0; position < volume; position++) {
            if ((position & 4095) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Decode cancelled");
            int value = 0; boolean ended = false;
            for (int shift = 0; shift <= 28; shift += 7) {
                if (cursor == data.length) throw new EOFException("Truncated block varint");
                int b = data[cursor++] & 255;
                if (shift == 28 && (b & 0xf0) != 0) throw new IOException("Block varint overflow");
                value |= (b & 127) << shift;
                if ((b & 128) == 0) { ended = true; break; }
            }
            if (!ended || value < 0 || value >= palette.length || palette[value] == null)
                throw new IOException("Unknown palette index or invalid varint");
            result[position] = value;
        }
        if (cursor != data.length) throw new IOException("Trailing block data");
        return result;
    }
}
