package com.magmaguy.betterstructures.lab;

import com.magmaguy.betterstructures.worldedit.Schematic;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

final class LabPlan {
    record Raw(LabManifest.Asset asset, Clipboard clipboard, LabGeometry geometry, String[] states, int[] indices,
               Map<String, Object> metadata, List<String> reasons) {}
    final LabManifest.Asset asset;
    final Clipboard clipboard;
    final LabGeometry geometry;
    final List<LabGeometry.ChunkSlice> slices;
    final Map<String, Object> metadata;
    private final int[] indices;
    private final BlockData[] palette;
    private final LabPolicy[] policies;
    private final BlockData pedestal;
    private final String[] canonical;
    private final String canonicalPedestal;

    private LabPlan(Raw raw, BlockData[] palette, BlockData pedestal) {
        asset = raw.asset; clipboard = raw.clipboard; geometry = raw.geometry; slices = geometry.slices();
        metadata = Map.copyOf(raw.metadata); indices = raw.indices.clone(); this.palette = palette.clone(); this.pedestal = pedestal;
        policies = Arrays.stream(raw.states).map(LabPolicy::forState).toArray(LabPolicy[]::new);
        canonical = Arrays.stream(palette).map(BlockData::getAsString).toArray(String[]::new);
        canonicalPedestal = pedestal.getAsString();
    }
    LabPolicy policy(int index) { return policies[indices[index]]; }
    BlockData target(int index) { return policy(index) == LabPolicy.PEDESTAL ? pedestal : palette[indices[index]]; }
    String canonicalTarget(int index) { return policy(index) == LabPolicy.PEDESTAL ? canonicalPedestal : canonical[indices[index]]; }
    BlockData expected(int index, BlockData before) {
        return policy(index).preserves(before.getMaterial().isAir(), before.getMaterial().isSolid()) ? before : target(index);
    }
    static Raw read(Path folder, LabManifest.Asset asset) throws Exception {
        try { return readVerified(folder, asset); }
        catch (InterruptedException cancelled) { throw cancelled; }
        catch (Exception failure) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("id", asset.id()); metadata.put("file", asset.file()); metadata.put("selected", asset.benchmark());
            metadata.put("expectedSha256", asset.sha256());
            return new Raw(asset, null, null, new String[0], new int[0], metadata,
                    new ArrayList<>(List.of("Preflight failed: " + failure.getMessage())));
        }
    }
    private static Raw readVerified(Path folder, LabManifest.Asset asset) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>(); List<String> reasons = new ArrayList<>();
        metadata.put("id", asset.id()); metadata.put("file", asset.file()); metadata.put("selected", asset.benchmark());
        Path file = LabManifest.assetPath(folder, asset.file());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        metadata.put("sha256", hash);
        if (!hash.equals(asset.sha256())) throw new IllegalArgumentException("Source SHA256 mismatch: " + asset.id());
        Path sidecar = Path.of(file.toString().substring(0, file.toString().length() - 6) + ".yml");
        if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sidecar)
                || Files.size(sidecar) > 65_536) throw new IllegalArgumentException("Matching regular sidecar required: " + asset.id());
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(sidecar.toFile());
        metadata.put("sidecarSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sidecar))));
        metadata.put("generatorConfigFilename", yaml.getString("generatorConfigFilename", ""));
        metadata.put("sidecarPedestalMaterial", yaml.getString("pedestalMaterial", ""));
        for (String key : List.of("treasureFile", "barrelTreasureFilename"))
            if (!yaml.getString(key, "").isBlank()) reasons.add("Unsupported sidecar " + key);
        if (asset.rotation() != 0) reasons.add("Non-identity WE transform unsupported in this first lab subset");
        Clipboard clipboard = Schematic.load(file.toFile());
        if (clipboard == null) throw new IllegalArgumentException("WorldEdit reader failed: " + asset.id());
        var dimensions = clipboard.getDimensions(); var minimum = clipboard.getMinimumPoint(); var origin = clipboard.getOrigin();
        LabGeometry geometry = new LabGeometry(Math.addExact(asset.x(), Math.subtractExact(minimum.x(), origin.x())),
                Math.addExact(asset.y(), Math.subtractExact(minimum.y(), origin.y())), Math.addExact(asset.z(), Math.subtractExact(minimum.z(), origin.z())),
                dimensions.x(), dimensions.y(), dimensions.z());
        metadata.put("dimensions", List.of(geometry.width(), geometry.height(), geometry.length()));
        metadata.put("clipboardMinimum", List.of(minimum.x(), minimum.y(), minimum.z()));
        metadata.put("clipboardOrigin", List.of(origin.x(), origin.y(), origin.z()));
        metadata.put("entities", clipboard.getEntities().size());
        if (!clipboard.getEntities().isEmpty()) reasons.add("Unsupported entities: " + clipboard.getEntities().size());
        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>(); int[] indices = new int[geometry.volume()];
        int nbt = 0; Map<String, Integer> counts = new TreeMap<>();
        for (int y = 0; y < geometry.height(); y++) for (int z = 0; z < geometry.length(); z++) for (int x = 0; x < geometry.width(); x++) {
            if ((x | z) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException("Preflight cancelled");
            var block = clipboard.getFullBlock(BlockVector3.at(minimum.x() + x, minimum.y() + y, minimum.z() + z));
            if (block.getNbt() != null && block.getNbt().value().keySet().stream().anyMatch(k -> !Set.of("x", "y", "z").contains(k))) nbt++;
            String state = block.toImmutableState().getAsString();
            int index = palette.computeIfAbsent(state, ignored -> palette.size());
            if (palette.size() > 4096) throw new IllegalArgumentException("Palette exceeds 4096 states");
            indices[geometry.index(x, y, z)] = index;
            counts.merge(LabPolicy.forState(state).name(), 1, Integer::sum);
        }
        if (nbt != 0) reasons.add("Unsupported block NBT: " + nbt);
        metadata.put("blocksWithNbt", nbt); metadata.put("policyCounts", counts); metadata.put("paletteStates", palette.size());
        metadata.put("propertyBearingPaletteStates", palette.keySet().stream().filter(state -> state.contains("[")).count());
        return new Raw(asset, clipboard, geometry, palette.keySet().toArray(String[]::new), indices, metadata, reasons);
    }
    static LabPlan prepare(Raw raw, BlockData pedestal) {
        String configuredPedestal = Objects.toString(raw.metadata.get("sidecarPedestalMaterial"), "");
        if (!configuredPedestal.isBlank() && !configuredPedestal.equals(pedestal.getMaterial().name()))
            raw.reasons.add("Sidecar pedestal material differs from the explicit constant A/B input");
        BlockData[] palette = new BlockData[raw.states.length];
        if (raw.reasons.isEmpty()) for (int i = 0; i < palette.length; i++) {
            try {
                palette[i] = Bukkit.createBlockData(raw.states[i]);
                if (palette[i].createBlockState() instanceof TileState)
                    raw.reasons.add("Unsupported block-entity material: " + raw.states[i]);
            } catch (IllegalArgumentException error) { raw.reasons.add("Unsupported block state: " + raw.states[i]); }
        }
        raw.metadata.put("supported", raw.reasons.isEmpty()); raw.metadata.put("reasons", List.copyOf(raw.reasons));
        return raw.reasons.isEmpty() ? new LabPlan(raw, palette, pedestal) : null;
    }
}
