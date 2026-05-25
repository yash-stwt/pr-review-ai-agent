package com.aicodereviewer.backend.controller;

import com.aicodereviewer.backend.dto.ReviewRequest;
import com.aicodereviewer.backend.dto.ReviewResponse;
import com.aicodereviewer.backend.dto.ImproveCodeRequest;
import com.aicodereviewer.backend.dto.ImproveCodeResponse;
import com.aicodereviewer.backend.dto.InlineReviewResponse;
import com.aicodereviewer.backend.dto.FixRequest;
import com.aicodereviewer.backend.dto.FixResponse;
import com.aicodereviewer.backend.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/analyze")
    public ReviewResponse analyze(@Valid @RequestBody ReviewRequest request) {
        return reviewService.analyzeDiff(request.diffText(), request.preferredProviderId());
    }

    @PostMapping("/improve-code")
    public ImproveCodeResponse improveCode(@Valid @RequestBody ImproveCodeRequest request) {
        return reviewService.generateCodeImprovements(request.diffText(), request.analysis());
    }

    @PostMapping("/inline")
    public InlineReviewResponse inlineReview(@Valid @RequestBody ReviewRequest request) {
        return reviewService.generateInlineReview(request.diffText());
    }

    @PostMapping("/fix")
    public FixResponse generateFix(@Valid @RequestBody FixRequest request) {
        return reviewService.generateFix(request);
    }
}
