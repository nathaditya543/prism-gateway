package com.prism.gateway.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Judges prompt difficulty for the {@code auto} alias with engineered, explainable signals.
 *
 * <p>Why not length: prompt length mostly measures how much <em>material</em> the caller pasted
 * (a roster, a log, an email), not how hard the <em>task</em> is. So the router first isolates
 * the task:
 * <ol>
 *   <li>Quoted spans (the email to rewrite, the text to extract from) are removed.</li>
 *   <li>The prompt is split into sentences; only <em>task sentences</em> - questions and
 *       imperatives - are scored. Statements ("Here is our roster: ...", raw log lines) are
 *       treated as material and ignored.</li>
 *   <li>Task text is scored: reasoning-heavy task types (proofs, estimation, design, trade-offs,
 *       causal "why", diagnosis, algorithmic complexity, multi-part deliverables, expert domain
 *       terms) add points; lookup, extraction, transformation and short-answer tasks subtract.</li>
 * </ol>
 * Score &ge; {@value #COMPLEX_THRESHOLD} routes to the complex tier. Every matched signal is
 * returned so the decision is auditable in the request log.
 *
 * <p>Very long prompts are truncated to {@value #MAX_CHARS} characters before classification: the
 * task almost always sits in the first or last sentences, and this bounds hot-path cost.
 */
@Component
public class DifficultyRouter {

    public static final double COMPLEX_THRESHOLD = 2.0;
    static final int MAX_CHARS = 8_000;

    private record Signal(String label, double weight, Pattern pattern) {
    }

    private static Signal signal(String label, double weight, String regex) {
        return new Signal(label, weight, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static final List<Signal> SIGNALS = List.of(
            // --- reasoning-heavy task types ---
            signal("formal proof/derivation", 3.0,
                    "\\b(prove|disprove|proof|derive|derivation|formally)\\b|\\b(show|demonstrate) that\\b"),
            signal("explicit reasoning requested", 2.0,
                    "show (your|the) (reasoning|work|steps)|step[- ]by[- ]step|\\bjustify\\b|\\bjustification\\b"
                            + "|explain your (reasoning|steps|thinking|approach|work)|reason through|walk (me )?through"),
            signal("quantitative reasoning", 2.0,
                    "\\bprobabilit(y|ies)\\b|\\bexpected (number|value)\\b|\\bhow likely\\b|\\bvariance\\b"),
            signal("security analysis", 2.0,
                    "threat model|attack surface|security (review|audit|analysis)|\\bvulnerabilit\\w*|\\bexploit\\w*"),
            signal("trade-off analysis", 2.0,
                    "trade[- ]?offs?|pros and cons|advantages and disadvantages|\\bimplications\\b"),
            signal("estimation", 2.0, "\\bestimate\\b|\\bfermi\\b|back[- ]of[- ](the[- ])?envelope|order of magnitude"),
            signal("design task", 2.0, "\\b(design|architect|architecture)\\b"),
            signal("planning", 1.5, "\\b(plan|strategy|roadmap|rollout|roll back|rollback)\\b"),
            signal("comparison", 1.5, "\\bcompare\\b|\\bcomparison\\b|\\bversus\\b|\\bvs\\.?\\s|which is better"),
            signal("recommendation", 1.0, "\\brecommend|should (we|i) (use|choose|pick)"),
            signal("causal 'why' question", 2.0,
                    "^why\\b|\\bwhy (can|does|do|would|is|are|did|might|could|should)\\b|\\bexplain why\\b"),
            signal("causal analysis", 1.5,
                    "\\b(mechanisms?|root causes?|causes|causing|failure modes?)\\b"
                            + "|\\bwhen (each|it|this|they|one)( one)? (fails?|breaks?)\\b"),
            // a symptom was described and the caller asks what is behind it: open-ended by nature
            signal("symptom diagnosis", 2.0,
                    "\\bwhat (could|might|would|can) (be )?(caus\\w*|explain\\w*|going on|wrong|happening)\\b"),
            signal("diagnosis", 1.5, "\\b(debug|diagnose|troubleshoot)\\b|\\bmost likely\\b|in order of likelihood"),
            signal("algorithmic complexity", 2.0,
                    "\\bo\\([^)]{1,20}\\)|time complexity|space complexity|\\balgorithm\\b|asymptotic"),
            signal("code generation", 1.0,
                    "\\b(write|implement|build) (a |an )?(sql )?(function|program|class|script|parser|service|library"
                            + "|query)\\b"),
            signal("explanation of how/what", 1.0,
                    "\\bexplain (how|what|the difference)\\b|\\bhow (does|do) .{3,60}? work\\b|\\bhow the \\w+ works\\b"),
            signal("open-ended method", 1.0, "\\bhow (would|should|could) (you|we|i)\\b|\\bhow you would\\b"),
            signal("edge-case reasoning", 1.0,
                    "\\b(concrete|counter)[- ]?examples?\\b|\\bedge cases?\\b|\\banomal(y|ies)\\b"
                            + "|\\bhandl(e|ing) (ties|edge cases|nulls?|duplicates|concurrency|errors|failures|retries)\\b"),
            signal("enumerated analysis", 1.0,
                    "\\b(give|list|name|provide|identify) (three|four|five|several|multiple|\\d+) .{0,30}?"
                            + "(mechanisms|reasons|causes|examples|ways|approaches|strategies|options|risks)\\b"),
            // --- simple task types ---
            signal("fact lookup", -2.0,
                    "^(who|when|where|which)\\b|\\bwhat (is|was|are) the (capital|timestamp|name|date|time|value"
                            + "|population|email|phone|address|first|last|height|price|author)\\b"),
            signal("definition", -1.5,
                    "^what (does|do) .{1,60} (mean|stand for)\\b|^define\\b|^what is (a|an) [\\w\\s-]{1,30}\\?$"),
            signal("text transformation", -2.0,
                    "^(translate|convert|rewrite|rephrase|paraphrase|summari[sz]e|reformat|format|spell|correct"
                            + "|fix the (typo|grammar|spelling)|capitali[sz]e|sort|alphabeti[sz]e)\\b"),
            signal("extraction", -2.0, "^(extract|find|look up|pull out|count)\\b|\\bextract\\b"),
            signal("short output requested", -1.0,
                    "\\bone[- ](line|word|sentence)\\b|\\bbriefly\\b|\\bin (a|one) (word|sentence)\\b|\\byes or no\\b"),
            signal("closed yes/no question", -1.0, "^(is|are|does|do|can|was|were|has|have) [^?]{1,60}\\?$"));

    /** Expert vocabulary, any word form ("shard", "sharded", "sharding" count once, by stem). Each distinct
     *  term adds a little, capped so vocabulary alone cannot decide. */
    private static final Pattern DOMAIN_TERMS = Pattern.compile(
            "\\b(distribut|consisten|consensus|partition|quorum|replicat|linearizab|latenc|p9[59]|throughput"
                    + "|concurren|race condition|deadlock|multi-tenan|zero-downtime|migrat|schema|shard|idempoten"
                    + "|isolation level|event sourcing|ledger|prorat|irrational|theorem|lemma|invariant|induction"
                    + "|integer|prime|eigen|gradient|convergen|cryptograph|locking)\\w*",
            Pattern.CASE_INSENSITIVE);
    private static final double DOMAIN_WEIGHT = 0.5;
    private static final double DOMAIN_CAP = 2.0;

    /** Multi-part deliverables: each extra demand adds a point, capped. */
    private static final Pattern MULTI_PART = Pattern.compile(
            "\\b(and|then|including|with) (explain|give|show|include|recommend|justify|list|describe|discuss|how|why"
                    + "|what)\\b|\\bincluding\\b|\\bwith (justification|reasoning|examples?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final double MULTI_PART_CAP = 2.0;

    private static final Set<String> IMPERATIVES = Set.of(
            "prove", "disprove", "show", "explain", "describe", "design", "draft", "write", "compare", "estimate",
            "list", "give", "translate", "convert", "rewrite", "rephrase", "paraphrase", "summarize", "summarise",
            "extract", "find", "calculate", "compute", "solve", "derive", "implement", "create", "generate", "build",
            "analyze", "analyse", "evaluate", "recommend", "suggest", "propose", "outline", "plan", "identify",
            "classify", "fix", "debug", "refactor", "optimize", "review", "tell", "define", "name", "count", "sort",
            "rank", "format", "check", "answer", "make", "provide", "determine", "diagnose", "justify", "discuss",
            "argue", "critique", "assess", "predict", "sketch", "choose", "decide", "pick", "select", "help",
            "please", "look", "pull", "spell", "correct", "capitalize", "reformat");

    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"[^\"]{12,}\"|“[^”]{12,}”");
    /** A single-quoted span must open after whitespace/colon and close before whitespace/punctuation, so
     *  apostrophes inside words ("don't") are not mistaken for quote marks. */
    private static final Pattern SINGLE_QUOTED = Pattern.compile("(?<=^|[\\s:(])'.{12,}?'(?=$|[\\s.,;:!?)])",
            Pattern.DOTALL);
    /** Pronoun uses only: "causing this?" refers back, "this Thursday" is a determiner and does not. */
    private static final Pattern ANAPHORA = Pattern.compile("\\b(this|that|these|those)\\s*[?.!]*$|\\b(it|them)\\b",
            Pattern.CASE_INSENSITIVE);
    /** A preceding statement longer than this is pasted material (a log, a roster), not a problem statement. */
    static final int MAX_CONTEXT_WORDS = 40;
    private static final Pattern CODE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.?!])\\s+(?=[A-Z0-9\"'(“])");

    public RoutingDecision classify(String prompt) {
        String text = prompt == null ? "" : prompt.strip();
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS / 2) + " " + text.substring(text.length() - MAX_CHARS / 2);
        }
        String stripped = CODE_BLOCK.matcher(text).replaceAll(" [code] ");
        stripped = DOUBLE_QUOTED.matcher(stripped).replaceAll("[quoted]");
        stripped = SINGLE_QUOTED.matcher(stripped).replaceAll("[quoted]");

        List<String> taskSentences = new ArrayList<>();
        String previousStatement = null;
        for (String sentence : SENTENCE_BREAK.split(stripped)) {
            String s = sentence.strip();
            if (s.isEmpty()) {
                continue;
            }
            if (!isTaskSentence(s)) {
                previousStatement = s;
                continue;
            }
            // "What could be causing this?" says little by itself: the problem is in the sentence before.
            if (previousStatement != null && refersBack(s) && words(previousStatement) <= MAX_CONTEXT_WORDS) {
                taskSentences.add(previousStatement);
            }
            taskSentences.add(s);
            previousStatement = null;
        }
        if (taskSentences.isEmpty()) {
            taskSentences.add(stripped.strip());
        }

        double score = 0;
        List<String> matched = new ArrayList<>();
        Map<String, String> domain = new LinkedHashMap<>(); // stem -> first word form seen
        int multiPart = 0;
        for (Signal signal : SIGNALS) {
            for (String sentence : taskSentences) {
                if (signal.pattern().matcher(sentence).find()) {
                    score += signal.weight();
                    matched.add(signal.label() + " (" + format(signal.weight()) + ")");
                    break;
                }
            }
        }
        for (String sentence : taskSentences) {
            Matcher d = DOMAIN_TERMS.matcher(sentence);
            while (d.find()) {
                domain.putIfAbsent(d.group(1).toLowerCase(Locale.ROOT), d.group().toLowerCase(Locale.ROOT));
            }
            Matcher m = MULTI_PART.matcher(sentence);
            while (m.find()) {
                multiPart++;
            }
        }
        if (!domain.isEmpty()) {
            double w = Math.min(DOMAIN_CAP, domain.size() * DOMAIN_WEIGHT);
            score += w;
            matched.add("domain terms " + domain.values() + " (" + format(w) + ")");
        }
        if (multiPart > 0) {
            double w = Math.min(MULTI_PART_CAP, multiPart);
            score += w;
            matched.add("multi-part request x" + multiPart + " (" + format(w) + ")");
        }

        boolean complex = score >= COMPLEX_THRESHOLD;
        return new RoutingDecision(complex ? RoutingDecision.COMPLEX : RoutingDecision.SIMPLE, score, matched,
                String.join(" ", taskSentences));
    }

    /** A short task sentence that points back at what was just said ("this", "it"). */
    private static boolean refersBack(String sentence) {
        return words(sentence) <= 8 && ANAPHORA.matcher(sentence).find();
    }

    private static int words(String s) {
        return s.trim().split("\\s+").length;
    }

    private static boolean isTaskSentence(String sentence) {
        if (sentence.endsWith("?")) {
            return true;
        }
        String first = sentence.split("[\\s:,]+", 2)[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return IMPERATIVES.contains(first);
    }

    private static String format(double w) {
        return (w > 0 ? "+" : "") + (w == Math.rint(w) ? String.valueOf((long) w) : String.valueOf(w));
    }

    /** The length-only baseline the eval compares against: long prompts are assumed hard. */
    public static String lengthBaseline(String prompt, int wordThreshold) {
        int words = prompt == null ? 0 : prompt.trim().split("\\s+").length;
        return words >= wordThreshold ? RoutingDecision.COMPLEX : RoutingDecision.SIMPLE;
    }
}
