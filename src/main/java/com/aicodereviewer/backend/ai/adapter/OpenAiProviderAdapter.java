package com.aicodereviewer.backend.ai.adapter;

import com.aicodereviewer.backend.ai.exception.AiProviderException;
import com.aicodereviewer.backend.ai.model.AiRequest;
import com.aicodereviewer.backend.ai.model.NormalizedResponse;
import com.aicodereviewer.backend.ai.model.ProviderMetadata;
import com.aicodereviewer.backend.ai.port.AiProviderPort;
import org.springframework.stereotype.Component;

/**
 * Stub adapter for OpenAI — future-ready placeholder.
 * Throws immediately until configured.
 */
@Component
public class OpenAiProviderAdapter implements AiProviderPort {

    @Override
    public NormalizedResponse call(AiRequest request) throws AiProviderException {
        throw new AiProviderException("OpenAI provider not yet configured", false);
    }

    @Override
    public ProviderMetadata getMetadata() {
        return new ProviderMetadata("openai", "OpenAI", "gpt-4o", 0.002, 30, 0);
    }

    @Override
    public boolean isConfigured() {
        return false; // Stub — not configured until an API key is provided
    }
}
