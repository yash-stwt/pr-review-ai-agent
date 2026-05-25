package com.aicodereviewer.backend.model;

public record Issue(
        String severity,    // Critical | High | Medium | Low
        String title,
        String description,
        String filePath,    // nullable — which file this issue belongs to
        Integer lineNumber  // nullable — new-side line number in the diff
) {
    /** Backward-compatible constructor for callers that don't supply line info. */
    public Issue(String severity, String title, String description) {
        this(severity, title, description, null, null);
    }
}
