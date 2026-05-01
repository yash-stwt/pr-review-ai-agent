package com.aicodereviewer.backend.dto;

import com.aicodereviewer.backend.model.CodeChangeSuggestion;

import java.util.List;

public record ImproveCodeResponse(
        String summary,
        List<CodeChangeSuggestion> changes
) {
}
