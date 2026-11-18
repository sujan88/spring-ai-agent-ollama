package com.example.agent.rag.rrf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: the standard way to merge ranked lists whose scores cannot be compared.
 *
 * A document's fused score is the sum, over every list it appears in, of 1 / (k + rank), with
 * rank starting at 1. BM25 scores and cosine similarities live on different scales, so only the
 * ranks are used. A chunk that both lists rank highly beats a chunk that only one list ranks
 * first. k = 60 is the value from the original paper and what most search engines default to.
 */
public final class ReciprocalRankFusion {

    public static final int DEFAULT_K = 60;
    private static final Logger log = LoggerFactory.getLogger(ReciprocalRankFusion.class);

    private ReciprocalRankFusion() {
    }

    /**
     * Merges the rankings, matching documents by id. Each returned document carries its fused score.
     * Ties go to the document that ranked best in any single list, then to the smaller id, so the
     * order is the same on every run.
     */
    public static List<Document> fuse(List<List<Document>> rankings, int k) {

        if (k < 1) throw new IllegalArgumentException("k must be at least 1, was " + k);

        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> bestRank = new HashMap<>();
        Map<String, Document> documents = new LinkedHashMap<>();

        for (List<Document> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                Document document = ranking.get(i);
                int rank = i + 1;
                documents.putIfAbsent(document.getId(), document);
                scores.merge(document.getId(), 1.0 / (k + rank), Double::sum);
                bestRank.merge(document.getId(), rank, Math::min);
            }
        }

        List<String> ids = new ArrayList<>(documents.keySet());
        ids.sort(Comparator
                .comparing((String id) -> scores.get(id)).reversed()
                .thenComparing(bestRank::get)
                .thenComparing(Comparator.naturalOrder()));

        List<Document> fused = new ArrayList<>(ids.size());
        for (String id : ids) {
            fused.add(documents.get(id).mutate().score(scores.get(id)).build());
        }
        log.info("Merged results : {}", fused.size());
        return fused;
    }
}
