package com.magmaguy.betterstructures.lab;

/** Stable FNV-1a receipt over coordinates and canonical state strings, not a security digest. */
final class BlockHash {
    private long value = 0xcbf29ce484222325L;
    void add(int x, int y, int z, String state) {
        number(x); number(y); number(z); number(state.length());
        for (int i = 0; i < state.length(); i++) number(state.charAt(i));
    }
    private void number(int number) {
        for (int shift = 0; shift < 32; shift += 8) { value ^= (number >>> shift) & 255; value *= 0x100000001b3L; }
    }
    String hex() { return String.format("%016x", value); }
}
