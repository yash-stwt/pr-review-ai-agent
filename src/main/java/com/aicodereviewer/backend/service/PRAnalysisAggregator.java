package com.aicodereviewer.backend.service;

import com.aicodereviewer.backend.dto.FileAnalysisResult;
import com.aicodereviewer.backend.dto.PRAnalysisResponse;
import com.aicodereviewer.backend.model.Issue;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Aggregates per-file analysis results into a PR-level summary.
 */
@Component
public class PRAnalysisAggregator {

    /**
     * Compute weighted overall risk score and aggregate counts.
     *
     * @param results          per-file analysis results
     * @param executiveSummary AI-generated PR-level summary
     */
    public PRAnalysisResponse aggregate(List<FileAnalysisResult> results, String executiveSummary) {
        if (results == null || results.isEmpty()) {
            return new PRAnalysisResponse(0, executiveSummary, 0, 0, 0, List.of());
        }

        // Weighted average: riskScore * (linesAdded + linesRemoved) / totalChurn
        long totalChurn = 0;
        long weightedRiskSum = 0;
        int criticalCount = 0;
        int totalFindings = 0;

        for (FileAnalysisResult file : results) {
            long churn = Math.max(1, (long) file.linesAdded() + file.linesRemoved());
            totalChurn += churn;
            weightedRiskSum += (long) file.riskScore() * churn;

            criticalCount += countHighSeverity(file.bugs());
            criticalCount += countHighSeverity(file.security());
            criticalCount += countHighSeverity(file.quality());

            totalFindings += safeSize(file.bugs())
                    + safeSize(file.security())
                    + safeSize(file.quality())
                    + safeSize(file.improvements());
        }

        int overallRiskScore = totalChurn == 0 ? 0 : (int) Math.min(100, Math.max(0, weightedRiskSum / totalChurn));

        return new PRAnalysisResponse(
                overallRiskScore,
                executiveSummary,
                criticalCount,
                totalFindings,
                results.size(),
                results
        );
    }

    private int countHighSeverity(List<Issue> issues) {
        if (issues == null) return 0;
        return (int) issues.stream()
                .filter(i -> "Critical".equalsIgnoreCase(i.severity()) || "High".equalsIgnoreCase(i.severity()))
                .count();
    }

    private int safeSize(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
