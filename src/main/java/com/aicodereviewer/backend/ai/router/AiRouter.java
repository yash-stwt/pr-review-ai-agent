package com.aicodereviewer.backend.ai.router;

import com.aicodereviewer.backend.ai.cache.AiResponseCache;
import com.aicodereviewer.backend.ai.exception.AiProviderException;
import com.aicodereviewer.backend.ai.exception.ProviderUnavailableException;
import com.aicodereviewer.backend.ai.model.AiRequest;
import com.aicodereviewer.backend.ai.model.NormalizedResponse;
import com.aicodereviewer.backend.ai.port.AiProviderPort;
import com.aicodereviewer.backend.ai.registry.ProviderRegistry;
import com.aicodereviewer.backend.ai.tracker.TokenUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes AI requests to the appropriate provider based on the configured strategy.
 * Handles retry with exponential backoff and fallback to alternative providers.
 */
@Component
public class AiRouter {

    private static final Logger log = LoggerFactory.getLogger(AiRouter.class);

    private final ProviderRegistry registry;
    private final TokenUsageTracker tracker;
    private final AiResponseCache cache;
    private final RoutingStrategy strategy;
    private final Map<String, String> taskToProvider;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public AiRouter(
            ProviderRegistry registry,
            TokenUsageTracker tracker,
            AiResponseCache cache,
            @Value("${ai.routing.strategy:TASK_BASED}") String strategyStr,
            @Value("${ai.routing.task.deep-review:groq}") String deepReviewProvider,
            @Value("${ai.routing.task.fast-analysis:groq}") String fastAnalysisProvider,
            @Value("${ai.routing.task.security-review:groq}") String securityReviewProvider,
            @Value("${ai.routing.task.fix-generation:groq}") String fixGenerationProvider
    ) {
        this.registry = registry;
        this.tracker = tracker;
        this.cache = cache;
        this.strategy = parseStrategy(strategyStr);
        this.taskToProvider = Map.of(
                "deep-review", deepReviewProvider,
                "fast-analysis", fastAnalysisProvider,
                "security-review", securityReviewProvider,
                "fix-generation", fixGenerationProvider
        );
    }

    /**
     * Route an AI request through the provider chain with retry and fallback.
     * If request.preferredProviderId() is set, that provider is tried 3 times first
     * before falling back to other configured providers.
     */
    public NormalizedResponse route(AiRequest request) {
        // 1. Check cache
        String cacheProviderId = request.preferredProviderId() != null
                ? request.preferredProviderId()
                : getPrimaryProviderId(request);
        Optional<NormalizedResponse> cached = cache.get(request.userPrompt(), cacheProviderId, request.taskType());
        if (cached.isPresent()) {
            log.debug("Cache hit for taskType={}, provider={}", request.taskType(), cacheProviderId);
            return cached.get();
        }

        // 2. Build fallback chain — preferred provider goes first with 3 retries
        List<AiProviderPort> chain = buildFallbackChain(request);

        // 3. Try each provider with retries
        AiProviderException lastException = null;
        for (AiProviderPort provider : chain) {
            String providerId = provider.getMetadata().providerId();
            // User-selected provider gets 3 retries; others use their configured maxRetries
            boolean isPreferred = providerId.equals(request.preferredProviderId());
            int maxRetries = isPreferred ? 3 : provider.getMetadata().maxRetries();

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (attempt > 0) {
                        long sleepMs = 1000L * attempt;
                        log.info("Retrying provider={}, attempt={}/{}, backoff={}ms", providerId, attempt, maxRetries, sleepMs);
                        Thread.sleep(sleepMs);
                    }

                    NormalizedResponse response = provider.call(request);

                    // Success — record and cache
                    tracker.record(response);
                    cache.put(request.userPrompt(), providerId, request.taskType(), response);
                    log.info("AI call success: provider={}, taskType={}, latency={}ms, tokens={}",
                            providerId, request.taskType(), response.latencyMs(),
                            response.tokenUsage().totalTokens());
                    return response;

                } catch (AiProviderException ex) {
                    lastException = ex;
                    if (!ex.isRetryable()) {
                        log.warn("Non-retryable error from provider={}: {}", providerId, ex.getMessage());
                        break;
                    }
                    if (attempt == maxRetries) {
                        log.warn("Provider={} exhausted {} retries, falling back", providerId, maxRetries);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ProviderUnavailableException("Interrupted during retry backoff", ex);
                }
            }
        }

        throw new ProviderUnavailableException(
                "All providers exhausted for taskType=" + request.taskType()
                        + (lastException != null ? ". Last error: " + lastException.getMessage() : "")
        );
    }

    /**
     * Build the ordered fallback chain.
     * If preferredProviderId is set, it goes first regardless of configured status
     * (the user explicitly chose it — let it fail naturally if not configured).
     * Remaining configured providers follow as fallback sorted by cost.
     */
    List<AiProviderPort> buildFallbackChain(AiRequest request) {
        List<AiProviderPort> available = registry.getAvailable();
        String preferredId = request.preferredProviderId();

        // If user selected a specific provider, put it first
        if (preferredId != null && !preferredId.isBlank()) {
            // Look in ALL providers (not just configured) — user explicitly chose this
            Optional<AiProviderPort> preferred = registry.getById(preferredId);

            if (preferred.isPresent()) {
                List<AiProviderPort> chain = new ArrayList<>();
                chain.add(preferred.get());
                // Fallback: remaining CONFIGURED providers sorted by cost
                available.stream()
                        .filter(p -> !p.getMetadata().providerId().equals(preferredId))
                        .sorted(Comparator.comparingDouble(p -> p.getMetadata().estimatedCostPer1kTokens()))
                        .forEach(chain::add);
                log.info("Using preferred provider '{}' first, with {} fallback(s)", preferredId, chain.size() - 1);
                return chain;
            }
            log.warn("Preferred provider '{}' not found in registry, using strategy-based routing", preferredId);
        }

        if (available.isEmpty()) {
            throw new ProviderUnavailableException("No configured AI providers available");
        }

        return switch (strategy) {
            case TASK_BASED -> buildTaskBasedChain(request, available);
            case COST_OPTIMIZED -> buildCostOptimizedChain(available);
            case ROUND_ROBIN -> buildRoundRobinChain(available);
        };
    }

    private List<AiProviderPort> buildTaskBasedChain(AiRequest request, List<AiProviderPort> available) {
        String primaryId = taskToProvider.getOrDefault(request.taskType(), "groq");
        List<AiProviderPort> chain = new ArrayList<>();

        // Primary provider first
        available.stream()
                .filter(p -> p.getMetadata().providerId().equals(primaryId))
                .findFirst()
                .ifPresent(chain::add);

        // Fallback: others sorted by cost ascending
        available.stream()
                .filter(p -> !p.getMetadata().providerId().equals(primaryId))
                .sorted(Comparator.comparingDouble(p -> p.getMetadata().estimatedCostPer1kTokens()))
                .forEach(chain::add);

        // If primary wasn't found, chain is just cost-sorted
        if (chain.isEmpty()) {
            return buildCostOptimizedChain(available);
        }

        return chain;
    }

    private List<AiProviderPort> buildCostOptimizedChain(List<AiProviderPort> available) {
        return available.stream()
                .sorted(Comparator.comparingDouble(p -> p.getMetadata().estimatedCostPer1kTokens()))
                .toList();
    }

    private List<AiProviderPort> buildRoundRobinChain(List<AiProviderPort> available) {
        int index = roundRobinCounter.getAndIncrement() % available.size();
        List<AiProviderPort> chain = new ArrayList<>(available.size());
        for (int i = 0; i < available.size(); i++) {
            chain.add(available.get((index + i) % available.size()));
        }
        return chain;
    }

    private String getPrimaryProviderId(AiRequest request) {
        return taskToProvider.getOrDefault(request.taskType(), "groq");
    }

    private RoutingStrategy parseStrategy(String str) {
        try {
            return RoutingStrategy.valueOf(str.toUpperCase());
        } catch (Exception e) {
            log.warn("Unknown routing strategy '{}', defaulting to TASK_BASED", str);
            return RoutingStrategy.TASK_BASED;
        }
    }
}
