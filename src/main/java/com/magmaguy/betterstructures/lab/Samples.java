package com.magmaguy.betterstructures.lab;

import java.util.*;

final class Samples {
    private final List<Double> values = new ArrayList<>();
    void add(double value) { if (values.size() < 20_000) values.add(value); }
    Map<String, Object> summary() {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return Map.of("samples", sorted.length, "p50Ms", percentile(sorted, .50), "p95Ms", percentile(sorted, .95),
                "p99Ms", percentile(sorted, .99), "maxMs", sorted.length == 0 ? 0 : sorted[sorted.length - 1]);
    }
    private static double percentile(double[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[Math.max(0, (int) Math.ceil(p * sorted.length) - 1)];
    }
}
