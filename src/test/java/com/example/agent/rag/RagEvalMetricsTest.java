package com.example.agent.rag;

import com.example.agent.rag.GoldenSet.GoldenQuestion;
import com.example.agent.rag.RagEvalMetrics.Calibration;
import com.example.agent.rag.RagEvalMetrics.Retrieved;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The metric maths, checked by hand on tiny examples. No network. */
class RagEvalMetricsTest {

    private static final double EPS = 1e-9;

    private static final GoldenQuestion Q = new GoldenQuestion("Q", true, "q?",
            List.of("a.md"), List.of("alpha fact", "beta fact"), "ref");

    private static Retrieved chunk(String source, String text, double score) {
        return new Retrieved(source, text, score);
    }

    // rank 1: right file but no evidence, rank 2: evidence (alpha), rank 3: wrong file with the phrase text
    private final List<Retrieved> ranked = List.of(
            chunk("a.md", "unrelated text", 0.80),
            chunk("a.md", "This has the **Alpha   Fact** in it", 0.70),
            chunk("b.md", "alpha fact but from the wrong file", 0.60));

    @Test
    void normalizeIgnoresCaseMarkdownAndWhitespace() {
        assertEquals("the alpha fact", RagEvalMetrics.normalize("  The **Alpha\n   `Fact`** "));
        assertTrue(RagEvalMetrics.containsPhrase("x The  ALPHA\tFACT y", "alpha fact"));
        assertEquals("", RagEvalMetrics.normalize(null));
    }

    @Test
    void evidenceNeedsTheRightSourceAndAnExpectedPhrase() {
        assertFalse(RagEvalMetrics.isEvidence(ranked.get(0), Q), "right file, no phrase");
        assertTrue(RagEvalMetrics.isEvidence(ranked.get(1), Q));
        assertFalse(RagEvalMetrics.isEvidence(ranked.get(2), Q), "phrase present but wrong file");
    }

    @Test
    void hitAtKDependsOnWhereTheEvidenceRanks() {
        assertEquals(0.0, RagEvalMetrics.hitAtK(ranked, Q, 1), EPS);
        assertEquals(1.0, RagEvalMetrics.hitAtK(ranked, Q, 2), EPS);
        assertEquals(1.0, RagEvalMetrics.hitAtK(ranked, Q, 10), EPS, "k larger than the list is fine");
        assertEquals(0.0, RagEvalMetrics.hitAtK(List.of(), Q, 3), EPS);
    }

    @Test
    void phraseRecallCountsHowManyExpectedPhrasesWereFetched() {
        // Only "alpha fact" appears in the top 3 chunks, "beta fact" never does.
        assertEquals(0.5, RagEvalMetrics.phraseRecallAtK(ranked, Q, 3), EPS);
        assertEquals(0.0, RagEvalMetrics.phraseRecallAtK(ranked, Q, 1), EPS);
    }

    @Test
    void precisionIsTheShareOfEvidenceChunksInTheTopK() {
        assertEquals(1.0 / 3, RagEvalMetrics.precisionAtK(ranked, Q, 3), EPS);
        assertEquals(0.0, RagEvalMetrics.precisionAtK(List.of(), Q, 3), EPS);
    }

    @Test
    void reciprocalRankIsOneOverTheRankOfTheFirstEvidenceChunk() {
        assertEquals(0.5, RagEvalMetrics.reciprocalRank(ranked, Q), EPS);
        assertEquals(0.0, RagEvalMetrics.reciprocalRank(List.of(chunk("a.md", "nothing", 0.9)), Q), EPS);
    }

    @Test
    void bestEvidenceScoreIgnoresNonEvidenceChunks() {
        assertEquals(0.70, RagEvalMetrics.bestEvidenceScore(ranked, Q), EPS);
        assertNull(RagEvalMetrics.bestEvidenceScore(List.of(chunk("a.md", "nothing", 0.9)), Q));
    }

    @Test
    void calibrationSuggestsAThresholdOnlyWhenTheScoresSeparate() {
        Calibration clean = RagEvalMetrics.calibrate(List.of(0.80, 0.90), List.of(0.60, 0.70));
        assertTrue(clean.separable());
        assertEquals(0.75, clean.suggestedThreshold(), EPS);

        Calibration overlap = RagEvalMetrics.calibrate(List.of(0.70, 0.90), List.of(0.60, 0.75));
        assertFalse(overlap.separable(), "an out-of-scope question scored higher than a weak in-scope one");
        assertEquals(0.70, overlap.lowestEvidenceScore(), EPS);
        assertEquals(0.75, overlap.highestOutOfScopeTop(), EPS);
    }

    @Test
    void meanOfNothingIsZero() {
        assertEquals(0.0, RagEvalMetrics.mean(List.of()), EPS);
        assertEquals(2.0, RagEvalMetrics.mean(List.of(1.0, 3.0)), EPS);
    }
}
