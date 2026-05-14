package com.aicodereviewer.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for POST /api/review/fix — generate an AI fix for a specific finding.
 */
public record FixRequest(
        @NotBlank @Size(max = 100_000, message = "Diff text exceeds maximum allowed size") String diffText,
        @NotBlank String filePath,
        @Min(1) int lineNumber,
        @NotBlank String findingTitle,
        @NotBlank String findingDescription,
        @NotBlank String severity,
        boolean forceGenerate   // default false — when false, only Critical/High findings get fixes
) {}
