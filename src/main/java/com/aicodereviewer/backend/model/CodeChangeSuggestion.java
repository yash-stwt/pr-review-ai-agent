package com.aicodereviewer.backend.model;

public record CodeChangeSuggestion(
        String filePath,
        String rationale,
        String beforeCode,
        String afterCode
) {
}
