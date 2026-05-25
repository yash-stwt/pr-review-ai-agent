package com.aicodereviewer.backend.controller;

import com.aicodereviewer.backend.ai.port.AiProviderPort;
import com.aicodereviewer.backend.ai.registry.ProviderRegistry;
import com.aicodereviewer.backend.ai.tracker.TokenUsageTracker;
import com.aicodereviewer.backend.dto.ProviderStatusResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoints for managing and querying AI providers.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderRegistry registry;
    private final TokenUsageTracker tracker;

    public ProviderController(ProviderRegistry registry, TokenUsageTracker tracker) {
        this.registry = registry;
        this.tracker = tracker;
    }

    /**
     * GET /api/providers — list ALL registered providers with status.
     * Unconfigured providers are shown with status "NOT_CONFIGURED" so the UI can display them.
     */
    @GetMapping
    public List<ProviderStatusResponse> listProviders() {
        return registry.getAll().stream()
                .map(this::toStatusResponse)
                .toList();
    }

    /**
     * POST /api/providers/select — validate that a provider exists.
     */
    @PostMapping("/select")
    public ResponseEntity<Map<String, String>> selectProvider(@RequestBody Map<String, String> body) {
        String providerId = body.get("providerId");
        if (providerId == null || providerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "providerId is required"));
        }
        boolean exists = registry.getById(providerId).isPresent();
        if (!exists) {
            return ResponseEntity.status(404).body(Map.of(
                    "errorCode", "PROVIDER_NOT_FOUND",
                    "message", "Provider '" + providerId + "' not found"
            ));
        }
        return ResponseEntity.ok(Map.of("selected", providerId));
    }

    /**
     * GET /api/providers/usage — token usage stats per provider.
     */
    @GetMapping("/usage")
    public Map<String, TokenUsageTracker.ProviderUsageStats> getUsage() {
        return tracker.getAll();
    }

    private ProviderStatusResponse toStatusResponse(AiProviderPort provider) {
        var meta = provider.getMetadata();
        var stats = tracker.getForProvider(meta.providerId());
        boolean configured = provider.isConfigured();
        String status = configured ? "AVAILABLE" : "NOT_CONFIGURED";
        return new ProviderStatusResponse(
                meta.providerId(),
                meta.displayName(),
                meta.modelId(),
                status,
                configured,
                stats.getAverageLatencyMs(),
                meta.estimatedCostPer1kTokens()
        );
    }
}
