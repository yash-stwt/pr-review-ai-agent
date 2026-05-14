package com.aicodereviewer.backend.ai.port;

import com.aicodereviewer.backend.ai.exception.AiProviderException;
import com.aicodereviewer.backend.ai.model.AiRequest;
import com.aicodereviewer.backend.ai.model.NormalizedResponse;
import com.aicodereviewer.backend.ai.model.ProviderMetadata;

/**
 * Common interface for all AI provider adapters.
 * Each provider (Groq, Gemini, Claude, OpenAI) implements this interface.
 */
public interface AiProviderPort {

    /**
     * Send a prompt to the AI provider and return a normalized response.
     */
    NormalizedResponse call(AiRequest request) throws AiProviderException;

    /**
     * Return metadata about this provider (id, model, cost, timeouts).
     */
    ProviderMetadata getMetadata();

    /**
     * Whether this provider is properly configured (has a valid API key).
     * Providers returning false are excluded from the routing fallback chain.
     */
    default boolean isConfigured() {
        return true;
    }
}
