package com.example.agent.rag.rerank;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Cohere v2 rerank call: request shape, response mapping, and error handling. */
class CohereRerankerTest {

    private final RagRerankSettings settings = new RagRerankSettings(true, "rerank-test", "https://api.cohere.com", 10, 0.0, 1000);

    private CohereReranker reranker(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return new CohereReranker(settings, "secret-key", builder);
    }

    @Test
    void sendsModelQueryAndDocumentsAndMapsTheResults() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CohereReranker reranker = reranker(server);
        server[0].expect(requestTo("https://api.cohere.com/v2/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andExpect(jsonPath("$.model").value("rerank-test"))
                .andExpect(jsonPath("$.query").value("return rules"))
                .andExpect(jsonPath("$.documents[1]").value("second"))
                .andExpect(jsonPath("$.top_n").value(2))
                .andRespond(withSuccess("{\"results\":[{\"index\":1,\"relevance_score\":0.91},{\"index\":0,\"relevance_score\":0.12}]}",
                        MediaType.APPLICATION_JSON));

        List<Reranker.Scored> result = reranker.rerank("return rules", List.of("first", "second", "third"), 2);

        assertEquals(List.of(new Reranker.Scored(1, 0.91), new Reranker.Scored(0, 0.12)), result);
        server[0].verify();
    }

    @Test
    void topNIsCappedAtTheNumberOfDocuments() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CohereReranker reranker = reranker(server);
        server[0].expect(requestTo("https://api.cohere.com/v2/rerank"))
                .andExpect(jsonPath("$.top_n").value(1))
                .andRespond(withSuccess("{\"results\":[{\"index\":0,\"relevance_score\":0.5}]}", MediaType.APPLICATION_JSON));

        reranker.rerank("q", List.of("only"), 3);

        server[0].verify();
    }

    @Test
    void serverErrorSurfacesAsAnException() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CohereReranker reranker = reranker(server);
        server[0].expect(requestTo("https://api.cohere.com/v2/rerank")).andRespond(withServerError());

        assertThrows(RuntimeException.class, () -> reranker.rerank("q", List.of("a"), 1));
    }

    @Test
    void indexOutsideTheRequestIsRejected() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CohereReranker reranker = reranker(server);
        server[0].expect(requestTo("https://api.cohere.com/v2/rerank"))
                .andRespond(withSuccess("{\"results\":[{\"index\":7,\"relevance_score\":0.9}]}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> reranker.rerank("q", List.of("a", "b"), 2));
    }

    @Test
    void missingResultsArrayIsRejected() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        CohereReranker reranker = reranker(server);
        server[0].expect(requestTo("https://api.cohere.com/v2/rerank"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> reranker.rerank("q", List.of("a"), 1));
    }
}
