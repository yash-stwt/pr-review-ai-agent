package com.aicodereviewer.backend.service;

import com.aicodereviewer.backend.ai.model.AiRequest;
import com.aicodereviewer.backend.ai.model.NormalizedResponse;
import com.aicodereviewer.backend.ai.router.AiRouter;
import com.aicodereviewer.backend.dto.FixRequest;
import com.aicodereviewer.backend.dto.FixResponse;
import com.aicodereviewer.backend.dto.ImproveCodeResponse;
import com.aicodereviewer.backend.dto.InlineReviewResponse;
import com.aicodereviewer.backend.dto.ReviewResponse;
import com.aicodereviewer.backend.model.CodeChangeSuggestion;
import com.aicodereviewer.backend.model.InlineComment;
import com.aicodereviewer.backend.model.Issue;
import com.aicodereviewer.backend.util.DiffParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ReviewService {

    private static final int MAX_IMPROVEMENT_DIFF_CHARS = 24000;

    private final AiRouter aiRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(AiRouter aiRouter) {
        this.aiRouter = aiRouter;
    }

    // ── Public API (unchanged signatures) ─────────────────────────────────────

    public ReviewResponse analyzeDiff(String diffText) {
        return analyzeDiff(diffText, null);
    }

    public ReviewResponse analyzeDiff(String diffText, String preferredProviderId) {
        if (diffText == null || diffText.trim().isEmpty()) {
            List<Issue> improvements = new ArrayList<>();
            improvements.add(new Issue("Low", "No input provided", "Paste a git diff or fetch a PR before running analysis."));
            return new ReviewResponse(0, "No diff provided. Paste a git diff or fetch a PR to begin.", 0,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), improvements, null);
        }

        String prompt = """
                You are a senior software engineer performing a pull request review.
                
                Analyze the given git diff carefully. Focus ONLY on the changes (+/- lines), but consider surrounding context when needed.
                
                Return STRICT JSON only. Do NOT include markdown, explanations, or extra text.
                
                JSON schema:
                {
                  "riskScore": number (0-100),
                  "executiveSummary": "string",
                  "bugs": [{"severity":"Critical|High|Medium|Low","title":"string","description":"string","filePath":"string or null","lineNumber":number or null}],
                  "security": [{"severity":"Critical|High|Medium|Low","title":"string","description":"string","filePath":"string or null","lineNumber":number or null}],
                  "quality": [{"severity":"Critical|High|Medium|Low","title":"string","description":"string","filePath":"string or null","lineNumber":number or null}],
                  "improvements": [{"severity":"Critical|High|Medium|Low","title":"string","description":"string","filePath":"string or null","lineNumber":number or null}]
                }
                
                executiveSummary rules:
                - Write 2-3 sentences in a professional tone
                - Must reference the actual risk level: Safe (0-20), Moderate (21-50), Risky (51-80), or Critical (81-100)
                - Must mention the top 1-2 specific concerns found (or confirm no issues if clean)
                
                filePath rules:
                - Extract the exact file path from the diff header line
                - Set to null only if the issue genuinely cannot be tied to a specific file
                
                lineNumber rules:
                - Set to the new-side (+) line number where the issue occurs
                - Set to null if not precisely mappable to a single line
                
                riskScore:
                  0-20: Safe | 21-50: Moderate | 51-80: Risky | 81-100: Critical
                
                - Each issue must be specific to the diff and mention the affected logic or pattern
                - Limit each category to max 5 items. Return [] if no issues.
                - DO NOT hallucinate issues. DO NOT repeat similar points.
                
                Diff:
                """ + truncateForAnalysis(diffText);

        try {
            NormalizedResponse aiResponse = callAiWithMeta(
                    "You are a precise static-analysis PR reviewer.", prompt, "fast-analysis", preferredProviderId);
            JsonNode analysis = objectMapper.readTree(extractJsonObject(aiResponse.content()));
            return toReviewResponse(analysis, aiResponse);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to parse analyzer response: " + ex.getMessage(), ex);
        }
    }

    public ImproveCodeResponse generateCodeImprovements(String diffText, ReviewResponse analysis) {
        if (diffText == null || diffText.trim().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Diff text is required to generate code improvements.");
        }
        if (analysis == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Analysis data is required before generating code improvements.");
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
                      "beforeCode": "ONLY the specific problematic lines — 3 to 8 lines maximum",
                      "afterCode": "ONLY the corrected replacement lines — same scope as beforeCode"
                    }
                  ]
                }
                
                CRITICAL Rules:
                - Maximum 6 changes. Prefer high-impact fixes over many small ones.
                - filePath must match a file in the diff when possible.
                - beforeCode: extract ONLY the exact lines that need changing (3-8 lines max). Do NOT include surrounding context, imports, class declarations, or unrelated code.
                - afterCode: provide ONLY the replacement for those exact lines. Same line count or close to it. Do NOT repeat the entire function or class.
                - Both beforeCode and afterCode must be minimal, focused snippets — not entire files or functions.
                - Avoid generic advice. Do NOT invent files or large unrelated code blocks.
                - Ensure JSON is valid and parsable.
                
                Input Review Analysis (JSON):
                """ + safeJson(analysis) + """
                
                Git Diff:
                """ + shrinkDiffForImprovementPrompt(diffText) + """
                
                IMPORTANT: Output must be valid JSON only and directly parseable.
                """;

        try {
            String content = callAi("You are a precise static-analysis PR reviewer.", prompt, "deep-review");
            JsonNode root = objectMapper.readTree(extractJsonObject(content));
            return toImproveCodeResponse(root);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_GATEWAY, "Failed to parse code improvement response: " + ex.getMessage(), ex);
        }
    }

    public InlineReviewResponse generateInlineReview(String diffText) {
        // 1. Run standard analysis to get AI findings
        ReviewResponse analysis = analyzeDiff(diffText);

        // 2. Parse diff to get changed line numbers per file
        Map<String, List<Integer>> changedLines = DiffParser.getChangedLineNumbers(diffText);

        // 3. Map each issue category to inline comments
        List<InlineComment> comments = new ArrayList<>();
        mapIssuesToComments(analysis.bugs(),         "bug",         comments, changedLines);
        mapIssuesToComments(analysis.security(),     "security",    comments, changedLines);
        mapIssuesToComments(analysis.quality(),      "quality",     comments, changedLines);
        mapIssuesToComments(analysis.improvements(), "improvement", comments, changedLines);

        // 4. Group by file for fast frontend lookup
        Map<String, List<InlineComment>> byFile = new LinkedHashMap<>();
        for (InlineComment c : comments) {
            byFile.computeIfAbsent(c.filePath(), k -> new ArrayList<>()).add(c);
        }

        return new InlineReviewResponse(diffText, comments, byFile);
    }

    // ── AI call abstraction ───────────────────────────────────────────────────

    private String callAi(String systemPrompt, String userPrompt, String taskType) {
        AiRequest request = new AiRequest(systemPrompt, userPrompt, 0.2, 4096, taskType);
        NormalizedResponse response = aiRouter.route(request);
        return response.content();
    }

    private NormalizedResponse callAiWithMeta(String systemPrompt, String userPrompt, String taskType) {
        AiRequest request = new AiRequest(systemPrompt, userPrompt, 0.2, 4096, taskType);
        return aiRouter.route(request);
    }

    private NormalizedResponse callAiWithMeta(String systemPrompt, String userPrompt, String taskType, String preferredProviderId) {
        AiRequest request = new AiRequest(systemPrompt, userPrompt, 0.2, 4096, taskType, preferredProviderId);
        return aiRouter.route(request);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) return "{}";
        String trimmed = text.trim();
        // Find the first { and last } — handles markdown fences, leading text, etc.
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return "{}";
    }

    private ReviewResponse toReviewResponse(JsonNode analysis, NormalizedResponse aiResponse) {
        int riskScore = Math.max(0, Math.min(100, analysis.path("riskScore").asInt(0)));
        String executiveSummary = analysis.path("executiveSummary").isNull()
                ? generateFallbackSummary(riskScore)
                : analysis.path("executiveSummary").asText(generateFallbackSummary(riskScore));

        List<Issue> bugs         = parseIssues(analysis.path("bugs"));
        List<Issue> security     = parseIssues(analysis.path("security"));
        List<Issue> quality      = parseIssues(analysis.path("quality"));
        List<Issue> improvements = parseIssues(analysis.path("improvements"));

        int criticalCount = countHighSeverity(bugs, security, quality);

        // Build provider metadata for the frontend
        Map<String, Object> meta = Map.of(
                "providerId", aiResponse.providerId(),
                "modelId", aiResponse.modelId(),
                "latencyMs", aiResponse.latencyMs(),
                "tokenUsage", Map.of(
                        "promptTokens", aiResponse.tokenUsage().promptTokens(),
                        "completionTokens", aiResponse.tokenUsage().completionTokens(),
                        "totalTokens", aiResponse.tokenUsage().totalTokens()
                )
        );

        return new ReviewResponse(riskScore, executiveSummary, criticalCount, bugs, security, quality, improvements, meta);
    }

    @SafeVarargs
    private int countHighSeverity(List<Issue>... lists) {
        int count = 0;
        for (List<Issue> list : lists) {
            count += list.stream()
                    .filter(i -> "Critical".equalsIgnoreCase(i.severity()) || "High".equalsIgnoreCase(i.severity()))
                    .count();
        }
        return count;
    }

    private String generateFallbackSummary(int riskScore) {
        if (riskScore <= 20) return "This PR appears safe with no significant issues detected. It is ready for merge after a standard review.";
        if (riskScore <= 50) return "This PR introduces moderate risk. Review the flagged items carefully before merging.";
        if (riskScore <= 80) return "This PR is risky. Several issues require attention and resolution before this can be safely merged.";
        return "This PR is critical risk. Do not merge without resolving all flagged issues — security and correctness concerns are present.";
    }

    private List<Issue> parseIssues(JsonNode node) {
        List<Issue> issues = new ArrayList<>();
        if (!node.isArray()) return issues;
        for (JsonNode item : node) {
            String severity    = normalizeSeverity(item.path("severity").asText("Low"));
            String title       = item.path("title").asText("Untitled finding");
            String description = item.path("description").asText("");
            String filePath    = item.path("filePath").isNull() ? null : item.path("filePath").asText(null);
            Integer lineNumber = item.path("lineNumber").isNull() ? null : item.path("lineNumber").asInt();
            issues.add(new Issue(severity, title, description, filePath, lineNumber));
        }
        return issues;
    }

    private String normalizeSeverity(String severity) {
        if (severity == null) return "Low";
        return switch (severity.trim().toLowerCase()) {
            case "critical" -> "Critical";
            case "high"     -> "High";
            case "medium"   -> "Medium";
            default         -> "Low";
        };
    }

    private ImproveCodeResponse toImproveCodeResponse(JsonNode node) {
        String summary = node.path("summary").asText("Suggested code improvements based on the analysis.");
        List<CodeChangeSuggestion> changes = new ArrayList<>();
        JsonNode changesNode = node.path("changes");
        if (changesNode.isArray()) {
            for (JsonNode item : changesNode) {
                String filePath   = item.path("filePath").asText("Unknown");
                String rationale  = item.path("rationale").asText("Improves code quality and reliability.");
                String beforeCode = item.path("beforeCode").asText("");
                String afterCode  = item.path("afterCode").asText("");
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
        if (trimmed.length() <= MAX_IMPROVEMENT_DIFF_CHARS) return trimmed;
        int headLength = MAX_IMPROVEMENT_DIFF_CHARS / 2;
        int tailLength = MAX_IMPROVEMENT_DIFF_CHARS - headLength;
        return trimmed.substring(0, headLength) + "\n\n... [DIFF TRUNCATED FOR TOKEN LIMITS] ...\n\n" + trimmed.substring(trimmed.length() - tailLength);
    }

    /**
     * Truncate diff for the analyze prompt to stay within model token limits.
     * ~4 chars per token, prompt overhead ~1500 tokens, so limit diff to ~12000 chars.
     */
    private static final int MAX_ANALYSIS_DIFF_CHARS = 12000;

    private String truncateForAnalysis(String diffText) {
        String trimmed = diffText == null ? "" : diffText.trim();
        if (trimmed.length() <= MAX_ANALYSIS_DIFF_CHARS) return trimmed;
        int headLength = MAX_ANALYSIS_DIFF_CHARS / 2;
        int tailLength = MAX_ANALYSIS_DIFF_CHARS - headLength;
        return trimmed.substring(0, headLength) + "\n\n... [DIFF TRUNCATED — " + trimmed.length() + " chars total] ...\n\n" + trimmed.substring(trimmed.length() - tailLength);
    }

    private void mapIssuesToComments(
            List<Issue> issues, String category,
            List<InlineComment> out, Map<String, List<Integer>> changedLines
    ) {
        for (Issue issue : issues) {
            String filePath = issue.filePath();
            int lineNumber = 1;

            if (filePath != null && issue.lineNumber() != null) {
                lineNumber = issue.lineNumber();
            } else if (filePath != null && changedLines.containsKey(filePath)) {
                List<Integer> lines = changedLines.get(filePath);
                if (!lines.isEmpty()) lineNumber = lines.get(0);
            } else if (filePath == null) {
                if (!changedLines.isEmpty()) {
                    Map.Entry<String, List<Integer>> first = changedLines.entrySet().iterator().next();
                    filePath = first.getKey();
                    if (!first.getValue().isEmpty()) lineNumber = first.getValue().get(0);
                } else {
                    filePath = "unknown";
                }
            }

            out.add(new InlineComment(
                    filePath, lineNumber, "RIGHT",
                    issue.severity(), category,
                    issue.title(), issue.description()
            ));
        }
    }

    // ── Apply AI Fix ──────────────────────────────────────────────────────────

    public FixResponse generateFix(FixRequest request) {
        // 1. Severity gate
        String sev = request.severity().trim().toLowerCase();
        if (!request.forceGenerate() && !"critical".equals(sev) && !"high".equals(sev) && !"medium".equals(sev)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "{\"errorCode\":\"FIX_NOT_APPLICABLE\",\"message\":\"Fix generation is only available for Critical or High severity findings. Set forceGenerate=true to override.\"}");
        }

        // 2. Validate diff size
        if (request.diffText().length() > 100_000) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "{\"errorCode\":\"DIFF_TOO_LARGE\",\"message\":\"Diff text exceeds 100,000 characters.\"}");
        }

        // 3. Extract context around the target line
        String context = extractContextAroundLine(request.diffText(), request.filePath(), request.lineNumber());

        // 4. Build AI prompt
        String prompt = """
                You are a senior software engineer generating a minimal, safe code fix.
                
                Finding: %s
                Description: %s
                Severity: %s
                File: %s
                Line: %d
                
                Context from the diff:
                %s
                
                Generate a fix. Return STRICT JSON only:
                {
                  "startLine": number,
                  "endLine": number,
                  "beforeCode": "the exact problematic code snippet",
                  "afterCode": "the corrected code snippet",
                  "patch": "unified diff string for this fix",
                  "explanation": "one sentence explaining the fix"
                }
                
                Rules:
                - Produce the MINIMAL change to fix the issue
                - Preserve indentation and formatting
                - beforeCode must match actual code from the diff context
                - afterCode must be syntactically correct
                - patch must be a valid unified diff
                - Do NOT change unrelated code
                """.formatted(
                request.findingTitle(),
                request.findingDescription(),
                request.severity(),
                request.filePath(),
                request.lineNumber(),
                context
        );

        // 5. Call AI
        String content = callAi("You are a precise code fix generator.", prompt, "fix-generation");

        // 6. Parse response
        try {
            String json = extractJsonObject(content);
            JsonNode root = objectMapper.readTree(json);

            String beforeCode = root.path("beforeCode").asText("");
            String afterCode = root.path("afterCode").asText("");
            String patch = root.path("patch").asText("");
            String explanation = root.path("explanation").asText("Fix applied.");
            int startLine = root.path("startLine").asInt(request.lineNumber());
            int endLine = root.path("endLine").asInt(startLine);

            // 7. Validate
            if (afterCode.isBlank() || afterCode.equals(beforeCode)) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "{\"errorCode\":\"FIX_GENERATION_FAILED\",\"message\":\"AI returned an invalid fix (afterCode is empty or identical to beforeCode).\"}");
            }

            // 8. Compute findingId
            String findingId = computeFindingId(request.filePath(), request.lineNumber(), request.findingTitle());

            return new FixResponse(findingId, request.filePath(), startLine, endLine, beforeCode, afterCode, patch, explanation);

        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "{\"errorCode\":\"FIX_GENERATION_FAILED\",\"message\":\"Failed to parse fix response: " + ex.getMessage() + "\"}");
        }
    }

    private String extractContextAroundLine(String diffText, String filePath, int lineNumber) {
        List<DiffParser.DiffFile> files = DiffParser.parse(diffText);
        for (DiffParser.DiffFile file : files) {
            if (!file.filePath().equals(filePath)) continue;
            StringBuilder context = new StringBuilder();
            for (DiffParser.Hunk hunk : file.hunks()) {
                for (DiffParser.DiffLine line : hunk.lines()) {
                    int ln = line.newLineNumber() > 0 ? line.newLineNumber() : line.oldLineNumber();
                    if (Math.abs(ln - lineNumber) <= 5) {
                        String prefix = switch (line.type()) {
                            case "added" -> "+";
                            case "removed" -> "-";
                            default -> " ";
                        };
                        context.append(prefix).append(line.content()).append("\n");
                    }
                }
            }
            if (!context.isEmpty()) return context.toString();
        }
        // Fallback: return raw lines around the target
        return "File: " + filePath + " (context not found in diff)";
    }

    private String computeFindingId(String filePath, int lineNumber, String title) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((filePath + lineNumber + title).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "000000";
        }
    }
}
