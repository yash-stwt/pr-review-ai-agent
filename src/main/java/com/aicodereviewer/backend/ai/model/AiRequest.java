package com.aicodereviewer.backend.ai.model;

/**
 * Unified request structure sent to any AI provider.
 *
 * @param systemPrompt        system-level instruction for the AI
 * @param userPrompt          the actual user prompt content
 * @param temperature         sampling temperature (0.0–1.0)
 * @param maxTokens           maximum tokens in the response
 * @param taskType            routing hint: "deep-review", "fast-analysis", "security-review", "fix-generation"
 * @param preferredProviderId optional user-selected provider — tried first, retried 3 times before fallback
 */
public record AiRequest(
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens,
        String taskType,
        String preferredProviderId
) {
    /** Backward-compatible constructor without preferred provider */
    public AiRequest(String systemPrompt, String userPrompt, double temperature, int maxTokens, String taskType) {
        this(systemPrompt, userPrompt, temperature, maxTokens, taskType, null);
    }
}
