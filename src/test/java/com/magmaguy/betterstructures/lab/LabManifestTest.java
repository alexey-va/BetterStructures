package com.magmaguy.betterstructures.lab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

final class LabManifestTest {
    @TempDir Path folder;
    private String valid() { return """
            enabled: true
            scope: CLIPBOARD_BLOCK_PHASE
            engine: NATIVE_GENERATION
            seed: 123
            assets:
              - id: real_asset
                file: schematics/asset.schem
                sha256: '%s'
                benchmark: true
                x: -12000
                y: 200
                z: 24000
            """.formatted("a".repeat(64)); }
    private LabManifest load(String text) throws Exception {
        Path file = folder.resolve("native-lab.yml"); Files.writeString(file, text); return LabManifest.load(file);
    }
    @Test void disabledManifestDoesNotChangeProductionBehaviour() throws Exception {
        assertNull(load("enabled: false\nengine: invalid\n"));
    }
    @Test void acceptsExplicitScopeAndPreservesSignedCoordinates() throws Exception {
        var manifest = load(valid());
        assertEquals(LabManifest.Engine.NATIVE_GENERATION, manifest.engine());
        assertEquals(-12000, manifest.assets().getFirst().x()); assertEquals(24000, manifest.assets().getFirst().z());
        assertFalse(manifest.warmEntityAdapters());
    }
    @Test void unknownEngineAndBroaderScopeNeverFallBackSilently() {
        assertThrows(IllegalArgumentException.class, () -> load(valid().replace("NATIVE_GENERATION", "NATIVE")));
        assertThrows(IllegalArgumentException.class, () -> load(valid().replace("CLIPBOARD_BLOCK_PHASE", "AUTOMATIC_FITTING")));
        assertThrows(IllegalArgumentException.class, () -> load(valid().replace("benchmark: true", "benchmark: false")));
    }
    @Test void fractionalPlacementCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> load(valid().replace("y: 200", "y: 200.5")));
    }
    @Test void assetPathsRejectTraversalAndExternalSymlinks() throws Exception {
        Path inside = folder.resolve("ok.schem"); Files.writeString(inside, "fixture");
        assertEquals(inside.toRealPath(), LabManifest.assetPath(folder, "ok.schem"));
        assertThrows(IOException.class, () -> LabManifest.assetPath(folder, "../other.schem"));
        assertThrows(IOException.class, () -> LabManifest.assetPath(folder, inside.toString()));
        Files.createSymbolicLink(folder.resolve("alias.schem"), inside);
        assertThrows(IOException.class, () -> LabManifest.assetPath(folder, "alias.schem"));
        Path outside = Files.createTempDirectory("bs-manifest-outside-");
        try {
            Files.writeString(outside.resolve("external.schem"), "fixture");
            Files.createSymbolicLink(folder.resolve("escape"), outside);
            assertThrows(IOException.class, () -> LabManifest.assetPath(folder, "escape/external.schem"));
        } finally { Files.deleteIfExists(outside.resolve("external.schem")); Files.deleteIfExists(outside); }
    }
}
