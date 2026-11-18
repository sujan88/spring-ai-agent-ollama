package com.example.agent.rag.load;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Chunking rules of KnowledgeBaseLoader. Plain JUnit: no Spring context, database or API key needed. */
class KnowledgeBaseLoaderChunkingTest {

    private static final List<String> DOCS = List.of(
            "returns-flow.md", "returns-policy.md", "shipping.md");
    private static final String FENCE = "`".repeat(3);

    private static String read(String name) throws IOException {
        return new ClassPathResource("docs/kb/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private static long fenceLines(String chunk) {
        return chunk.lines().filter(l -> l.stripLeading().startsWith(FENCE)).count();
    }

    @Test
    void docsAreReadAsUtf8WithoutGarbledDashes() throws IOException {
        String text = read("returns-flow.md");
        assertTrue(text.contains("—"), "the em dash in the headings must survive");
        assertFalse(text.contains("鈥"), "a wrong charset turns em dashes into this");
        assertFalse(text.contains("\uFFFD"));
    }

    @Test
    void noChunkEverCutsAFencedBlock() throws IOException {
        for (String name : DOCS) {
            for (String chunk : KnowledgeBaseLoader.splitMarkdown(read(name), KnowledgeBaseLoader.MAX_CHUNK_CHARS)) {
                assertEquals(0, fenceLines(chunk) % 2, name + " has a chunk with an unbalanced code fence:\n" + chunk);
            }
        }
    }

    @Test
    void everyDiagramStaysInOneChunkWithItsHeader() throws IOException {
        String text = read("returns-flow.md");
        List<String> chunks = KnowledgeBaseLoader.splitMarkdown(text, KnowledgeBaseLoader.MAX_CHUNK_CHARS);
        long diagramsInDoc = text.lines().filter(l -> l.strip().equals(FENCE + "mermaid")).count();
        long chunksWithDiagram = chunks.stream().filter(c -> c.contains(FENCE + "mermaid")).count();
        assertEquals(2, diagramsInDoc);
        // Each diagram appears whole: opening fence and "flowchart TD" header in the same chunk.
        assertTrue(chunksWithDiagram >= 1);
        for (String c : chunks) {
            if (c.contains("flowchart TD")) {
                assertTrue(c.contains(FENCE + "mermaid"), "diagram header without its opening fence");
            }
        }
        assertEquals(diagramsInDoc, chunks.stream()
                .mapToLong(c -> c.lines().filter(l -> l.strip().equals("flowchart TD")).count()).sum());
    }

    @Test
    void chunksRespectTheSizeLimitAndNoTextIsLost() throws IOException {
        String text = read("returns-flow.md");
        List<String> chunks = KnowledgeBaseLoader.splitMarkdown(text, KnowledgeBaseLoader.MAX_CHUNK_CHARS);
        chunks.forEach(c -> assertTrue(c.length() <= KnowledgeBaseLoader.MAX_CHUNK_CHARS, "chunk too long: " + c.length()));
        String joined = String.join("", chunks).replaceAll("\\s+", "");
        assertEquals(text.replaceAll("\\s+", ""), joined, "splitting must not drop or change any text");
    }

    @Test
    void chunkIdsAreStableValidUuidsAndUniquePerChunk() throws IOException {
        String text = read("returns-policy.md");
        List<Document> first = KnowledgeBaseLoader.toChunks("returns-policy.md", "RETURNS-POLICY", text, "h1");
        List<Document> second = KnowledgeBaseLoader.toChunks("returns-policy.md", "RETURNS-POLICY", text, "h1");

        assertEquals(first.stream().map(Document::getId).toList(), second.stream().map(Document::getId).toList(),
                "re-ingesting must produce the same ids so the vector store overwrites instead of duplicating");
        Set<String> ids = first.stream().map(Document::getId).collect(Collectors.toSet());
        assertEquals(first.size(), ids.size(), "ids must be unique per chunk");
        ids.forEach(UUID::fromString); // PGvector ids are UUIDs
        assertNotEquals(KnowledgeBaseLoader.chunkId("a.md", 0), KnowledgeBaseLoader.chunkId("b.md", 0));
    }

    @Test
    void chunksCarryTheMetadataTheLoaderRelyOn() throws IOException {
        List<Document> chunks = KnowledgeBaseLoader.toChunks(
                "returns-flow.md", "RETURNS-FLOW", read("returns-flow.md"), "abc123");
        for (int i = 0; i < chunks.size(); i++) {
            var md = chunks.get(i).getMetadata();
            assertEquals("returns-flow.md", md.get("source"));
            assertEquals("RETURNS-FLOW", md.get("component"));
            assertEquals("abc123", md.get("contentHash"));
            assertEquals(i, md.get("chunkIndex"));
        }
    }

    @Test
    void aHeadingInsideACodeFenceIsNotASplitPoint() {
        String md = "## One\ntext\n" + FENCE + "\n## not a heading\nx\n" + FENCE + "\n## Two\nmore";
        List<String> chunks = KnowledgeBaseLoader.splitMarkdown(md, 30);
        for (String c : chunks) assertEquals(0, fenceLines(c) % 2, c);
    }
}
