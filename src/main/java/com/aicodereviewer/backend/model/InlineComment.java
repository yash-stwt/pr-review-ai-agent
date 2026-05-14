package com.aicodereviewer.backend.model;

public record InlineComment(
        String filePath,
        int lineNumber,   // 1-based new-side line number
        String side,      // "RIGHT" (added/context) or "LEFT" (removed)
        String severity,  // Critical | High | Medium | Low
        String category,  // bug | security | quality | improvement
        String title,
        String body
) {
}
