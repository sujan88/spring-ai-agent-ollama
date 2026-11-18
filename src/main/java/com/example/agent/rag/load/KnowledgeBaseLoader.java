package com.example.agent.rag.load;

import com.example.agent.rag.Bm25Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ingests every knowledge-base document matching app.rag.docs-pattern (default
 * classpath*:/docs/kb/*.md) into the vector store (PGvector by default) after startup.
 *
 * Each chunk carries "source" (the filename), "component", "chunkIndex" and "contentHash"
 * metadata so retrieved chunks can be traced back to their origin.
 *
 * Chunking is heading-aware: a document is cut at "## " headings and neighbouring sections
 * are packed together up to MAX_CHUNK_CHARS. A section is never split in the middle, so a
 * fenced block (for example a Mermaid diagram) always stays in one chunk. Only a single
 * section larger than the limit falls back to token-based splitting.
 *
 * Idempotency is per source file:
 *   - Chunk ids are derived from "source#index", so writing the same chunk twice overwrites it.
 *   - A source is skipped when the vector store already holds it with the same content hash. Editing a
 *     file, or adding a new file to the list, re-ingests just that file on the next start.
 *   - A changed source is written first and pruned afterwards (everything of that source that was
 *     not just written is deleted), so a shorter file leaves no stale chunks and a failed write
 *     never empties the knowledge base.
 *   - If the store or the embedding service cannot be reached to check a source, that source is
 *     left exactly as it is and tried again on the next start.
 *   - app.rag.force-reload=true re-embeds every source.
 *   - A file that was deleted from the folder has its chunks removed from the vector store. This cleanup is
 *     skipped when no file was found or a file could not be read, so a packaging mistake can never
 *     empty the knowledge base.
 *
 * To add a knowledge document, drop a .md file into src/main/resources/docs/kb/. No code change.
 * Each chunk's "component" is the file name without ".md" (returns-policy.md gives RETURNS-POLICY),
 * so you can filter by document family later.
 */
public class KnowledgeBaseLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseLoader.class);

    /** About 800 tokens. Small enough for precise retrieval, big enough to keep a section together. */
    public static final int MAX_CHUNK_CHARS = 3500;

    public static final String DEFAULT_DOCS_PATTERN = "classpath*:/docs/kb/*.md";

    private final VectorStore vectorStore;
    private final Bm25Index keywordIndex;
    private final String docsPattern;
    private final boolean forceReload;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /** Fixed component for every chunk, or null to derive it from the file name. */
    private final String component;

    /** Short name used in log lines, so two knowledge bases can be told apart. */
    private final String label;

    /**
     * Not a Spring bean on purpose: RagConfig builds it and runs {@link #load()} from an ApplicationRunner,
     * after the web server is up, so a slow embedding service never blocks startup. To add a second
     * knowledge base (another folder, another table), build a second loader with its own store and index.
     */
    public KnowledgeBaseLoader(VectorStore vectorStore, Bm25Index keywordIndex, String docsPattern,
                               boolean forceReload, String component, String label) {
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.docsPattern = docsPattern == null || docsPattern.isBlank() ? DEFAULT_DOCS_PATTERN : docsPattern;
        this.forceReload = forceReload;
        this.component = component;
        this.label = label;
    }

    public void load() {
        if (vectorStore == null) {
            log.info("{}: no vector store; knowledge base not loaded, RAG is off.", label);
            return;
        }
        List<Resource> docs = findDocs();
        if (docs.isEmpty()) {
            log.warn("{}: no documents match {}; leaving the vector store as it is.", label, docsPattern);
            return;
        }
        log.info("{}: {} document(s) match {}", label, docs.size(), docsPattern);
        // The deleted-file cleanup runs only if every file was read and checked or written this start.
        boolean allSettled = true;
        int ingested = 0;
        List<Document> keywordChunks = new ArrayList<>();
        for (Resource doc : docs) {
            String source = doc.getFilename();
            String text;
            try {
                // Read as UTF-8 explicitly. Tika guessed the wrong charset for some files and
                // turned em dashes into garbage characters.
                text = doc.getContentAsString(StandardCharsets.UTF_8);
            } catch (Exception ex) {
                allSettled = false;
                log.warn("{}: could not read {} ({}); it is kept as stored.", label, source, ex.getMessage());
                continue;
            }
            try {
                String hash = sha256(text);

                List<Document> chunks = toChunks(source, component != null ? component : componentFor(source), text, hash);
                // The keyword (BM25) index is cheap, so it is rebuilt from every doc on every start,
                // whether or not the vector store already holds the chunks.
                keywordChunks.addAll(chunks);

                if (!forceReload) {
                    Probe probe = probe(source, hash);
                    if (probe == Probe.CURRENT) {
                        log.info("{}: {} already ingested and unchanged; skipping. " +
                                "Set app.rag.force-reload=true to re-embed.", label, source);
                        continue;
                    }
                    if (probe == Probe.UNKNOWN) {
                        allSettled = false;
                        // The vector store or the embedding service did not answer. Writing now would fail
                        // too, so leave whatever is stored untouched and try again on the next start.
                        log.warn("{}: could not check {}; leaving the stored chunks as they are.", label, source);
                        continue;
                    }
                }

                // Write first, then prune. Chunk ids are stable, so this overwrites the chunks that still exist.
                // Deleting first would leave the knowledge base empty if the write then failed.
                vectorStore.add(chunks);
                pruneOldChunks(source, hash);
                ingested += chunks.size();
                log.info("{}: ingested {} chunks from {}", label, chunks.size(), source);
            } catch (Exception ex) {
                allSettled = false;
                // One bad file must not block the others. It is retried on the next start
                // because it will not be found with a matching hash.
                log.warn("{}: failed to ingest {} — RAG may be incomplete this session. " +
                        "The chat is unaffected. Error: {}", label, source, ex.getMessage());
            }
        }
        if (allSettled) pruneDeletedSources(docs.stream().map(Resource::getFilename).toList());
        if (keywordIndex != null) keywordIndex.replaceAll(keywordChunks);
        log.info("{}: done, {} chunks written this start, {} chunks in the keyword index.",
                label, ingested, keywordChunks.size());
    }

    /** Every file matching the pattern, sorted by name so the order is the same on every start. */
    private List<Resource> findDocs() {
        try {
            return Arrays.stream(resolver.getResources(docsPattern))
                    .filter(Resource::isReadable)
                    .filter(r -> r.getFilename() != null)
                    .sorted(Comparator.comparing(Resource::getFilename))
                    .toList();
        } catch (IOException e) {
            log.warn("{}: could not list {} ({}).", label, docsPattern, e.getMessage());
            return List.of();
        }
    }

    static String componentFor(String source) {
        String base = source.toLowerCase(Locale.ROOT).endsWith(".md") ? source.substring(0, source.length() - 3) : source;
        return base.toUpperCase(Locale.ROOT);
    }

    /**
     * Removes the chunks of files that are no longer in the folder. It first looks for one such chunk
     * and deletes only if it finds a chunk with a source name outside the current list, so an
     * unchanged knowledge base costs one lookup and no delete.
     */
    private void pruneDeletedSources(List<String> present) {
        String filter = "source nin [" + present.stream()
                .map(name -> "'" + name.replace("'", "") + "'")
                .collect(Collectors.joining(", ")) + "]";
        try {
            List<Document> orphan = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("document").topK(1).filterExpression(filter).build());
            Object source = orphan.isEmpty() ? null : orphan.get(0).getMetadata().get("source");
            if (source == null || present.contains(String.valueOf(source))) return;
            vectorStore.delete(filter);
            log.info("{}: removed the chunks of files no longer in the folder (for example {}).", label, source);
        } catch (Exception e) {
            log.warn("{}: could not remove chunks of deleted files ({}).", label, e.getMessage());
        }
    }

    /** What the vector store says about one source file. */
    private enum Probe {
        /** Stored with the same content hash: nothing to do. */
        CURRENT,
        /** Missing, or stored from an older version of the file: write it. */
        STALE,
        /** The store or the embedding service did not answer, so nothing can be said. */
        UNKNOWN
    }

    private Probe probe(String source, String hash) {
        try {
            List<Document> hit = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(source)
                    .topK(1)
                    .filterExpression("source == '" + source + "'")
                    .build());
            return !hit.isEmpty() && hash.equals(hit.get(0).getMetadata().get("contentHash"))
                    ? Probe.CURRENT
                    : Probe.STALE;
        } catch (Exception e) {
            log.warn("{}: probe for {} failed ({}).", label, source, e.getMessage());
            return Probe.UNKNOWN;
        }
    }

    /**
     * Removes every chunk of this source that was not written in this run: chunks beyond the end of
     * a file that got shorter, and chunks from versions written before chunks carried a content hash.
     */
    private void pruneOldChunks(String source, String hash) {
        try {
            vectorStore.delete("source == '" + source + "' && contentHash != '" + hash + "'");
        } catch (Exception e) {
            // Harmless: the new chunks are already written. An old chunk may linger until the next reload.
            log.warn("{}: could not prune old chunks of {} ({}).", label, source, e.getMessage());
        }
    }

    // ── chunking (package-private so it can be unit tested) ─────────────────

    public static List<Document> toChunks(String source, String component, String text, String hash) {
        List<String> pieces = splitMarkdown(text, MAX_CHUNK_CHARS);
        List<Document> docs = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            Map<String, Object> md = new HashMap<>();
            md.put("source", source);
            md.put("component", component);
            md.put("chunkIndex", i);
            md.put("contentHash", hash);
            docs.add(Document.builder()
                    .id(chunkId(source, i))
                    .text(pieces.get(i))
                    .metadata(md)
                    .build());
        }
        return docs;
    }

    /** Same source and index always give the same id, so re-writing a chunk overwrites it. */
    static String chunkId(String source, int index) {
        return UUID.nameUUIDFromBytes((source + "#" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Cuts at "## " headings (ignoring any that sit inside a code fence), then packs neighbouring
     * sections into chunks of at most maxChars. A section larger than maxChars is split by tokens.
     */
    public static List<String> splitMarkdown(String text, int maxChars) {
        List<String> sections = new ArrayList<>();
        StringBuilder section = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\\R", -1)) {
            if (line.stripLeading().startsWith("```")) inFence = !inFence;
            if (!inFence && line.startsWith("## ") && !section.isEmpty()) {
                sections.add(section.toString().strip());
                section.setLength(0);
            }
            section.append(line).append('\n');
        }
        if (!section.toString().isBlank()) sections.add(section.toString().strip());

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String s : sections) {
            if (s.isBlank()) continue;
            if (s.length() > maxChars) {
                flush(current, chunks);
                if (s.contains("```")) {
                    log.warn("KnowledgeBaseLoader: a section with a code fence is over {} chars and will be " +
                            "split by tokens, which can cut it. Start: {}", maxChars, firstLine(s));
                }
                new TokenTextSplitter().apply(List.of(new Document(s)))
                        .forEach(d -> chunks.add(d.getText()));
                continue;
            }
            if (!current.isEmpty() && current.length() + 2 + s.length() > maxChars) flush(current, chunks);
            if (!current.isEmpty()) current.append("\n\n");
            current.append(s);
        }
        flush(current, chunks);
        return chunks;
    }

    private static void flush(StringBuilder current, List<String> chunks) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
