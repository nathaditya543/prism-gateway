package com.prism.gateway.routing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.prism.gateway.catalog.ModelCatalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs labeled prompts through the router and reports accuracy against the labels, alongside a
 * length-only baseline. Labels are read only here, never by the router.
 */
@Service
public class RoutingEvalService {

    /** Words at or above which the baseline calls a prompt complex. */
    static final int BASELINE_WORDS = 20;

    private final DifficultyRouter router;
    private final ModelCatalog catalog;
    private final ObjectMapper mapper;

    public RoutingEvalService(DifficultyRouter router, ModelCatalog catalog, ObjectMapper mapper) {
        this.router = router;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    public Map<String, Object> evaluate(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read eval file " + file, e);
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        int correct = 0;
        int baselineCorrect = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode c = mapper.readTree(line);
            String prompt = c.path("prompt").asString();
            String expected = c.path("expected_tier").asString();
            RoutingDecision decision = router.classify(prompt);
            String actual = tierFor(decision.difficulty());
            String baseline = tierFor(DifficultyRouter.lengthBaseline(prompt, BASELINE_WORDS));
            boolean ok = expected.equals(actual);
            correct += ok ? 1 : 0;
            baselineCorrect += expected.equals(baseline) ? 1 : 0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.path("id").asString());
            row.put("expected", expected);
            row.put("actual", actual);
            row.put("result", ok ? "pass" : "fail");
            row.put("score", Math.round(decision.score() * 10) / 10.0);
            row.put("reason", decision.reason());
            row.put("baseline", baseline);
            row.put("note", c.path("note").asString(""));
            cases.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("file", file.toString().replace('\\', '/'));
        report.put("method", "engineered signals on task sentences (see DifficultyRouter), threshold "
                + DifficultyRouter.COMPLEX_THRESHOLD);
        report.put("total", cases.size());
        report.put("correct", correct);
        report.put("accuracy", ratio(correct, cases.size()));
        report.put("baseline", Map.of(
                "method", "length-only: >= " + BASELINE_WORDS + " words routes to smart",
                "correct", baselineCorrect,
                "accuracy", ratio(baselineCorrect, cases.size())));
        report.put("cases", cases);
        return report;
    }

    private String tierFor(String difficulty) {
        return RoutingDecision.COMPLEX.equals(difficulty) ? catalog.complexTierAlias() : catalog.simpleTierAlias();
    }

    private static double ratio(int n, int total) {
        return total == 0 ? 0 : Math.round(1000.0 * n / total) / 1000.0;
    }
}
