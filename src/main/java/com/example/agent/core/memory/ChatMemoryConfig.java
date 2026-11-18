package com.example.agent.core.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chat memory with a sliding window.
 *
 * The repository is Spring AI's JdbcChatMemoryRepository on the same Postgres that holds the vectors
 * (table SPRING_AI_CHAT_MEMORY, created by spring.ai.chat.memory.repository.jdbc.initialize-schema).
 * Every message is stored; only the last N are sent to the model, which keeps cost and latency flat
 * in long conversations.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository,
                                 @Value("${app.chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }
}
