package com.example.agent.rag;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** The golden question set used to measure retrieval and answer quality. */
public record GoldenSet(int version, String description, List<GoldenQuestion> questions) {

    /** One golden question. Out-of-scope questions have answerable=false and no expected sources. */
    public record GoldenQuestion(String id,
                          boolean answerable,
                          String question,
                          List<String> expectedSources,
                          List<String> expectedPhrases,
                          String referenceAnswer) {
        public GoldenQuestion {
            expectedSources = expectedSources == null ? List.of() : List.copyOf(expectedSources);
            expectedPhrases = expectedPhrases == null ? List.of() : List.copyOf(expectedPhrases);
        }
    }

    static final String RESOURCE = "/rag-eval/golden-questions.json";

    public static GoldenSet load() {
        try (InputStream in = GoldenSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing test resource " + RESOURCE);
            return JsonMapper.builder().build().readValue(in, GoldenSet.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    public List<GoldenQuestion> answerable() {
        return questions.stream().filter(GoldenQuestion::answerable).toList();
    }

    public List<GoldenQuestion> outOfScope() {
        return questions.stream().filter(q -> !q.answerable()).toList();
    }
}
