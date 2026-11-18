package com.example.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, offline embeddings for integration tests: a hashed bag of the same analysed terms
 * BM25 uses (stop words removed, stemmed). Texts that share words get similar vectors; texts that
 * share none are nearly orthogonal. Good enough to test the pipeline end to end with no API key.
 */
class HashingEmbeddingModel implements EmbeddingModel {

    static final int DIMENSIONS = 256;
    private final Bm25Index analyzer = new Bm25Index();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) embeddings.add(new Embedding(vector(texts.get(i)), i));
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        for (String term : analyzer.analyze(text == null ? "" : text)) v[Math.floorMod(term.hashCode(), DIMENSIONS)] += 1f;
        double norm = 0;
        for (float x : v) norm += x * x;
        if (norm == 0) {
            v[0] = 1f; // a zero vector has no direction; cosine distance would be undefined
            return v;
        }
        float n = (float) Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) v[i] /= n;
        return v;
    }
}
