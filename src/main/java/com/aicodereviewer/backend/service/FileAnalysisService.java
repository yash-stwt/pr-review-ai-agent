package com.aicodereviewer.backend.service;

import com.aicodereviewer.backend.ai.model.AiRequest;
import com.aicodereviewer.backend.ai.model.NormalizedResponse;
import com.aicodereviewer.backend.ai.router.AiRouter;
import com.aicodereviewer.backend.dto.FileAnalysisJobStatus;
import com.aicodereviewer.backend.dto.FileAnalysisResult;
import com.aicodereviewer.backend.dto.PRAnalysisResponse;
import com.aicodereviewer.backend.model.Issue;
import com.aicodereviewer.backend.util.DiffParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Orchestrates per-file independent AI analysis with concurrent processing,
 * chunking for large files, and async job support for large PRs.
 */
@Service
public class FileAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FileAnalysisService.class);

    private final AiRouter aiRouter;
    private final PRAnalysisAggregator aggregator;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int chunkSize;
    private final int asyncThreshold;

    // In-memory async job store
    private final ConcurrentHashMap<String, AsyncJobEntry> jobStore = new ConcurrentHashMap<>();

    private record AsyncJobEntry(
            CompletableFuture<PRAnalysisResponse> future,
            AtomicInteger completedFiles,
            int totalFiles,
            Instant createdAt
    ) {}

    public FileAnalysisService(
            AiRouter aiRouter,
            PRAnalysisAggregator aggregator,
            @Qualifier("fileAnalysisExecutor") ExecutorService executor,
            @Value("${ai.file-analysis.chunk-size:300}") int chunkSize,
            @Value("${ai.file-analysis.async-threshold:20}") int asyncThreshold
    ) {
        this.aiRouter = aiRouter;
        this.aggregator = aggregator;
        this.executor = executor;
        this.chunkSize = chunkSize;
        this.asyncThreshold = asyncThreshold;
    }

    /**
     * Analyze all files synchronously (for PRs with ≤ asyncThreshold files).
     */
    public PRAnalysisResponse analyzeSync(String diffText) {
        List<DiffParser.DiffFile> files = DiffParser.parse(diffText);

        List<CompletableFuture<FileAnalysisResult>> futures = files.stream()
                .map(file -> CompletableFuture.supplyAsync(() -> analyzeFile(file), executor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("File analysis timed out after 60s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("File analysis execution error: {}", e.getMessage());
        }

        List<FileAnalysisResult> results = futures.stream()
                .map(f -> {
                    try {
                        return f.isDone() ? f.get() : failedResult("unknown", "Analysis timed out");
                    } catch (Exception e) {
                        return failedResult("unknown", e.getMessage());
                    }
                })
                .toList();

        String summary = generatePRSummary(results);
        return aggregator.aggregate(results, summary);
    }

    /**
     * Start async analysis (for PRs with > asyncThreshold files). Returns jobId.
     */
    public String analyzeAsync(String diffText) {
        List<DiffParser.DiffFile> files = DiffParser.parse(diffText);
        String jobId = UUID.randomUUID().toString();
        AtomicInteger completed = new AtomicInteger(0);

        CompletableFuture<PRAnalysisResponse> future = CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<FileAnalysisResult>> futures = files.stream()
                    .map(file -> CompletableFuture.supplyAsync(() -> {
                        FileAnalysisResult result = analyzeFile(file);
                        completed.incrementAndGet();
                        return result;
                    }, executor))
                    .toList();

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Async file analysis error: {}", e.getMessage());
            }

            List<FileAnalysisResult> results = futures.stream()
                    .map(f -> {
                        try {
                            return f.isDone() ? f.get() : failedResult("unknown", "Timed out");
                        } catch (Exception e) {
                            return failedResult("unknown", e.getMessage());
                        }
                    })
                    .toList();

            String summary = generatePRSummary(results);
            return aggregator.aggregate(results, summary);
        }, executor);

        jobStore.put(jobId, new AsyncJobEntry(future, completed, files.size(), Instant.now()));
        return jobId;
    }

    /**
     * Get the status of an async job.
     */
    public Optional<FileAnalysisJobStatus> getJobStatus(String jobId) {
        AsyncJobEntry entry = jobStore.get(jobId);
        if (entry == null) return Optional.empty();

        if (entry.future().isDone()) {
            try {
                PRAnalysisResponse result = entry.future().get();
                return Optional.of(new FileAnalysisJobStatus(jobId, "COMPLETE", 100, result));
            } catch (Exception e) {
                return Optional.of(new FileAnalysisJobStatus(jobId, "FAILED", 0, null));
            }
        }

        int progress = entry.totalFiles() == 0 ? 0 : (entry.completedFiles().get() * 100) / entry.totalFiles();
        return Optional.of(new FileAnalysisJobStatus(jobId, "PROCESSING", progress, null));
    }

    /**
     * Determine if the diff should be processed asynchronously.
     */
    public boolean shouldProcessAsync(String diffText) {
        List<DiffParser.DiffFile> files = DiffParser.parse(diffText);
        return files.size() > asyncThreshold;
    }

    /**
     * Cleanup expired jobs (older than 30 minutes).
     */
    @Scheduled(fixedDelay = 300_000)
    public void cleanupExpiredJobs() {
        Instant cutoff = Instant.now().minusSeconds(1800);
        jobStore.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    // ── Per-file analysis ─────────────────────────────────────────────────────

    private FileAnalysisResult analyzeFile(DiffParser.DiffFile file) {
        // Binary files: skip AI analysis
        if (file.binary()) {
            return new FileAnalysisResult(
                    file.filePath(), file.language(), "binary",
                    0, 0, 0, "Binary file — skipped.",
                    "OK", null,
                    List.of(), List.of(), List.of(), List.of()
            );
        }

        try {
            int totalChangedLines = file.linesAdded() + file.linesRemoved();

            // Chunk large files
            if (totalChangedLines > chunkSize) {
                return analyzeFileChunked(file);
            }

            return analyzeFileSingle(file);
        } catch (Exception e) {
            log.error("Failed to analyze file {}: {}", file.filePath(), e.getMessage());
            return failedResult(file, e.getMessage());
        }
    }

    private FileAnalysisResult analyzeFileSingle(DiffParser.DiffFile file) {
        String fileContent = buildFileContext(file);
        String prompt = buildFileAnalysisPrompt(file.filePath(), file.language(), fileContent);

        AiRequest request = new AiRequest(
                "You are a precise static-analysis PR reviewer analyzing a single file.",
                prompt, 0.2, 4096, "fast-analysis"
        );

        NormalizedResponse response = aiRouter.route(request);
        return parseFileAnalysisResponse(file, response.content());
    }

    private FileAnalysisResult analyzeFileChunked(DiffParser.DiffFile file) {
        // Split hunks into chunks of ~chunkSize lines
        List<List<DiffParser.Hunk>> chunks = new ArrayList<>();
        List<DiffParser.Hunk> currentChunk = new ArrayList<>();
        int currentLines = 0;

        for (DiffParser.Hunk hunk : file.hunks()) {
            int hunkLines = hunk.lines().size();
            if (currentLines + hunkLines > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(new ArrayList<>(currentChunk));
                currentChunk.clear();
                currentLines = 0;
            }
            currentChunk.add(hunk);
            currentLines += hunkLines;
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }

        // Analyze each chunk and merge
        List<Issue> allBugs = new ArrayList<>();
        List<Issue> allSecurity = new ArrayList<>();
        List<Issue> allQuality = new ArrayList<>();
        List<Issue> allImprovements = new ArrayList<>();
        int maxRisk = 0;

        for (List<DiffParser.Hunk> chunk : chunks) {
            String chunkContent = buildChunkContext(file.filePath(), chunk);
            String prompt = buildFileAnalysisPrompt(file.filePath(), file.language(), chunkContent);

            AiRequest request = new AiRequest(
                    "You are a precise static-analysis PR reviewer analyzing a single file.",
                    prompt, 0.2, 4096, "fast-analysis"
            );

            try {
                NormalizedResponse response = aiRouter.route(request);
                FileAnalysisResult chunkResult = parseFileAnalysisResponse(file, response.content());
                allBugs.addAll(chunkResult.bugs());
                allSecurity.addAll(chunkResult.security());
                allQuality.addAll(chunkResult.quality());
                allImprovements.addAll(chunkResult.improvements());
                maxRisk = Math.max(maxRisk, chunkResult.riskScore());
            } catch (Exception e) {
                log.warn("Chunk analysis failed for {}: {}", file.filePath(), e.getMessage());
            }
        }

        // Deduplicate by (title, lineNumber)
        allBugs = deduplicateIssues(allBugs);
        allSecurity = deduplicateIssues(allSecurity);
        allQuality = deduplicateIssues(allQuality);
        allImprovements = deduplicateIssues(allImprovements);

        return new FileAnalysisResult(
                file.filePath(), file.language(), file.changeType(),
                file.linesAdded(), file.linesRemoved(), maxRisk,
                "Analyzed in " + chunks.size() + " chunks.",
                "OK", null,
                allBugs, allSecurity, allQuality, allImprovements
        );
    }

    private FileAnalysisResult parseFileAnalysisResponse(DiffParser.DiffFile file, String content) {
        try {
            String json = extractJsonObject(content);
            JsonNode root = objectMapper.readTree(json);

            int riskScore = Math.max(0, Math.min(100, root.path("riskScore").asInt(0)));
            String summary = root.path("summary").asText("No summary.");

            return new FileAnalysisResult(
                    file.filePath(), file.language(), file.changeType(),
                    file.linesAdded(), file.linesRemoved(), riskScore, summary,
                    "OK", null,
                    parseIssues(root.path("bugs")),
                    parseIssues(root.path("security")),
                    parseIssues(root.path("quality")),
                    parseIssues(root.path("improvements"))
            );
        } catch (Exception e) {
            log.error("Failed to parse file analysis response for {}: {}", file.filePath(), e.getMessage());
            return failedResult(file.filePath(), "Failed to parse AI response: " + e.getMessage());
        }
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    private String buildFileAnalysisPrompt(String filePath, String language, String content) {
        return """
                You are a precise static-analysis PR reviewer.
                
                Analyze the following file changes. You MUST return STRICT JSON only.
                Do NOT include markdown, code fences, explanations, or any text outside the JSON object.
                Start your response with { and end with }.
                
                File: %s (language: %s)
                
                JSON schema:
                {
                  "riskScore": number (0-100),
                  "summary": "one sentence summary of changes in this file",
                  "bugs": [{"severity":"Critical|High|Medium|Low","title":"string","description":"string","filePath":"%s","lineNumber":number or null}],
                  "security": [],
                  "quality": [],
                  "improvements": []
                }
                
                Rules:
                - riskScore: 0-20 Safe, 21-50 Moderate, 51-80 Risky, 81-100 Critical
                - Limit each category to max 5 items. Return [] if no issues.
                - DO NOT hallucinate issues.
                - Output ONLY the JSON object. No markdown. No explanation.
                
                Changes:
                %s
                """.formatted(filePath, language, filePath, content);
    }

    private String buildFileContext(DiffParser.DiffFile file) {
        StringBuilder sb = new StringBuilder();
        for (DiffParser.Hunk hunk : file.hunks()) {
            sb.append("@@ ").append(hunk.header()).append(" @@\n");
            for (DiffParser.DiffLine line : hunk.lines()) {
                String prefix = switch (line.type()) {
                    case "added" -> "+";
                    case "removed" -> "-";
                    default -> " ";
                };
                sb.append(prefix).append(line.content()).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildChunkContext(String filePath, List<DiffParser.Hunk> hunks) {
        StringBuilder sb = new StringBuilder();
        for (DiffParser.Hunk hunk : hunks) {
            sb.append("@@ ").append(hunk.header()).append(" @@\n");
            for (DiffParser.DiffLine line : hunk.lines()) {
                String prefix = switch (line.type()) {
                    case "added" -> "+";
                    case "removed" -> "-";
                    default -> " ";
                };
                sb.append(prefix).append(line.content()).append("\n");
            }
        }
        return sb.toString();
    }

    private String generatePRSummary(List<FileAnalysisResult> results) {
        int totalFiles = results.size();
        int failedFiles = (int) results.stream().filter(r -> "FAILED".equals(r.status())).count();
        int maxRisk = results.stream().mapToInt(FileAnalysisResult::riskScore).max().orElse(0);

        if (maxRisk <= 20) return "This PR appears safe across " + totalFiles + " files with no significant issues.";
        if (maxRisk <= 50) return "This PR introduces moderate risk across " + totalFiles + " files. Review flagged items.";
        if (maxRisk <= 80) return "This PR is risky. " + totalFiles + " files analyzed with several issues requiring attention.";
        return "This PR is critical risk across " + totalFiles + " files. Do not merge without resolving all flagged issues.";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Issue> deduplicateIssues(List<Issue> issues) {
        Set<String> seen = new HashSet<>();
        return issues.stream()
                .filter(i -> seen.add(i.title() + "|" + i.lineNumber()))
                .toList();
    }

    private List<Issue> parseIssues(JsonNode node) {
        List<Issue> issues = new ArrayList<>();
        if (!node.isArray()) return issues;
        for (JsonNode item : node) {
            String severity = normalizeSeverity(item.path("severity").asText("Low"));
            String title = item.path("title").asText("Untitled");
            String description = item.path("description").asText("");
            String filePath = item.path("filePath").isNull() ? null : item.path("filePath").asText(null);
            Integer lineNumber = item.path("lineNumber").isNull() ? null : item.path("lineNumber").asInt();
            issues.add(new Issue(severity, title, description, filePath, lineNumber));
        }
        return issues;
    }

    private String normalizeSeverity(String severity) {
        if (severity == null) return "Low";
        return switch (severity.trim().toLowerCase()) {
            case "critical" -> "Critical";
            case "high" -> "High";
            case "medium" -> "Medium";
            default -> "Low";
        };
    }

    /**
     * Robustly extract a JSON object from an AI response that may contain
     * markdown fences, leading text, or other non-JSON content.
     */
    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) return "{}";
        String trimmed = text.trim();

        // Find the first { and last } — works for any wrapping (markdown, plain text, etc.)
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        // No JSON object found — return empty object so parsing doesn't throw
        return "{}";
    }

    private FileAnalysisResult failedResult(String filePath, String errorMessage) {
        return new FileAnalysisResult(
                filePath, "plaintext", "modified",
                0, 0, 0, null,
                "FAILED", errorMessage,
                List.of(), List.of(), List.of(), List.of()
        );
    }

    private FileAnalysisResult failedResult(DiffParser.DiffFile file, String errorMessage) {
        return new FileAnalysisResult(
                file.filePath(), file.language(), file.changeType(),
                file.linesAdded(), file.linesRemoved(), 0, null,
                "FAILED", errorMessage,
                List.of(), List.of(), List.of(), List.of()
        );
    }
}
