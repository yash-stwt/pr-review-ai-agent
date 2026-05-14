package com.aicodereviewer.backend.ai.model;

/**
 * Static metadata about an AI provider, used for routing and display.
 */
public record ProviderMetadata(
        String providerId,
        String displayName,
        String modelId,
        double estimatedCostPer1kTokens,
        int timeoutSeconds,
        int maxRetries
) {}
