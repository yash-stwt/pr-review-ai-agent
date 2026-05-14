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
 * AI provider adapter for Anthropic Claude (Messages API).
 */
@Component
public class ClaudeProviderAdapter implements AiProviderPort {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProviderAdapter.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final int timeoutSeconds;
    private final int maxRetries;
    private final double costPer1kTokens;

    public ClaudeProviderAdapter(
            @Value("${ai.provider.claude.api-key:}") String apiKey,
            @Value("${ai.provider.claude.model:claude-3-5-sonnet-20241022}") String model,
            @Value("${ai.provider.claude.api-url:https://api.anthropic.com/v1/messages}") String apiUrl,
            @Value("${ai.provider.claude.timeout-seconds:40}") int timeoutSeconds,
            @Value("${ai.provider.claude.max-retries:2}") int maxRetries,
            @Value("${ai.provider.claude.cost-per-1k-tokens:0.003}") double costPer1kTokens
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
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiProviderException(
                    "Claude API key not configured. Set the CLAUDE_API_KEY environment variable.", false);
        }
        long startMs = System.currentTimeMillis();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", request.maxTokens(),
                "system", request.systemPrompt(),
                "messages", List.of(
                        Map.of("role", "user", "content", request.userPrompt())
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
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

            // Claude returns content as an array of content blocks
            String content = root.path("content").path(0).path("text").asText("");
            String stopReason = root.path("stop_reason").asText("end_turn");

            // Token usage
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("input_tokens").asInt(0);
            int completionTokens = usage.path("output_tokens").asInt(0);
            int totalTokens = promptTokens + completionTokens;

            if (content.isBlank()) {
                throw new AiProviderException("Claude returned empty content", true);
            }

            return new NormalizedResponse(
                    content,
                    new TokenUsage(promptTokens, completionTokens, totalTokens),
                    "claude",
                    model,
                    latencyMs,
                    stopReason
            );

        } catch (HttpStatusCodeException ex) {
            long latencyMs = System.currentTimeMillis() - startMs;
            int statusCode = ex.getStatusCode().value();
            log.warn("Claude API error: status={}, latency={}ms", statusCode, latencyMs);
            boolean retryable = statusCode == 429 || statusCode >= 500;
            throw new AiProviderException("Claude API error " + statusCode, retryable, ex);
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Claude call failed: {}", ex.getMessage());
            throw new AiProviderException("Claude call failed: " + ex.getMessage(), true, ex);
        }
    }

    @Override
    public ProviderMetadata getMetadata() {
        return new ProviderMetadata("claude", "Claude (Security)", model, costPer1kTokens, timeoutSeconds, maxRetries);
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
