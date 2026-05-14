package com.aicodereviewer.backend.ai.adapter;

import com.aicodereviewer.backend.ai.exception.AiProviderException;
import com.aicodereviewer.backend.ai.model.AiRequest;
import com.aicodereviewer.backend.ai.model.NormalizedResponse;
import com.aicodereviewer.backend.ai.model.ProviderMetadata;
import com.aicodereviewer.backend.ai.model.TokenUsage;
import com.aicodereviewer.backend.ai.port.AiProviderPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI provider adapter for Groq (OpenAI-compatible API).
 * Migrated from the original ReviewService HTTP logic.
 */
@Component
public class GroqProviderAdapter implements AiProviderPort {

    private static final Logger log = LoggerFactory.getLogger(GroqProviderAdapter.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final int timeoutSeconds;
    private final int maxRetries;
    private final double costPer1kTokens;

    public GroqProviderAdapter(
            @Value("${ai.provider.groq.api-key}") String apiKey,
            @Value("${ai.provider.groq.model:llama-3.3-70b-versatile}") String model,
            @Value("${ai.provider.groq.api-url:https://api.groq.com/openai/v1/chat/completions}") String apiUrl,
            @Value("${ai.provider.groq.timeout-seconds:30}") int timeoutSeconds,
            @Value("${ai.provider.groq.max-retries:2}") int maxRetries,
            @Value("${ai.provider.groq.cost-per-1k-tokens:0.0008}") double costPer1kTokens
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.costPer1kTokens = costPer1kTokens;
    }

    @Override
    public NormalizedResponse call(AiRequest request) throws AiProviderException {
        long startMs = System.currentTimeMillis();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "temperature", request.temperature(),
                "max_tokens", request.maxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            long latencyMs = System.currentTimeMillis() - startMs;
            String body = Objects.requireNonNullElse(response.getBody(), "");
            JsonNode root = objectMapper.readTree(body);

            String content = root.path("choices").path(0).path("message").path("content").asText("");
            String finishReason = root.path("choices").path(0).path("finish_reason").asText("stop");

            // Extract token usage
            JsonNode usageNode = root.path("usage");
            int promptTokens = usageNode.path("prompt_tokens").asInt(0);
            int completionTokens = usageNode.path("completion_tokens").asInt(0);
            int totalTokens = usageNode.path("total_tokens").asInt(promptTokens + completionTokens);

            if (content.isBlank()) {
                throw new AiProviderException("Groq returned empty content", true);
            }

            return new NormalizedResponse(
                    content,
                    new TokenUsage(promptTokens, completionTokens, totalTokens),
                    "groq",
                    model,
                    latencyMs,
                    finishReason
            );

        } catch (HttpStatusCodeException ex) {
            long latencyMs = System.currentTimeMillis() - startMs;
            HttpStatusCode status = ex.getStatusCode();
            String responseBody = ex.getResponseBodyAsString();
            log.warn("Groq API error: status={}, latency={}ms, body={}", status.value(), latencyMs, responseBody);

            boolean retryable = status.value() == 429 || status.value() == 413 || status.value() >= 500;
            throw new AiProviderException(
                    "Groq API error " + status.value() + ": " + responseBody,
                    retryable, ex
            );
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - startMs;
            log.error("Groq call failed after {}ms: {}", latencyMs, ex.getMessage());
            throw new AiProviderException("Groq call failed: " + ex.getMessage(), true, ex);
        }
    }

    @Override
    public ProviderMetadata getMetadata() {
        return new ProviderMetadata("groq", "Groq (Fast)", model, costPer1kTokens, timeoutSeconds, maxRetries);
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
