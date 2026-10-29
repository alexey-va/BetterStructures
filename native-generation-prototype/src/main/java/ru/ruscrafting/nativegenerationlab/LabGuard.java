package ru.ruscrafting.nativegenerationlab;

final class LabGuard {
    static final String WORLD = "rc_native_generation_lab";
    static void server(String bind, int port) {
        if (port != 54295 || !"127.0.0.1".equals(bind))
            throw new IllegalStateException("Generation lab requires bind 127.0.0.1 and port 54295");
    }
    static void world(String name) {
        if (!WORLD.equals(name)) throw new IllegalArgumentException("Only rc_native_generation_lab is permitted");
    }
}
