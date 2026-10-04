package com.prism.gateway.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** An L2-normalized term -> weight vector. Cosine similarity is then a plain dot product. */
public final class SparseVector {

    private static final SparseVector EMPTY = new SparseVector(Map.of());

    private final Map<String, Double> weights;

    private SparseVector(Map<String, Double> weights) {
        this.weights = weights;
    }

    public static SparseVector of(Map<String, Double> raw) {
        double norm = Math.sqrt(raw.values().stream().mapToDouble(w -> w * w).sum());
        if (norm == 0) {
            return EMPTY;
        }
        Map<String, Double> normalized = new TreeMap<>();
        raw.forEach((term, w) -> normalized.put(term, w / norm));
        return new SparseVector(Collections.unmodifiableMap(normalized));
    }

    public boolean isEmpty() {
        return weights.isEmpty();
    }

    public Map<String, Double> weights() {
        return weights;
    }

    public double cosine(SparseVector other) {
        Map<String, Double> small = weights.size() <= other.weights.size() ? weights : other.weights;
        Map<String, Double> large = small == weights ? other.weights : weights;
        double dot = 0;
        for (Map.Entry<String, Double> e : small.entrySet()) {
            Double w = large.get(e.getKey());
            if (w != null) {
                dot += e.getValue() * w;
            }
        }
        return Math.min(1.0, dot);
    }

    /** {@code term=weight} pairs separated by spaces; terms never contain spaces or '='. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        weights.forEach((t, w) -> sb.append(sb.isEmpty() ? "" : " ").append(t).append('=').append(w));
        return sb.toString();
    }

    public static SparseVector parse(String serialized) {
        Map<String, Double> raw = new LinkedHashMap<>();
        if (serialized != null && !serialized.isBlank()) {
            for (String pair : serialized.trim().split(" ")) {
                int eq = pair.lastIndexOf('=');
                raw.put(pair.substring(0, eq), Double.parseDouble(pair.substring(eq + 1)));
            }
        }
        return of(raw);
    }

    @Override
    public String toString() {
        return weights.keySet().toString();
    }
}
