package com.aicodereviewer.backend.ai.registry;

import com.aicodereviewer.backend.ai.port.AiProviderPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Registry of all available AI provider adapters.
 * Collects all AiProviderPort beans from the Spring context.
 */
@Component
public class ProviderRegistry {

    private final List<AiProviderPort> providers;

    public ProviderRegistry(List<AiProviderPort> providers) {
        this.providers = List.copyOf(providers);
    }

    public List<AiProviderPort> getAll() {
        return providers;
    }

    public Optional<AiProviderPort> getById(String providerId) {
        return providers.stream()
                .filter(p -> p.getMetadata().providerId().equals(providerId))
                .findFirst();
    }

    public List<AiProviderPort> getAvailable() {
        return providers.stream()
                .filter(AiProviderPort::isConfigured)
                .toList();
    }
}
