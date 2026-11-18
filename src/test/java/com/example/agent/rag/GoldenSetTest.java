package com.example.agent.rag;

import com.example.agent.rag.GoldenSet.GoldenQuestion;
import com.example.agent.rag.load.KnowledgeBaseLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the golden set against the real documents and the real chunking, with no network.
 * If someone edits a doc so that an expected phrase disappears or is cut across two chunks,
 * this fails here instead of showing up as a mysterious retrieval regression later.
 */
class GoldenSetTest {

    private static final GoldenSet GOLDEN = GoldenSet.load();

    private static List<String> chunksOf(String source) throws IOException {
        String text = new ClassPathResource("docs/kb/" + source).getContentAsString(StandardCharsets.UTF_8);
        return KnowledgeBaseLoader.splitMarkdown(text, KnowledgeBaseLoader.MAX_CHUNK_CHARS);
    }

    @Test
    void hasEnoughQuestionsWithUniqueIds() {
        assertTrue(GOLDEN.answerable().size() >= 12, "need at least 12 answerable questions");
        assertTrue(GOLDEN.outOfScope().size() >= 4, "need at least 4 out-of-scope questions");
        Set<String> ids = new HashSet<>();
        for (GoldenQuestion q : GOLDEN.questions()) {
            assertTrue(ids.add(q.id()), "duplicate id " + q.id());
            assertFalse(q.question().isBlank(), q.id() + " has no question text");
        }
    }

    @Test
    void everyIndexedDocumentIsCoveredByAtLeastFourQuestions() {
        Map<String, Long> perSource = GOLDEN.answerable().stream()
                .flatMap(q -> q.expectedSources().stream())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        for (String doc : List.of("returns-policy.md", "shipping.md", "returns-flow.md")) {
            assertTrue(perSource.getOrDefault(doc, 0L) >= 4, doc + " is covered by too few questions: " + perSource);
        }
    }

    @Test
    void answerableQuestionsAreFullySpecified() {
        for (GoldenQuestion q : GOLDEN.answerable()) {
            assertFalse(q.expectedSources().isEmpty(), q.id() + " has no expected source");
            assertFalse(q.expectedPhrases().isEmpty(), q.id() + " has no expected phrases");
            assertNotNull(q.referenceAnswer(), q.id() + " has no reference answer");
            assertFalse(q.referenceAnswer().isBlank(), q.id() + " has a blank reference answer");
        }
    }

    @Test
    void outOfScopeQuestionsCarryNoExpectations() {
        for (GoldenQuestion q : GOLDEN.outOfScope()) {
            assertTrue(q.expectedSources().isEmpty(), q.id());
            assertTrue(q.expectedPhrases().isEmpty(), q.id());
        }
    }

    @Test
    void everyExpectedPhraseSitsInsideOneRealChunkOfItsSource() throws IOException {
        for (GoldenQuestion q : GOLDEN.answerable()) {
            for (String source : q.expectedSources()) {
                List<String> chunks = chunksOf(source); // also fails if the file does not exist
                for (String phrase : q.expectedPhrases()) {
                    boolean found = chunks.stream().anyMatch(c -> RagEvalMetrics.containsPhrase(c, phrase));
                    assertTrue(found, q.id() + ": phrase not found inside a single chunk of " + source + ": \"" + phrase + "\"");
                }
            }
        }
    }
}
