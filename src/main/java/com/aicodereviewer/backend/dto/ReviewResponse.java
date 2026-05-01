package com.aicodereviewer.backend.dto;

import com.aicodereviewer.backend.model.Issue;

import java.util.List;

public record ReviewResponse(
        int riskScore,
        List<Issue> bugs,
        List<Issue> security,
        List<Issue> quality,
        List<Issue> improvements
) {
}
