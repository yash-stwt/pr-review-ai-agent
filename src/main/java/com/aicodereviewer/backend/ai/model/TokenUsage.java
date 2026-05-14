package com.aicodereviewer.backend.ai.model;

/**
 * Token consumption for a single AI call.
 */
public record TokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
) {}
