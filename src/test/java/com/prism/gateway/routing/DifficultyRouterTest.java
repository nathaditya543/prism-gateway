package com.prism.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DifficultyRouterTest {

    private final DifficultyRouter router = new DifficultyRouter();

    @Test
    void shortButHardIsComplex() {
        RoutingDecision d = router.classify("Prove that the square root of 2 is irrational.");
        assertThat(d.complex()).isTrue();
        assertThat(d.reason()).contains("formal proof");
    }

    @Test
    void longButTrivialIsSimpleBecauseOnlyTheTaskSentenceIsScored() {
        RoutingDecision d = router.classify("Here is our on-call roster for the next two weeks: Monday - Priya, "
                + "Tuesday - Chen, Wednesday - Amara, Thursday - Diego, Friday - Fatima, Saturday - Lukas, "
                + "Sunday - Mei, next Monday - Tom. Who is on call this Thursday?");
        assertThat(d.complex()).isFalse();
        assertThat(d.taskText()).isEqualTo("Who is on call this Thursday?");
    }

    @Test
    void quotedMaterialIsNotScored() {
        // The quoted text is full of "hard" words, but the task is a rewrite.
        RoutingDecision d = router.classify("Rewrite this to sound friendlier: 'Explain why the distributed "
                + "consensus design trade-offs require a formal proof of linearizability.'");
        assertThat(d.complex()).isFalse();
        assertThat(d.taskText()).contains("[quoted]");
    }

    @Test
    void apostrophesInsideQuotesDoNotBreakQuoteStripping() {
        RoutingDecision d = router.classify("Rewrite the following email: 'I don't want to chase you again, "
                + "please design a proof.'");
        assertThat(d.complex()).isFalse();
    }

    @Test
    void reasonListsEveryMatchedSignal() {
        RoutingDecision d = router.classify("Compare event sourcing with CRUD and recommend one, with justification.");
        assertThat(d.signals()).anyMatch(s -> s.startsWith("comparison"))
                .anyMatch(s -> s.startsWith("recommendation"))
                .anyMatch(s -> s.startsWith("explicit reasoning"));
    }

    @Test
    void vagueFollowUpQuestionInheritsTheProblemStatement() {
        RoutingDecision d = router.classify(
                "Our Kafka consumers keep rebalancing every few minutes under load. What could be causing this?");
        assertThat(d.complex()).isTrue();
        assertThat(d.taskText()).startsWith("Our Kafka consumers");
    }

    @Test
    void pastedMaterialIsNotInheritedEvenWhenTheQuestionSaysThis() {
        // "this Thursday" refers back, but the sentence before is a 40+ word roster, i.e. material.
        RoutingDecision d = router.classify("Here is the roster: Monday - Priya, Tuesday - Chen, Wednesday - Amara, "
                + "Thursday - Diego, Friday - Fatima, Saturday - Lukas, Sunday - Mei, next Monday - Tom, next Tuesday "
                + "- Sara, next Wednesday - Ravi, next Thursday - Ana, next Friday - Kofi. Who is on call this Thursday?");
        assertThat(d.complex()).isFalse();
        assertThat(d.taskText()).isEqualTo("Who is on call this Thursday?");
    }

    @Test
    void domainTermsMatchAnyWordFormButCountOnce() {
        RoutingDecision d = router.classify("Explain how sharding works when a sharded table needs a new shard key.");
        assertThat(d.signals()).anyMatch(s -> s.startsWith("domain terms [sharding] (+0.5)"));
    }

    @Test
    void generalizesToTheFreshHoldoutSet() throws Exception {
        // data/routing_holdout2.jsonl was written and labeled before the second round of signal changes.
        assertThat(accuracy("data/routing_holdout2.jsonl")).isGreaterThanOrEqualTo(0.9);
    }

    private double accuracy(String file) throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        int correct = 0;
        int total = 0;
        for (String line : Files.readAllLines(Path.of(file))) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode c = mapper.readTree(line);
            boolean expectComplex = "smart".equals(c.path("expected_tier").asString());
            correct += router.classify(c.path("prompt").asString()).complex() == expectComplex ? 1 : 0;
            total++;
        }
        return (double) correct / total;
    }

    @Test
    void beatsTheLengthBaselineOnThePackEvalSet() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        int correct = 0;
        int baselineCorrect = 0;
        int total = 0;
        for (String line : Files.readAllLines(Path.of("data/routing_eval.jsonl"))) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode c = mapper.readTree(line);
            String prompt = c.path("prompt").asString();
            boolean expectComplex = "smart".equals(c.path("expected_tier").asString());
            correct += router.classify(prompt).complex() == expectComplex ? 1 : 0;
            boolean baselineComplex = RoutingDecision.COMPLEX.equals(
                    DifficultyRouter.lengthBaseline(prompt, RoutingEvalService.BASELINE_WORDS));
            baselineCorrect += baselineComplex == expectComplex ? 1 : 0;
            total++;
        }
        assertThat(total).isEqualTo(20);
        assertThat(correct).as("router accuracy").isGreaterThanOrEqualTo(16); // >= 80%
        assertThat(correct).as("router must beat length-only").isGreaterThan(baselineCorrect);
    }
}
