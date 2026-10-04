package com.prism.gateway.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * The documented local substitute for an embedding model: a normalized bag of content words.
 *
 * <p>Pipeline: lowercase, canonicalize a small set of common multi-word synonyms
 * ("two-factor" / "2fa" / "mfa", "sign in" / "log in", "money back" / "refund"...), tokenize,
 * expand {@code n't} to {@code not} (negation changes meaning, so it is kept), drop stopwords
 * and question-framing words ("how", "what", "steps", "way"...), apply light suffix stemming,
 * then term-frequency weight and L2-normalize.
 *
 * <p>Consequences, by design: rephrasings that keep the same content words match exactly
 * ("How do I reset my password on the dashboard?" vs "What are the steps to reset my dashboard
 * password?"); a changed subject drops similarity sharply (password reset vs 2FA reset ≈ 0.67);
 * numbers and identifiers are content, so "72°F" and "73°F" do not collide. It cannot bridge
 * paraphrases with no shared vocabulary - that is the known limitation versus a real model.
 */
@Component
public class LexicalEmbedder implements TextEmbedder {

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");

    /** Applied in order, before tokenizing. Kept deliberately small and domain-neutral. */
    private static final List<Map.Entry<Pattern, String>> PHRASES = List.of(
            Map.entry(Pattern.compile("\\b(?:two|2)[- ]?(?:factor|step)(?: authentication| auth| verification)?\\b|\\b(?:2fa|mfa|multi[- ]?factor(?: authentication)?)\\b"), " twofactor "),
            Map.entry(Pattern.compile("\\b(?:log|sign)[- ]?(?:in|on)\\b|\\blogon\\b"), " login "),
            Map.entry(Pattern.compile("\\b(?:log|sign)[- ]?out\\b"), " logout "),
            Map.entry(Pattern.compile("\\b(?:money back|reimburse(?:ment|d)?)\\b"), " refund "),
            Map.entry(Pattern.compile("\\be-?mail\\b"), " email "),
            Map.entry(Pattern.compile("\\bcan't\\b|\\bcannot\\b"), " can not "),
            Map.entry(Pattern.compile("\\bwon't\\b"), " will not "),
            Map.entry(Pattern.compile("n't\\b"), " not "));

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("bought", "buy"), Map.entry("purchase", "buy"), Map.entry("purchased", "buy"),
            Map.entry("pwd", "password"), Map.entry("passcode", "password"), Map.entry("passphrase", "password"),
            Map.entry("cancellation", "cancel"), Map.entry("cancelled", "cancel"), Map.entry("canceled", "cancel"),
            Map.entry("faq", "question"), Map.entry("app", "application"));

    private static final Set<String> STOPWORDS = Set.of(
            // function words
            "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "on", "in", "at", "to", "for", "from",
            "by", "with", "about", "into", "onto", "over", "under", "up", "as", "is", "are", "was", "were", "be",
            "been", "being", "am", "do", "does", "did", "doing", "have", "has", "had", "i", "me", "my", "mine",
            "we", "us", "our", "you", "your", "it", "its", "this", "that", "these", "those", "there", "here",
            "can", "could", "would", "should", "will", "shall", "may", "might", "must", "s", "t", "d", "ll",
            "re", "ve", "m", "just", "also", "any", "some", "get", "got",
            // question framing: changes the phrasing, not the information asked for
            "how", "what", "which", "where", "when", "who", "steps", "step", "way", "ways", "guide",
            "instructions", "procedure", "tell", "please", "know", "need", "want", "help", "possible", "able",
            "go", "going");

    @Override
    public SparseVector embed(String text) {
        Map<String, Double> tf = new LinkedHashMap<>();
        for (String token : tokens(text)) {
            tf.merge(token, 1.0, Double::sum);
        }
        return SparseVector.of(tf);
    }

    @Override
    public String name() {
        return "lexical-bow-v1";
    }

    List<String> tokens(String text) {
        String s = text.toLowerCase().replace('’', '\'');
        for (Map.Entry<Pattern, String> phrase : PHRASES) {
            s = phrase.getKey().matcher(s).replaceAll(phrase.getValue());
        }
        Matcher m = TOKEN.matcher(s);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String token = m.group();
            if (STOPWORDS.contains(token)) {
                continue;
            }
            token = SYNONYMS.getOrDefault(token, token);
            out.add(stem(token));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() <= 3 || !w.chars().allMatch(Character::isLetter)) {
            return w;
        }
        if (w.endsWith("ies") && w.length() > 4) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("sses")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("ing") && w.length() > 5) {
            return w.substring(0, w.length() - 3);
        }
        if (w.endsWith("ed") && w.length() > 4) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }
}
