package com.magmaguy.betterstructures.lab;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

record LabManifest(Engine engine, long seed, String pedestalMaterial, boolean profileSpark, boolean warmEntityAdapters, List<Asset> assets) {
    enum Engine { DEFAULT, NATIVE_GENERATION }
    record Asset(String id, String file, String sha256, boolean benchmark, int x, int y, int z, int rotation) {}
    static LabManifest load(Path file) throws Exception {
        if (Files.size(file) > 65_536) throw new IOException("native-lab.yml exceeds 64 KiB");
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
        if (!yaml.getBoolean("enabled", false)) return null;
        if (!"CLIPBOARD_BLOCK_PHASE".equals(yaml.getString("scope")))
            throw new IllegalArgumentException("scope must explicitly be CLIPBOARD_BLOCK_PHASE; automatic fitting/finishing is unsupported");
        Engine engine = Engine.valueOf(yaml.getString("engine", "DEFAULT"));
        List<Asset> assets = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (Map<?, ?> entry : yaml.getMapList("assets")) {
            String id = Objects.toString(entry.get("id"), ""), path = Objects.toString(entry.get("file"), "");
            String hash = Objects.toString(entry.get("sha256"), "");
            if (!id.matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(id) || !hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Every asset needs a unique id and exact lowercase SHA256");
            assets.add(new Asset(id, path, hash, Boolean.TRUE.equals(entry.get("benchmark")), number(entry, "x", 0),
                    number(entry, "y", 200), number(entry, "z", 0), number(entry, "rotation", 0)));
        }
        if (assets.isEmpty() || assets.size() > 128 || assets.stream().noneMatch(Asset::benchmark))
            throw new IllegalArgumentException("Provide 1..128 assets and explicitly select at least one benchmark case");
        if (assets.stream().filter(Asset::benchmark).count() > 12) throw new IllegalArgumentException("At most twelve benchmark cases");
        return new LabManifest(engine, yaml.getLong("seed", 0), yaml.getString("pedestalMaterial", "STONE"),
                yaml.getBoolean("profileSpark", true), yaml.getBoolean("warmEntityAdapters", false), List.copyOf(assets));
    }
    private static int number(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number n) || n.longValue() != n.doubleValue() || n.longValue() < Integer.MIN_VALUE || n.longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid integer " + key);
        return n.intValue();
    }
    static Path assetPath(Path folder, String value) throws IOException {
        Path relative = Path.of(value);
        if (relative.isAbsolute() || !value.endsWith(".schem") || relative.normalize().startsWith(".."))
            throw new IOException("Asset must be a relative .schem path inside the plugin directory");
        Path base = folder.toRealPath(), candidate = base.resolve(relative).normalize();
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)
                || !candidate.toRealPath().startsWith(base)) throw new IOException("Asset is not a contained regular file");
        if (Files.size(candidate) > 16L << 20) throw new IOException("Asset exceeds 16 MiB");
        return candidate;
    }
}
