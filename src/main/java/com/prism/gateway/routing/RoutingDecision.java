package com.prism.gateway.routing;

import java.util.List;
import java.util.Locale;

/**
 * @param difficulty {@link #SIMPLE} or {@link #COMPLEX}
 * @param signals    every matched signal with its weight, for the request log
 * @param taskText   the sentences that were actually scored (after removing quoted material)
 */
public record RoutingDecision(String difficulty, double score, List<String> signals, String taskText) {

    public static final String SIMPLE = "simple";
    public static final String COMPLEX = "complex";

    public boolean complex() {
        return COMPLEX.equals(difficulty);
    }

    /** One line for the request log, e.g. {@code complex (score 3.5 >= 2): formal proof/derivation (+3), ...}. */
    public String reason() {
        String cmp = complex() ? ">=" : "<";
        String detail = signals.isEmpty() ? "no difficulty signals" : String.join(", ", signals);
        return String.format(Locale.ROOT, "%s (score %.1f %s %.0f): %s", difficulty, score, cmp,
                DifficultyRouter.COMPLEX_THRESHOLD, detail);
    }
}
