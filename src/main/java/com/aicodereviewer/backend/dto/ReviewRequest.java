package com.aicodereviewer.backend.dto;

import jakarta.validation.constraints.NotNull;

public record ReviewRequest(@NotNull String diffText) {
}
