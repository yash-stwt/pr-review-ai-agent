package com.aicodereviewer.backend.ai.model;

/**
 * Normalized response from any AI provider, regardless of native format.
 */
public record NormalizedResponse(
        String content,
        TokenUsage tokenUsage,
        String providerId,
        String modelId,
        long latencyMs,
        String finishReason
) {}
