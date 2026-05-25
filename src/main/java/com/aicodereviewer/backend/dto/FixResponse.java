package com.aicodereviewer.backend.dto;

/**
 * Response DTO for POST /api/review/fix — contains the AI-generated fix.
 */
public record FixResponse(
        String findingId,       // first 6 chars of SHA-256(filePath + lineNumber + findingTitle)
        String filePath,
        int startLine,
        int endLine,
        String beforeCode,
        String afterCode,
        String patch,           // unified diff string
        String explanation      // one-sentence rationale for the fix
) {}
