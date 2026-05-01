package com.aicodereviewer.backend.service;

import com.aicodereviewer.backend.dto.ReviewResponse;
import com.aicodereviewer.backend.model.Issue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ReviewService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String xaiApiKey;
    private final String xaiModel;
    private final String xaiApiUrl;

    public ReviewService(
            @Value("${xai.api-key}") String xaiApiKey,
            @Value("${xai.model}") String xaiModel,
            @Value("${xai.api-url}") String xaiApiUrl
    ) {
        this.xaiApiKey = xaiApiKey;
        this.xaiModel = xaiModel;
        this.xaiApiUrl = xaiApiUrl;
    }

    public ReviewResponse analyzeDiff(String diffText) {
        if (diffText == null || diffText.trim().isEmpty()) {
            List<Issue> bugs = new ArrayList<>();
            List<Issue> security = new ArrayList<>();
            List<Issue> quality = new ArrayList<>();
            List<Issue> improvements = new ArrayList<>();
            improvements.add(new Issue("Low", "No input provided", "Paste a git diff or fetch a PR before running analysis."));
            return new ReviewResponse(0, bugs, security, quality, improvements);
        }

        if (xaiApiKey == null || xaiApiKey.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing xAI API key. Set xai.api-key or XAI_API_KEY.");
        }

        String prompt = """
                You are an expert pull request reviewer.
                Analyze the following git diff and return STRICT JSON only (no markdown), matching this schema:
                {
                  "riskScore": number from 0 to 100,
                  "bugs": [{"severity":"High|Medium|Low","title":"...","description":"..."}],
                  "security": [{"severity":"High|Medium|Low","title":"...","description":"..."}],
                  "quality": [{"severity":"High|Medium|Low","title":"...","description":"..."}],
                  "improvements": [{"severity":"High|Medium|Low","title":"...","description":"..."}]
                }
                Keep each list concise (0-5 items each).
                If no findings exist in a category, return an empty array for that category.

                Diff:
                """ + diffText;

        Map<String, Object> requestBody = Map.of(
                "model", xaiModel,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", "You are a precise static-analysis PR reviewer."),
                        Map.of("role", "user", "content", prompt)
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(xaiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    xaiApiUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            String body = Objects.requireNonNullElse(response.getBody(), "");
            JsonNode root = objectMapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new ResponseStatusException(BAD_GATEWAY, "xAI returned an empty analyzer response.");
            }
            JsonNode analysis = objectMapper.readTree(extractJsonObject(content));
            return toReviewResponse(analysis);
        } catch (HttpStatusCodeException ex) {
            HttpStatusCode status = ex.getStatusCode();
            String responseBody = ex.getResponseBodyAsString();
            if (status.is4xxClientError()) {
                throw new ResponseStatusException(BAD_REQUEST, "xAI request rejected: " + responseBody, ex);
            }
            throw new ResponseStatusException(BAD_GATEWAY, "xAI API error: " + responseBody, ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to parse Grok analyzer response", ex);
        }
    }

    private String extractJsonObject(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstBrace = trimmed.indexOf('{');
            int lastBrace = trimmed.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return trimmed.substring(firstBrace, lastBrace + 1);
            }
        }
        return trimmed;
    }

    private ReviewResponse toReviewResponse(JsonNode analysis) {
        int riskScore = Math.max(0, Math.min(100, analysis.path("riskScore").asInt(0)));
        List<Issue> bugs = parseIssues(analysis.path("bugs"));
        List<Issue> security = parseIssues(analysis.path("security"));
        List<Issue> quality = parseIssues(analysis.path("quality"));
        List<Issue> improvements = parseIssues(analysis.path("improvements"));

        return new ReviewResponse(riskScore, bugs, security, quality, improvements);
    }

    private List<Issue> parseIssues(JsonNode node) {
        List<Issue> issues = new ArrayList<>();
        if (!node.isArray()) {
            return issues;
        }

        for (JsonNode item : node) {
            String severity = normalizeSeverity(item.path("severity").asText("Low"));
            String title = item.path("title").asText("Untitled finding");
            String description = item.path("description").asText("");
            issues.add(new Issue(severity, title, description));
        }
        return issues;
    }

    private String normalizeSeverity(String severity) {
        if (severity == null) {
            return "Low";
        }
        return switch (severity.trim().toLowerCase()) {
            case "high" -> "High";
            case "medium" -> "Medium";
            default -> "Low";
        };
    }
}
