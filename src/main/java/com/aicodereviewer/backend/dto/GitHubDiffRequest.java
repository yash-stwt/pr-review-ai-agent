package com.aicodereviewer.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record GitHubDiffRequest(
        @NotBlank String token,
        @NotBlank String owner,
        @NotBlank String repo,
        @NotBlank String prNumber
) {
}
