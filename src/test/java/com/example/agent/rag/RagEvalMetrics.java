package com.example.agent.rag;

import com.example.agent.rag.GoldenSet.GoldenQuestion;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Retrieval metrics. Pure functions, no network, so they are unit tested in RagEvalMetricsTest.
 *
 * Vocabulary:
 *   evidence chunk : a retrieved chunk that comes from an expected source AND contains at least
 *                    one expected phrase. This is a stricter test than "right file".
 *   hit@k          : 1 if the top k results contain an evidence chunk, else 0. Averaged over
 *                    questions this is the usual "recall@k" for single-answer questions.
 *   phrase recall@k: share of a question's expected phrases found in the top k chunks combined
 *                    (context recall: did we fetch everything needed to answer?).
 *   precision@k    : share of the top k chunks that are evidence chunks (context precision).
 *   MRR            : 1 / rank of the first evidence chunk, 0 if none. Averaged over questions.
 */
final class RagEvalMetrics {

    private RagEvalMetrics() {
    }

    /** A retrieved chunk reduced to what the metrics need. */
    record Retrieved(String source, String text, Double score) {
    }

    /** Lower-cases, drops markdown emphasis and backticks, and collapses whitespace. */
    static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[*`]", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    static boolean containsPhrase(String text, String phrase) {
        return normalize(text).contains(normalize(phrase));
    }

    static boolean isEvidence(Retrieved r, GoldenQuestion q) {
        boolean sourceOk = q.expectedSources().isEmpty() || q.expectedSources().contains(r.source());
        return sourceOk && q.expectedPhrases().stream().anyMatch(p -> containsPhrase(r.text(), p));
    }

    private static List<Retrieved> top(List<Retrieved> ranked, int k) {
        return ranked.subList(0, Math.min(k, ranked.size()));
    }

    static double hitAtK(List<Retrieved> ranked, GoldenQuestion q, int k) {
        return top(ranked, k).stream().anyMatch(r -> isEvidence(r, q)) ? 1.0 : 0.0;
    }

    static double phraseRecallAtK(List<Retrieved> ranked, GoldenQuestion q, int k) {
        if (q.expectedPhrases().isEmpty()) return 1.0;
        List<Retrieved> top = top(ranked, k);
        long found = q.expectedPhrases().stream()
                .filter(p -> top.stream().anyMatch(r -> containsPhrase(r.text(), p)))
                .count();
        return (double) found / q.expectedPhrases().size();
    }

    static double precisionAtK(List<Retrieved> ranked, GoldenQuestion q, int k) {
        List<Retrieved> top = top(ranked, k);
        if (top.isEmpty()) return 0.0;
        return (double) top.stream().filter(r -> isEvidence(r, q)).count() / top.size();
    }

    static double reciprocalRank(List<Retrieved> ranked, GoldenQuestion q) {
        for (int i = 0; i < ranked.size(); i++) {
            if (isEvidence(ranked.get(i), q)) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    /** Score of the best-scoring evidence chunk, or null if none was retrieved. */
    static Double bestEvidenceScore(List<Retrieved> ranked, GoldenQuestion q) {
        return ranked.stream()
                .filter(r -> isEvidence(r, q) && r.score() != null)
                .map(Retrieved::score)
                .max(Double::compare)
                .orElse(null);
    }

    static double mean(Collection<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * Can one similarity threshold separate in-scope from out-of-scope questions?
     *
     * @param lowestEvidenceScore  the weakest "best evidence score" across answerable questions
     * @param highestOutOfScopeTop the strongest top-1 score across out-of-scope questions
     */
    record Calibration(double lowestEvidenceScore, double highestOutOfScopeTop, boolean separable, double suggestedThreshold) {
    }

    static Calibration calibrate(Collection<Double> bestEvidenceScores, Collection<Double> outOfScopeTopScores) {
        double lowEvidence = bestEvidenceScores.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double highOos = outOfScopeTopScores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        boolean separable = lowEvidence > highOos;
        double suggested = separable ? (lowEvidence + highOos) / 2.0 : lowEvidence;
        return new Calibration(lowEvidence, highOos, separable, suggested);
    }
}
