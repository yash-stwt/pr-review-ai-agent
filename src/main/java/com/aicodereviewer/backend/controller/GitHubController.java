package com.aicodereviewer.backend.controller;

import com.aicodereviewer.backend.dto.GitHubDiffRequest;
import com.aicodereviewer.backend.dto.GitHubDiffResponse;
import com.aicodereviewer.backend.service.GitHubService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github")
public class GitHubController {

    private final GitHubService gitHubService;

    public GitHubController(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    @PostMapping("/pr-diff")
    public GitHubDiffResponse fetchPullRequestDiff(@Valid @RequestBody GitHubDiffRequest request) {
        String diffText = gitHubService.fetchPullRequestDiff(
                request.token(),
                request.owner(),
                request.repo(),
                request.prNumber()
        );
        return new GitHubDiffResponse(diffText);
    }
}
