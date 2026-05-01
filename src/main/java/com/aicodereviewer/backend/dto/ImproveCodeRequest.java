package com.aicodereviewer.backend.dto;

import jakarta.validation.constraints.NotNull;

public record ImproveCodeRequest(
        @NotNull String diffText,
        @NotNull ReviewResponse analysis
) {
}
