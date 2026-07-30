package io.evotrace.server.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Structured output from the AI change-summary prompt.
 * Maps to the JSON schema defined in change-summary.st.
 */
public record ChangeSummaryResult(
        @JsonProperty("summary") String summary,
        @JsonProperty("businessImpact") String businessImpact,
        @JsonProperty("changeCategory") String changeCategory,
        @JsonProperty("riskHints") List<String> riskHints,
        @JsonProperty("confidence") BigDecimal confidence
) {}
