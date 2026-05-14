package com.aicodereviewer.backend.dto;

/**
 * Response DTO for GET /api/providers — one entry per registered provider.
 */
public record ProviderStatusResponse(
        String providerId,
        String displayName,
        String modelId,
        String status,                  // "AVAILABLE" | "NOT_CONFIGURED" | "UNAVAILABLE"
        boolean configured,             // true if API key is set
        long averageLatencyMs,
        double estimatedCostPer1kTokens
) {}
