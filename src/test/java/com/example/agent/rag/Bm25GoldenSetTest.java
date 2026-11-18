package com.example.agent.rag;

import com.example.agent.rag.Bm25Index.KeywordHit;
import com.example.agent.rag.GoldenSet.GoldenQuestion;
import com.example.agent.rag.RagEvalMetrics.Retrieved;
import com.example.agent.rag.load.KnowledgeBaseLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How well does keyword search alone do on the golden questions, using the real docs and the real
 * chunking? Needs no embeddings, so it runs offline and gives measured numbers without an API key.
 *
 * It also checks the keyword relevance gate: out-of-scope questions must retrieve nothing.
 * The vector side is measured by RagIT (real PGvector) and EvalIT (real model).
 */
class Bm25GoldenSetTest {

    private static final GoldenSet GOLDEN = GoldenSet.load();
    private static final double MIN_TERM_MATCH = RagHybridSettings.DEFAULT_MIN_TERM_MATCH;
    private static Bm25Index index;

    @BeforeAll
    static void buildIndexFromTheRealDocs() throws IOException {
        List<Document> chunks = new ArrayList<>();
        for (String doc : List.of("returns-policy.md", "shipping.md", "returns-flow.md")) {
            String text = new ClassPathResource("docs/kb/" + doc).getContentAsString(StandardCharsets.UTF_8);
            chunks.addAll(KnowledgeBaseLoader.toChunks(doc, "TEST", text, "hash"));
        }
        index = new Bm25Index();
        index.replaceAll(chunks);
    }

    private static List<Retrieved> ranked(String question, int n) {
        return index.search(question, n).stream()
                .map(h -> new Retrieved(String.valueOf(h.document().getMetadata().get("source")), h.document().getText(), h.score()))
                .toList();
    }

    private static List<Retrieved> gated(String question, int n) {
        return index.search(question, n).stream()
                .filter(h -> HybridDocumentRetriever.passesKeywordGate(h, MIN_TERM_MATCH))
                .map(h -> new Retrieved(String.valueOf(h.document().getMetadata().get("source")), h.document().getText(), h.score()))
                .toList();
    }

    /** Not an assertion: shows what each gate strictness keeps and leaks, to choose app.rag.hybrid.min-term-match. */
    @Test
    void printGateSweep() {
        for (double m : new double[]{0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9}) {
            List<String> lost = new ArrayList<>(), leaked = new ArrayList<>();
            for (GoldenQuestion q : GOLDEN.answerable()) {
                boolean kept = index.search(q.question(), 5).stream()
                        .filter(h -> HybridDocumentRetriever.passesKeywordGate(h, m))
                        .limit(3).anyMatch(h -> RagEvalMetrics.isEvidence(new Retrieved(
                                String.valueOf(h.document().getMetadata().get("source")), h.document().getText(), h.score()), q));
                if (!kept) lost.add(q.id());
            }
            for (GoldenQuestion q : GOLDEN.outOfScope()) {
                if (index.search(q.question(), 5).stream().anyMatch(h -> HybridDocumentRetriever.passesKeywordGate(h, m))) leaked.add(q.id());
            }
            System.out.printf(Locale.ROOT, "SWEEP min-term-match=%.1f  evidence lost for %s  out-of-scope leaks %s%n", m, lost, leaked);
        }
    }

    @Test
    void keywordSearchAloneFindsTheEvidenceForMostQuestions() {
        List<Double> hit1 = new ArrayList<>(), hit3 = new ArrayList<>(), hit5 = new ArrayList<>(), mrr = new ArrayList<>();
        List<String> missedAt3 = new ArrayList<>();
        for (GoldenQuestion q : GOLDEN.answerable()) {
            List<Retrieved> r = ranked(q.question(), 5);
            hit1.add(RagEvalMetrics.hitAtK(r, q, 1));
            double h3 = RagEvalMetrics.hitAtK(r, q, 3);
            hit3.add(h3);
            hit5.add(RagEvalMetrics.hitAtK(r, q, 5));
            mrr.add(RagEvalMetrics.reciprocalRank(r, q));
            if (h3 == 0.0) missedAt3.add(q.id());
        }
        System.out.printf(Locale.ROOT, "BM25 alone on %d answerable questions: hit@1=%.2f hit@3=%.2f hit@5=%.2f MRR=%.2f missed@3=%s%n",
                GOLDEN.answerable().size(), RagEvalMetrics.mean(hit1), RagEvalMetrics.mean(hit3), RagEvalMetrics.mean(hit5),
                RagEvalMetrics.mean(mrr), missedAt3);

        assertTrue(RagEvalMetrics.mean(hit3) >= 0.80, "BM25 hit@3 was " + RagEvalMetrics.mean(hit3) + ", missed: " + missedAt3);
        assertTrue(RagEvalMetrics.mean(hit5) >= 0.85, "BM25 hit@5 was " + RagEvalMetrics.mean(hit5));
    }

    @Test
    void theKeywordGateKeepsTheEvidenceForAlmostEveryAnswerableQuestion() {
        List<Double> hit3 = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        for (GoldenQuestion q : GOLDEN.answerable()) {
            double ungated = RagEvalMetrics.hitAtK(ranked(q.question(), 5), q, 3);
            double afterGate = RagEvalMetrics.hitAtK(gated(q.question(), 5), q, 3);
            hit3.add(afterGate);
            if (ungated == 1.0 && afterGate == 0.0) lost.add(q.id());
        }
        System.out.printf(Locale.ROOT, "BM25 after the gate: hit@3=%.2f, questions the gate lost: %s%n", RagEvalMetrics.mean(hit3), lost);
        // The gate trades a little keyword recall for precision. Vector search still covers what it drops.
        assertTrue(RagEvalMetrics.mean(hit3) >= 0.90, "the gate cost too much keyword recall, lost: " + lost);
    }

    @Test
    void theKeywordGateRejectsEveryOutOfScopeQuestion() {
        List<String> leaked = new ArrayList<>();
        for (GoldenQuestion q : GOLDEN.outOfScope()) {
            List<KeywordHit> passed = index.search(q.question(), 5).stream()
                    .filter(h -> HybridDocumentRetriever.passesKeywordGate(h, MIN_TERM_MATCH)).toList();
            if (!passed.isEmpty()) leaked.add(q.id() + " matched " + passed.get(0).matchedTerms() + "/" + passed.get(0).queryTerms());
        }
        System.out.println("BM25 gate leaks on out-of-scope questions: " + (leaked.isEmpty() ? "none" : leaked));
        assertTrue(leaked.isEmpty(), "out-of-scope questions got keyword hits through the gate: " + leaked);
    }
}
