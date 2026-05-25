package com.aicodereviewer.backend.ai.router;

/**
 * Strategy for selecting which AI provider handles a request.
 */
public enum RoutingStrategy {
    /** Route based on task type (deep-review → Gemini, fast-analysis → Groq, etc.) */
    TASK_BASED,
    /** Prefer the cheapest available provider */
    COST_OPTIMIZED,
    /** Distribute requests evenly across providers */
    ROUND_ROBIN
}
