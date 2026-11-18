package com.example.agent;

import com.example.agent.chat.ChatService;
import com.example.agent.core.guard.GroundingChecker;
import com.example.agent.core.security.Caller;
import com.example.agent.model.ChatReply;
import com.example.agent.rag.GoldenSet;
import com.example.agent.rag.tool.KnowledgeBaseTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Answer-quality evaluation against a real model (cloud profile, OpenAI). Costs money, so it only
 * runs with:  OPENAI_API_KEY=... ./mvnw verify -Pevals
 *
 * Each answerable golden question goes through the real agent (tools, RAG, advisors). Two judges:
 *   - relevancy (Spring AI RelevancyEvaluator): does the answer address the question, given the context?
 *   - faithfulness (GroundingChecker, the same check the app runs live): is every policy claim
 *     supported by the retrieved chunks, i.e. no hallucinated rules?
 * Both pass rates are printed and must reach 80%.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("cloud")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class EvalIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired ChatService chat;
    @Autowired KnowledgeBaseTool knowledgeBase;
    @Autowired ChatClient.Builder judgeBuilder;
    @Autowired GroundingChecker groundingChecker;

    @Test
    void answersAreRelevantAndFaithfulForTheGoldenQuestions() {
        RelevancyEvaluator relevancy = RelevancyEvaluator.builder().chatClientBuilder(judgeBuilder).build();
        List<String> failed = new ArrayList<>();
        List<String> unfaithful = new ArrayList<>();
        int total = 0;
        for (GoldenSet.GoldenQuestion q : GoldenSet.load().answerable()) {
            total++;
            String id = q.id();
            String question = q.question();
            ChatReply reply = chat.call(new Caller("C-1001", "eval-" + id), question);
            List<Document> context = knowledgeBase.search(question, null, KnowledgeBaseTool.NOT_FOUND).chunks().stream()
                    .map(c -> new Document(c.text(), Map.of("source", c.source())))
                    .toList();
            boolean pass = relevancy.evaluate(new EvaluationRequest(question, context, reply.answer())).isPass();
            GroundingChecker.Verdict verdict = groundingChecker.check(question, context, reply.answer());
            boolean faithful = !verdict.checked() || verdict.supported();
            System.out.printf("EVAL %s relevancy=%s faithful=%s | %s%n", id, pass ? "PASS" : "FAIL",
                    faithful ? "PASS" : "FAIL (" + verdict.reason() + ")", reply.answer().replace('\n', ' '));
            if (!pass) failed.add(id);
            if (!faithful) unfaithful.add(id);
        }
        double passRate = 1.0 - (double) failed.size() / total;
        double faithfulness = 1.0 - (double) unfaithful.size() / total;
        System.out.printf("EVAL relevancy %.2f, faithfulness %.2f (%d questions), failed %s, unfaithful %s%n",
                passRate, faithfulness, total, failed, unfaithful);
        assertTrue(passRate >= 0.8, "relevancy " + passRate + ", failed " + failed);
        assertTrue(faithfulness >= 0.8, "faithfulness " + faithfulness + ", unfaithful " + unfaithful);
    }
}
