package com.aicodereviewer.backend.service;

import com.aicodereviewer.backend.dto.ImproveCodeResponse;
import com.aicodereviewer.backend.dto.ReviewResponse;
import com.aicodereviewer.backend.model.CodeChangeSuggestion;
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

    private static final int MAX_IMPROVEMENT_DIFF_CHARS = 24000;

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
                You are a senior software engineer performing a pull request review.
                
                Analyze the given git diff carefully. Focus ONLY on the changes (+/- lines), but consider surrounding context when needed.
                
                Return STRICT JSON only. Do NOT include markdown, explanations, or extra text.
                
                JSON schema:
                {
                  "riskScore": number (0-100),
                  "bugs": [{"severity":"High|Medium|Low","title":"string","description":"string"}],
                  "security": [{"severity":"High|Medium|Low","title":"string","description":"string"}],
                  "quality": [{"severity":"High|Medium|Low","title":"string","description":"string"}],
                  "improvements": [{"severity":"High|Medium|Low","title":"string","description":"string"}]
                }
                
                Rules:
                - riskScore must reflect overall risk of this PR:
                  0-20: Safe
                  21-50: Moderate
                  51-80: Risky
                  81-100: Critical
                
                - Each issue must:
                  - Be specific to the diff
                  - Mention affected logic or pattern
                  - Avoid generic statements
                
                - Prioritize:
                  1. Bugs (logic errors, null issues, edge cases)
                  2. Security (auth, data leaks, injection, secrets)
                  3. Code quality (readability, duplication, bad practices)
                  4. Improvements (refactoring, optimization)
                
                - Limit each category to max 5 items
                - If no issues, return empty array []
                
                - DO NOT hallucinate issues if none exist
                - DO NOT repeat similar points
                
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

    public ImproveCodeResponse generateCodeImprovements(String diffText, ReviewResponse analysis) {
        if (diffText == null || diffText.trim().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Diff text is required to generate code improvements.");
        }

        if (analysis == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Analysis data is required before generating code improvements.");
        }

        if (xaiApiKey == null || xaiApiKey.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing xAI API key. Set xai.api-key or XAI_API_KEY.");
        }

        String prompt = """
                You are a senior software engineer generating precise, actionable code improvements for a pull request.
                
                Your task is to convert the provided review analysis and git diff into concrete, minimal, and correct code changes.
                
                Return STRICT JSON only. Do NOT include markdown, explanations, or any text outside JSON.
                
                JSON schema:
                {
                  "summary": "short summary of key improvements",
                  "changes": [
                    {
                      "filePath": "exact file path from diff or 'Unknown'",
                      "rationale": "clear and specific reason for the change",
                      "beforeCode": "relevant problematic snippet from diff or best approximation",
                      "afterCode": "corrected/improved version of the snippet"
                    }
                  ]
                }
                
                Rules:
                - Maximum 6 changes. Prefer high-impact fixes over many small ones.
                - Focus on:
                  1. Bug fixes (null checks, incorrect logic, edge cases)
                  2. Security fixes (validation, injection, secrets, auth issues)
                  3. Code quality (duplication, readability, maintainability)
                  4. Performance improvements (only if meaningful)
                
                - filePath must match a file in the diff when possible.
                - beforeCode should resemble actual code from the diff (use +/- context).
                - afterCode must be:
                  - syntactically correct
                  - directly usable
                  - minimal (only show relevant lines)
                
                - Avoid generic advice like "improve readability".
                - Avoid repeating similar changes.
                - Do NOT invent files or large unrelated code blocks.
                - If uncertain, make a realistic best-effort assumption.
                
                - Ensure JSON is valid and parsable:
                  - Escape quotes properly
                  - No trailing commas
                  - No comments
                
                Input Review Analysis (JSON):
                """ + safeJson(analysis) + """
                
                Git Diff:
                """ + shrinkDiffForImprovementPrompt(diffText) + """
                
                IMPORTANT:
                Output must be valid JSON only and directly parseable.
                """;

        try {
            String content = callModelForJsonContent(prompt);
            JsonNode root = objectMapper.readTree(extractJsonObject(content));
            return toImproveCodeResponse(root);
        } catch (HttpStatusCodeException ex) {
            HttpStatusCode status = ex.getStatusCode();
            String responseBody = ex.getResponseBodyAsString();
            if (status.is4xxClientError()) {
                throw new ResponseStatusException(BAD_REQUEST, "xAI request rejected: " + responseBody, ex);
            }
            throw new ResponseStatusException(BAD_GATEWAY, "xAI API error: " + responseBody, ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to parse code improvement response", ex);
        }
    }

    private String callModelForJsonContent(String prompt) throws Exception {
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
            throw new ResponseStatusException(BAD_GATEWAY, "xAI returned an empty response.");
        }
        return content;
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

    private ImproveCodeResponse toImproveCodeResponse(JsonNode node) {
        String summary = node.path("summary").asText("Suggested code improvements based on the analysis.");
        List<CodeChangeSuggestion> changes = new ArrayList<>();
        JsonNode changesNode = node.path("changes");
        if (changesNode.isArray()) {
            for (JsonNode item : changesNode) {
                String filePath = item.path("filePath").asText("Unknown");
                String rationale = item.path("rationale").asText("Improves code quality and reliability.");
                String beforeCode = item.path("beforeCode").asText("");
                String afterCode = item.path("afterCode").asText("");
                changes.add(new CodeChangeSuggestion(filePath, rationale, beforeCode, afterCode));
            }
        }
        return new ImproveCodeResponse(summary, changes);
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String shrinkDiffForImprovementPrompt(String diffText) {
        String trimmed = diffText == null ? "" : diffText.trim();
        if (trimmed.length() <= MAX_IMPROVEMENT_DIFF_CHARS) {
            return trimmed;
        }

        int headLength = MAX_IMPROVEMENT_DIFF_CHARS / 2;
        int tailLength = MAX_IMPROVEMENT_DIFF_CHARS - headLength;
        String head = trimmed.substring(0, headLength);
        String tail = trimmed.substring(trimmed.length() - tailLength);

        return head + "\n\n... [DIFF TRUNCATED FOR TOKEN LIMITS] ...\n\n" + tail;
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
