package com.example.agent.rag;

import com.example.agent.rag.GoldenSet.GoldenQuestion;
import com.example.agent.rag.load.KnowledgeBaseLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end retrieval on a real PGvector (Testcontainers) with offline embeddings:
 * ingestion, idempotent re-ingestion, hybrid retrieval with gates, on the golden set.
 * Needs Docker; skipped when Docker is not available. Runs in `mvn verify` and in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class RagIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static final GoldenSet GOLDEN = GoldenSet.load();
    private static JdbcTemplate jdbc;
    private static PgVectorStore store;
    private static Bm25Index keywordIndex;
    private static KnowledgeBaseLoader loader;
    private static HybridDocumentRetriever retriever;

    @BeforeAll
    static void ingest() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        store = PgVectorStore.builder(jdbc, new HashingEmbeddingModel())
                .dimensions(HashingEmbeddingModel.DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet();
        keywordIndex = new Bm25Index();
        loader = new KnowledgeBaseLoader(store, keywordIndex, KnowledgeBaseLoader.DEFAULT_DOCS_PATTERN, false, null, "RagIT");
        loader.load();
        retriever = new HybridDocumentRetriever(store, keywordIndex, RagSearchSettings.defaults(),
                RagHybridSettings.defaults().enabledCopy());
    }

    private static int rows() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void everyDocumentIsIngestedWithItsMetadata() {
        assertTrue(rows() >= 3, "expected at least one chunk per document, got " + rows());
        assertEquals(rows(), keywordIndex.size(), "vector store and keyword index hold the same chunks");
    }

    @Test
    void reIngestingUnchangedDocsWritesNothingNew() {
        int before = rows();
        loader.load();
        assertEquals(before, rows(), "unchanged files must be skipped by content hash, not duplicated");
    }

    @Test
    void hybridRetrievalFindsTheExpectedSourceForAnswerableQuestions() {
        List<String> missed = new ArrayList<>();
        for (GoldenQuestion q : GOLDEN.answerable()) {
            List<Document> top = retriever.retrieve(new Query(q.question()));
            boolean hit = top.stream().anyMatch(d -> q.expectedSources().contains(String.valueOf(d.getMetadata().get("source"))));
            if (!hit) missed.add(q.id());
        }
        double hitRate = 1.0 - (double) missed.size() / GOLDEN.answerable().size();
        System.out.printf("RagIT hybrid hit@%d = %.2f, missed %s%n", RagSearchSettings.DEFAULT_TOP_K, hitRate, missed);
        assertTrue(hitRate >= 0.8, "hit rate " + hitRate + ", missed " + missed);
    }

    @Test
    void outOfScopeQuestionsRetrieveNothing() {
        for (GoldenQuestion q : GOLDEN.outOfScope()) {
            assertTrue(retriever.retrieve(new Query(q.question())).isEmpty(), q.id() + " should retrieve nothing");
        }
    }
}
