package com.aicodereviewer.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async configuration for file-wise analysis and other concurrent AI operations.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("fileAnalysisExecutor")
    public ExecutorService fileAnalysisExecutor(
            @Value("${ai.file-analysis.max-parallel:4}") int maxParallel) {
        return Executors.newFixedThreadPool(maxParallel, r -> {
            Thread t = new Thread(r);
            t.setName("file-analysis-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }
}
