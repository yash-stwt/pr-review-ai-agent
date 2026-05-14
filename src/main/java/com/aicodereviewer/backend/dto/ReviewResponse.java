package com.aicodereviewer.backend.dto;

import com.aicodereviewer.backend.model.Issue;

import java.util.List;
import java.util.Map;

public record ReviewResponse(
        int riskScore,
        String executiveSummary,
        int criticalCount,
        List<Issue> bugs,
        List<Issue> security,
        List<Issue> quality,
        List<Issue> improvements,
        Map<String, Object> _meta  // provider metadata: providerId, modelId, latencyMs, tokens
) {
}
