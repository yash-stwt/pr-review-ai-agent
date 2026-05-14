package com.aicodereviewer.backend.dto;

import java.util.List;

/**
 * Aggregated PR-level analysis assembled from all per-file results.
 */
public record PRAnalysisResponse(
        int overallRiskScore,       // weighted average by lines changed
        String executiveSummary,
        int criticalCount,
        int totalFindings,
        int filesAnalyzed,
        List<FileAnalysisResult> fileAnalyses
) {}
