package com.example.agent.rag;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory BM25 keyword index over the knowledge-base chunks, built on Apache Lucene.
 *
 * Why it exists: vector search blurs exact tokens. The docs are full of identifiers such as
 * RETURN_WINDOW_EXPIRED, orderId or SKU-1042, which
 * BM25 matches exactly. HybridDocumentRetriever merges the two result lists.
 *
 * Analysis: the standard tokenizer, then identifiers are split on case changes, digits and
 * punctuation (hasMultiRatePlans gives has, multi, rate, plans, and the whole word is kept too),
 * then lower-casing, stop words (English plus question words) and Porter stemming (restrictions and restriction match).
 *
 * The user's text is never parsed as Lucene query syntax. It is analysed into plain terms, so
 * operators such as AND, quotes or wildcards in a chat message have no special meaning.
 *
 * The index is rebuilt from the same chunks the loader writes to the vector store, on every
 * start. It holds a few dozen chunks, so this takes milliseconds and needs no persistence.
 */
@Component
public class Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(Bm25Index.class);
    private static final String ID = "id";
    private static final String TEXT = "text";

    /** One keyword hit: the chunk, its BM25 score, and how many of the query's distinct terms it contains. */
    public record KeywordHit(Document document, double score, int matchedTerms, int queryTerms) {
        /** Share of the query's distinct terms found in this chunk, from 0 to 1. */
        public double termCoverage() {
            return queryTerms == 0 ? 0.0 : (double) matchedTerms / queryTerms;
        }
    }

    /** Everything a search needs, swapped in as one immutable unit so readers never see a half-built index. */
    private record Snapshot(IndexSearcher searcher, Map<String, Document> docsById, Map<String, Set<String>> termsById) {
        static final Snapshot EMPTY = new Snapshot(null, Map.of(), Map.of());
    }

    /**
     * Lucene's default English stop list has only 33 words and keeps question words such as
     * "which", "does" and "what". Left in, they make an unrelated question look like a partial
     * match ("Which payment processors does the checkout support?" matched 3 of 6 terms through
     * "which", "does" and "support"). The extras are question words, auxiliary verbs and pronouns.
     */
    private static final CharArraySet STOP_WORDS = buildStopWords();

    private static CharArraySet buildStopWords() {
        CharArraySet words = new CharArraySet(128, true);
        words.addAll(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);
        words.addAll(List.of(
                "what", "which", "who", "whom", "whose", "when", "where", "why", "how",
                "do", "does", "did", "doing", "done", "can", "could", "would", "should", "shall", "may", "might", "must",
                "have", "has", "had", "having", "been", "being", "am", "were",
                "i", "me", "my", "we", "us", "our", "you", "your", "he", "she", "him", "her", "his", "its", "them",
                "about", "from", "also", "any", "some", "than", "too", "very", "just", "so", "other"));
        return words;
    }

    private final Analyzer analyzer = newAnalyzer();
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** Replaces the whole index. Chunks need a stable id and text; the id is what links a hit to a vector result. */
    public synchronized void replaceAll(List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            snapshot = Snapshot.EMPTY;
            return;
        }
        Map<String, Document> docsById = new HashMap<>();
        Map<String, Set<String>> termsById = new HashMap<>();
        try {
            ByteBuffersDirectory directory = new ByteBuffersDirectory();
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                for (Document chunk : chunks) {
                    if (chunk.getText() == null || chunk.getText().isBlank()) continue;
                    org.apache.lucene.document.Document lucene = new org.apache.lucene.document.Document();
                    lucene.add(new StringField(ID, chunk.getId(), Field.Store.YES));
                    lucene.add(new TextField(TEXT, chunk.getText(), Field.Store.NO));
                    writer.addDocument(lucene);
                    docsById.put(chunk.getId(), chunk);
                    termsById.put(chunk.getId(), new HashSet<>(analyze(chunk.getText())));
                }
            }
            snapshot = new Snapshot(new IndexSearcher(DirectoryReader.open(directory)), docsById, termsById);
            log.info("Bm25Index: indexed {} chunks", docsById.size());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the BM25 index", e);
        }
    }

    public int size() {
        return snapshot.docsById().size();
    }

    /** Best-first BM25 hits for the query, at most maxHits. Empty when the index is empty or no term matches. */
    public List<KeywordHit> search(String query, int maxHits) {
        Snapshot snap = snapshot;
        if (snap.searcher() == null || query == null || query.isBlank() || maxHits < 1) return List.of();

        Set<String> terms = new LinkedHashSet<>(analyze(query));
        if (terms.isEmpty()) return List.of();

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String term : terms) {
            builder.add(new TermQuery(new Term(TEXT, term)), BooleanClause.Occur.SHOULD);
        }
        try {
            List<KeywordHit> hits = new ArrayList<>();
            for (ScoreDoc scoreDoc : snap.searcher().search(builder.build(), maxHits).scoreDocs) {
                String id = snap.searcher().storedFields().document(scoreDoc.doc).get(ID);
                Document chunk = snap.docsById().get(id);
                if (chunk == null) continue;
                Set<String> chunkTerms = snap.termsById().getOrDefault(id, Set.of());
                int matched = (int) terms.stream().filter(chunkTerms::contains).count();
                hits.add(new KeywordHit(chunk, scoreDoc.score, matched, terms.size()));
            }
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException("BM25 search failed", e);
        }
    }

    /** The analysed terms of a text, in order, with duplicates. Package-private for tests. */
    List<String> analyze(String text) {
        List<String> out = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(TEXT, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) out.add(term.toString());
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static Analyzer newAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new StandardTokenizer();
                int flags = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
                        | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
                        | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
                        | WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
                        | WordDelimiterGraphFilter.PRESERVE_ORIGINAL;
                TokenStream stream = new WordDelimiterGraphFilter(source, flags, null);
                stream = new FlattenGraphFilter(stream);
                stream = new LowerCaseFilter(stream);
                stream = new StopFilter(stream, STOP_WORDS);
                stream = new PorterStemFilter(stream);
                return new TokenStreamComponents(source, stream);
            }
        };
    }
}
