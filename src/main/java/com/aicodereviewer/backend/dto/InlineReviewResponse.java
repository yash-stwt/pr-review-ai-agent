package com.aicodereviewer.backend.dto;

import com.aicodereviewer.backend.model.InlineComment;

import java.util.List;
import java.util.Map;

public record InlineReviewResponse(
        String diffText,
        List<InlineComment> comments,
        Map<String, List<InlineComment>> commentsByFile  // keyed by filePath for fast frontend lookup
) {
}
