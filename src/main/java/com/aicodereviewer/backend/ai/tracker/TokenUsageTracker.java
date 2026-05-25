package com.aicodereviewer.backend.ai.tracker;

import com.aicodereviewer.backend.ai.model.NormalizedResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory rolling token usage tracker per provider.
 */
@Component
public class TokenUsageTracker {

    private final ConcurrentHashMap<String, ProviderUsageStats> stats = new ConcurrentHashMap<>();

    public void record(NormalizedResponse response) {
        if (response == null || response.tokenUsage() == null) return;

        stats.computeIfAbsent(response.providerId(), k -> new ProviderUsageStats())
                .record(response);
    }

    public Map<String, ProviderUsageStats> getAll() {
        return Map.copyOf(stats);
    }

    public ProviderUsageStats getForProvider(String providerId) {
        return stats.getOrDefault(providerId, new ProviderUsageStats());
    }

    public static class ProviderUsageStats {
        private final AtomicLong totalPromptTokens = new AtomicLong(0);
        private final AtomicLong totalCompletionTokens = new AtomicLong(0);
        private final AtomicLong totalTokens = new AtomicLong(0);
        private final AtomicLong callCount = new AtomicLong(0);
        private final AtomicLong totalLatencyMs = new AtomicLong(0);

        public void record(NormalizedResponse response) {
            totalPromptTokens.addAndGet(response.tokenUsage().promptTokens());
            totalCompletionTokens.addAndGet(response.tokenUsage().completionTokens());
            totalTokens.addAndGet(response.tokenUsage().totalTokens());
            callCount.incrementAndGet();
            totalLatencyMs.addAndGet(response.latencyMs());
        }

        public long getTotalPromptTokens() { return totalPromptTokens.get(); }
        public long getTotalCompletionTokens() { return totalCompletionTokens.get(); }
        public long getTotalTokens() { return totalTokens.get(); }
        public long getCallCount() { return callCount.get(); }
        public long getAverageLatencyMs() {
            long count = callCount.get();
            return count == 0 ? 0 : totalLatencyMs.get() / count;
        }
    }
}
