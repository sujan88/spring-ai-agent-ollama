package com.example.agent.rag;

import com.example.agent.rag.Bm25Index.KeywordHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The keyword index on tiny hand-made chunks. No network, no Spring. */
class Bm25IndexTest {

    private Bm25Index index;

    private static Document chunk(String id, String text) {
        return Document.builder().id(id).text(text).metadata("source", id + ".md").build();
    }

    @BeforeEach
    void setUp() {
        index = new Bm25Index();
        index.replaceAll(List.of(
                chunk("flag", "Partial refunds are issued only when AllowPartialRefund = true is set on the order."),
                chunk("api", "The response includes hasGiftWrapOptions on each lineItemDetails object."),
                chunk("coupon", "Coupon restrictions limit how many coupon redemptions a shopper can make each month."),
                chunk("codes", "Failure codes include RETURN_LABEL_ID_ALREADY_PRINTED and PRINT_LABEL_FAILED."),
                chunk("saga", "Charge first, then save the shipment. A later failure voids the charge.")));
    }

    @Test
    void anExactIdentifierFindsItsChunkFirst() {
        List<KeywordHit> hits = index.search("AllowPartialRefund", 3);
        assertFalse(hits.isEmpty());
        assertEquals("flag", hits.get(0).document().getId());
    }

    @Test
    void aCamelCaseIdentifierIsFoundThroughItsWords() {
        // Nobody types the identifier. They type the words. The analyser splits hasGiftWrapOptions into them.
        List<KeywordHit> hits = index.search("has gift wrap options", 3);
        assertEquals("api", hits.get(0).document().getId());
        assertEquals(hits.get(0).queryTerms(), hits.get(0).matchedTerms(), "every query term is in the chunk");
    }

    @Test
    void anUnderscoreConstantIsFoundThroughItsWords() {
        List<KeywordHit> hits = index.search("label id already printed", 3);
        assertEquals("codes", hits.get(0).document().getId());
    }

    @Test
    void stemmingMatchesSingularAndPlural() {
        assertEquals("coupon", index.search("restriction", 3).get(0).document().getId());
        assertEquals("coupon", index.search("restricting redemptions", 3).get(0).document().getId());
    }

    @Test
    void stopWordsAreIgnored() {
        assertTrue(index.search("what is the", 5).isEmpty());
    }

    @Test
    void termCoverageCountsDistinctQueryTermsFoundInTheChunk() {
        // Four distinct terms (france, capital, coupon, redemptions). Only coupon and redemptions are in the "coupon" chunk.
        KeywordHit hit = index.search("france capital coupon redemptions", 3).get(0);
        assertEquals("coupon", hit.document().getId());
        assertEquals(4, hit.queryTerms());
        assertEquals(2, hit.matchedTerms());
        assertEquals(0.5, hit.termCoverage(), 1e-9);
    }

    @Test
    void aQuestionWithNoMatchingWordFindsNothing() {
        assertTrue(index.search("capital of France", 5).isEmpty());
    }

    @Test
    void searchSyntaxInTheQuestionHasNoSpecialMeaning() {
        // The text is analysed into plain terms, never parsed as a Lucene query.
        assertDoesNotThrow(() -> index.search("coupon AND (redemptions OR \"month\") * ~2 -shopper field:text", 5));
        assertDoesNotThrow(() -> index.search("(((", 5));
        assertTrue(index.search("*:*", 5).isEmpty(), "a match-everything query must not match everything");
    }

    @Test
    void anEmptyIndexOrBlankQueryReturnsNothing() {
        assertTrue(new Bm25Index().search("coupon", 5).isEmpty());
        assertTrue(index.search("   ", 5).isEmpty());
        assertTrue(index.search(null, 5).isEmpty());
        assertTrue(index.search("coupon", 0).isEmpty());
    }

    @Test
    void replaceAllSwapsTheWholeIndex() {
        assertEquals(5, index.size());
        index.replaceAll(List.of(chunk("new", "Totally different content about parking.")));
        assertEquals(1, index.size());
        assertTrue(index.search("coupon restrictions", 5).isEmpty());
        assertEquals("new", index.search("parking", 5).get(0).document().getId());
        index.replaceAll(List.of());
        assertEquals(0, index.size());
    }

    @Test
    void hitsKeepTheOriginalChunkAndItsMetadata() {
        Document found = index.search("AllowPartialRefund", 1).get(0).document();
        assertEquals("flag.md", found.getMetadata().get("source"));
        assertTrue(found.getText().contains("AllowPartialRefund"));
    }
}
