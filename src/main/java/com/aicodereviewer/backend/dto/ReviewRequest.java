package com.aicodereviewer.backend.dto;

import jakarta.validation.constraints.NotNull;

public record ReviewRequest(
        @NotNull String diffText,
        String preferredProviderId  // optional — user-selected provider from the UI
) {
    // Backward-compatible constructor for callers that don't supply a provider
    public ReviewRequest(@NotNull String diffText) {
        this(diffText, null);
    }
}
