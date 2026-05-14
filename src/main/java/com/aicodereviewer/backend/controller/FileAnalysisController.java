package com.aicodereviewer.backend.controller;

import com.aicodereviewer.backend.dto.FileAnalysisJobStatus;
import com.aicodereviewer.backend.dto.PRAnalysisResponse;
import com.aicodereviewer.backend.dto.ReviewRequest;
import com.aicodereviewer.backend.service.FileAnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Endpoints for per-file analysis of pull requests.
 */
@RestController
@RequestMapping("/api/review")
public class FileAnalysisController {

    private final FileAnalysisService fileAnalysisService;

    public FileAnalysisController(FileAnalysisService fileAnalysisService) {
        this.fileAnalysisService = fileAnalysisService;
    }

    /**
     * POST /api/review/analyze-files
     * Synchronous for ≤20 files, async (202 + jobId) for >20 files.
     */
    @PostMapping("/analyze-files")
    public ResponseEntity<?> analyzeFiles(@Valid @RequestBody ReviewRequest request) {
        if (fileAnalysisService.shouldProcessAsync(request.diffText())) {
            String jobId = fileAnalysisService.analyzeAsync(request.diffText());
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        }

        PRAnalysisResponse result = fileAnalysisService.analyzeSync(request.diffText());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/review/analyze-files/{jobId}
     * Poll async job status.
     */
    @GetMapping("/analyze-files/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        Optional<FileAnalysisJobStatus> status = fileAnalysisService.getJobStatus(jobId);
        if (status.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "errorCode", "JOB_NOT_FOUND",
                    "message", "Job '" + jobId + "' not found"
            ));
        }
        return ResponseEntity.ok(status.get());
    }
}
