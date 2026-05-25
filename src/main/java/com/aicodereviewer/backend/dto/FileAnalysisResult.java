package com.aicodereviewer.backend.dto;

import com.aicodereviewer.backend.model.Issue;

import java.util.List;

/**
 * Per-file analysis result produced by analyzing a single changed file independently.
 */
public record FileAnalysisResult(
        String filePath,
        String language,
        String changeType,      // "added" | "modified" | "deleted" | "renamed" | "binary"
        int linesAdded,
        int linesRemoved,
        int riskScore,          // 0-100
        String summary,
        String status,          // "OK" | "FAILED"
        String errorMessage,    // null when status is "OK"
        List<Issue> bugs,
        List<Issue> security,
        List<Issue> quality,
        List<Issue> improvements
) {}
