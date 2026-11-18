package com.example.agent.rag;

import com.example.agent.rag.rrf.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Reciprocal Rank Fusion on hand-computed examples. */
class ReciprocalRankFusionTest {

    private static final double EPS = 1e-9;

    private static Document doc(String id) {
        return Document.builder().id(id).text("text of " + id).metadata("source", id + ".md").build();
    }

    private static List<String> ids(List<Document> docs) {
        return docs.stream().map(Document::getId).toList();
    }

    @Test
    void aDocumentBothListsRankBeatsOneOnlyOneListRanksFirst() {
        // k = 60. A: 1/61. B: 1/62 + 1/61 (in both). C: 1/63. D: 1/62.
        List<Document> fused = ReciprocalRankFusion.fuse(
                List.of(List.of(doc("A"), doc("B"), doc("C")), List.of(doc("B"), doc("D"))), 60);
        assertEquals(List.of("B", "A", "D", "C"), ids(fused));
    }

    @Test
    void theFusedScoreIsTheSumOfOneOverKPlusRank() {
        List<Document> fused = ReciprocalRankFusion.fuse(
                List.of(List.of(doc("A"), doc("B")), List.of(doc("B"))), 60);
        assertEquals(1.0 / 62 + 1.0 / 61, fused.get(0).getScore(), EPS);   // B: rank 2 and rank 1
        assertEquals(1.0 / 61, fused.get(1).getScore(), EPS);              // A: rank 1 in one list
    }

    @Test
    void aSmallerKGivesTopRanksMoreWeight() {
        // Same lists, different k: with k=1 the rank gap matters much more than with k=1000.
        List<List<Document>> lists = List.of(List.of(doc("A"), doc("B")), List.of(doc("C"), doc("B")));
        double smallKGap = ReciprocalRankFusion.fuse(lists, 1).get(0).getScore() - ReciprocalRankFusion.fuse(lists, 1).get(1).getScore();
        double largeKGap = ReciprocalRankFusion.fuse(lists, 1000).get(0).getScore() - ReciprocalRankFusion.fuse(lists, 1000).get(1).getScore();
        assertTrue(smallKGap > largeKGap);
    }

    @Test
    void tiesGoToTheBestSingleRankThenToTheSmallerId() {
        // Both appear once at rank 1, so the scores tie. The id decides, and the result is stable.
        assertEquals(List.of("A", "B"), ids(ReciprocalRankFusion.fuse(List.of(List.of(doc("B")), List.of(doc("A"))), 60)));
        // Four documents, none in both lists: P and R tie at rank 1, Q and S tie at rank 2. Ids break each tie.
        List<Document> fused = ReciprocalRankFusion.fuse(
                List.of(List.of(doc("P"), doc("Q")), List.of(doc("R"), doc("S"))), 60);
        assertEquals(List.of("P", "R", "Q", "S"), ids(fused));
    }

    @Test
    void sameIdIsOneDocumentEvenIfTheCopiesDiffer() {
        Document fromVector = Document.builder().id("A").text("from vector").build();
        Document fromKeyword = Document.builder().id("A").text("from keyword").build();
        List<Document> fused = ReciprocalRankFusion.fuse(List.of(List.of(fromVector), List.of(fromKeyword)), 60);
        assertEquals(1, fused.size());
        assertEquals("from vector", fused.get(0).getText(), "the first list's copy is kept");
    }

    @Test
    void fusedDocumentsKeepTheirTextAndMetadata() {
        Document fused = ReciprocalRankFusion.fuse(List.of(List.of(doc("A"))), 60).get(0);
        assertEquals("text of A", fused.getText());
        assertEquals("A.md", fused.getMetadata().get("source"));
    }

    @Test
    void emptyInputsAreSafe() {
        assertTrue(ReciprocalRankFusion.fuse(List.of(), 60).isEmpty());
        assertTrue(ReciprocalRankFusion.fuse(List.of(List.of(), List.of()), 60).isEmpty());
        assertEquals(List.of("A"), ids(ReciprocalRankFusion.fuse(List.of(List.of(doc("A")), List.of()), 60)));
    }

    @Test
    void kMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> ReciprocalRankFusion.fuse(List.of(), 0));
    }
}
