package io.evotrace.server.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Routes AI tasks to the configured ChatModel.
 * For MVP, uses a single ChatModel bean; multi-model routing
 * (e.g. light model for small diffs, flagship for release notes)
 * will be added in M2.
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ChatClient chatClient;
    private final String modelName;

    public ModelRouter(ChatModel chatModel,
                       @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String modelName) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.modelName = modelName;
        log.info("AI model router initialized with model: {}", modelName);
    }

    /**
     * Get a ChatClient for the given task type.
     * Future: route to different models based on task type or workspace config.
     */
    public ChatClient clientFor(String taskType) {
        return chatClient;
    }

    public String getModelName() {
        return modelName;
    }
}
