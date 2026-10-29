package ru.ruscrafting.nativestructureslab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class LabGuard {
    static final String WORLD = "rc_native_lab";
    static void server(String bind, int port) {
        if (port != 54295 || !("127.0.0.1".equals(bind) || "::1".equals(bind)
                || "0:0:0:0:0:0:0:1".equals(bind))) {
            throw new IllegalStateException("Lab requires an explicit loopback bind and port 54295");
        }
    }
    static void world(String name) {
        if (!WORLD.equals(name)) throw new IllegalArgumentException("Only rc_native_lab is permitted");
    }
    static Path schematic(Path directory, String filename) throws IOException {
        Path name = Path.of(filename);
        if (name.isAbsolute() || name.getNameCount() != 1 || !filename.endsWith(".schem")
                || filename.contains("/") || filename.contains("\\")) {
            throw new IOException("Use one .schem filename inside the schematics directory");
        }
        if (Files.isSymbolicLink(directory)) throw new IOException("Symlink schematic directory is not allowed");
        Path base = directory.toRealPath();
        Path file = base.resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || !file.toRealPath().getParent().equals(base)) {
            throw new IOException("Schematic must be a regular file inside the schematics directory");
        }
        return file;
    }
}
