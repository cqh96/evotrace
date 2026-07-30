package io.evotrace.server.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads versioned prompt templates from the evotrace-ai-prompts classpath.
 * Templates are StringTemplate (.st) files under {@code classpath:/prompts/}.
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptLoader(ResourcePatternResolver resolver) {
        try {
            Resource[] resources = resolver.getResources("classpath*:/prompts/*.st");
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name != null) {
                    String content = r.getContentAsString(StandardCharsets.UTF_8);
                    cache.put(name.replace(".st", ""), content);
                    log.info("loaded prompt template: {}", name);
                }
            }
        } catch (IOException e) {
            log.warn("failed to load prompt templates: {}", e.getMessage());
        }
    }

    /**
     * Get a prompt template by name (without .st extension).
     */
    public String get(String name) {
        String template = cache.get(name);
        if (template == null) {
            throw new IllegalArgumentException("prompt template not found: " + name);
        }
        return template;
    }

    /**
     * Fill template placeholders with values.
     * Supports {@code {key}} style placeholders.
     */
    public String fill(String templateName, Map<String, String> vars) {
        String template = get(templateName);
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
