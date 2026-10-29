package com.magmaguy.betterstructures.lab;

/** Matches Schematic.pasteClipboardBlock, including preserved air variants and fixed pedestal input. */
enum LabPolicy {
    BLOCK, AIR, MASK, PEDESTAL;
    static LabPolicy forState(String state) {
        String id = state.split("\\[", 2)[0];
        return switch (id) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> AIR;
            case "minecraft:barrier" -> MASK;
            case "minecraft:bedrock" -> PEDESTAL;
            default -> BLOCK;
        };
    }
    boolean preserves(boolean destinationAir, boolean destinationSolid) {
        return this == MASK || this == AIR && destinationAir || this == PEDESTAL && destinationSolid;
    }
}
