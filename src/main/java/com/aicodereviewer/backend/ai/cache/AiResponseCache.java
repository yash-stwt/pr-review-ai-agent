package com.aicodereviewer.backend.ai.cache;

import com.aicodereviewer.backend.ai.model.NormalizedResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LRU cache for AI responses. Max 100 entries, 10-minute TTL.
 * Key: sha256(diffText) + "|" + providerId + "|" + taskType
 */
@Component
public class AiResponseCache {

    private static final int MAX_ENTRIES = 100;
    private static final long TTL_MS = 10 * 60 * 1000L; // 10 minutes

    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>(
            MAX_ENTRIES, 0.75f, true
    ) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private record CacheEntry(NormalizedResponse response, long insertedAt) {}

    public synchronized Optional<NormalizedResponse> get(String diffText, String providerId, String taskType) {
        String key = buildKey(diffText, providerId, taskType);
        CacheEntry entry = cache.get(key);
        if (entry == null) return Optional.empty();
        if (System.currentTimeMillis() - entry.insertedAt() > TTL_MS) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    public synchronized void put(String diffText, String providerId, String taskType, NormalizedResponse response) {
        String key = buildKey(diffText, providerId, taskType);
        cache.put(key, new CacheEntry(response, System.currentTimeMillis()));
    }

    private String buildKey(String diffText, String providerId, String taskType) {
        String hash = DigestUtils.sha256Hex(diffText != null ? diffText : "");
        return hash + "|" + providerId + "|" + taskType;
    }
}
