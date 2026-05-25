package com.aicodereviewer.backend.dto;

/**
 * Status of an async file-wise analysis job (for PRs with >20 files).
 */
public record FileAnalysisJobStatus(
        String jobId,
        String status,          // "PENDING" | "PROCESSING" | "COMPLETE" | "FAILED"
        int progress,           // 0-100
        PRAnalysisResponse result  // null until status is "COMPLETE"
) {}
