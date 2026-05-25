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
 * AI provider adapter for Google Gemini (generateContent API).
 */
@Component
public class GeminiProviderAdapter implements AiProviderPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiProviderAdapter.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final int timeoutSeconds;
    private final int maxRetries;
    private final double costPer1kTokens;

    public GeminiProviderAdapter(
            @Value("${ai.provider.gemini.api-key:}") String apiKey,
            @Value("${ai.provider.gemini.model:gemini-3-flash-preview}") String model,
            @Value("${ai.provider.gemini.api-url:https://generativelanguage.googleapis.com/v1beta/models}") String apiUrl,
            @Value("${ai.provider.gemini.timeout-seconds:45}") int timeoutSeconds,
            @Value("${ai.provider.gemini.max-retries:2}") int maxRetries,
            @Value("${ai.provider.gemini.cost-per-1k-tokens:0.0035}") double costPer1kTokens
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
                    "Gemini API key not configured. Set the GEMINI_API_KEY environment variable.", false);
        }
        long startMs = System.currentTimeMillis();

        // Gemini generateContent endpoint: POST {apiUrl}/{model}:generateContent?key={apiKey}
        String url = apiUrl + "/" + model + ":generateContent?key=" + apiKey;

        // Combine system + user prompt into a single content part for Gemini
        String combinedPrompt = request.systemPrompt() + "\n\n" + request.userPrompt();

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", combinedPrompt)))
                ),
                "generationConfig", Map.of(
                        "temperature", request.temperature(),
                        "maxOutputTokens", request.maxTokens()
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            long latencyMs = System.currentTimeMillis() - startMs;
            String body = Objects.requireNonNullElse(response.getBody(), "");
            JsonNode root = objectMapper.readTree(body);

            String content = root.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text").asText("");
            String finishReason = root.path("candidates").path(0)
                    .path("finishReason").asText("STOP");

            // Token usage from usageMetadata
            JsonNode usage = root.path("usageMetadata");
            int promptTokens = usage.path("promptTokenCount").asInt(0);
            int completionTokens = usage.path("candidatesTokenCount").asInt(0);
            int totalTokens = usage.path("totalTokenCount").asInt(promptTokens + completionTokens);

            if (content.isBlank()) {
                throw new AiProviderException("Gemini returned empty content", true);
            }

            return new NormalizedResponse(
                    content,
                    new TokenUsage(promptTokens, completionTokens, totalTokens),
                    "gemini",
                    model,
                    latencyMs,
                    finishReason
            );

        } catch (HttpStatusCodeException ex) {
            long latencyMs = System.currentTimeMillis() - startMs;
            int statusCode = ex.getStatusCode().value();
            String errorResponse = ex.getResponseBodyAsString();
            log.warn("Gemini API error: status={}, latency={}ms, response={}", statusCode, latencyMs, errorResponse);
            boolean retryable = statusCode == 429 || statusCode >= 500;
            throw new AiProviderException("Gemini API error " + statusCode, retryable, ex);
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Gemini call failed: {}", ex.getMessage());
            throw new AiProviderException("Gemini call failed: " + ex.getMessage(), true, ex);
        }
    }

    @Override
    public ProviderMetadata getMetadata() {
        return new ProviderMetadata("gemini", "Gemini (Deep)", model, costPer1kTokens, timeoutSeconds, maxRetries);
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
