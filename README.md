# AI Code Reviewer — Backend

A production-grade Spring Boot backend that powers AI-assisted pull request reviews. It parses git diffs, routes analysis requests to multiple AI providers (Groq, Gemini, Claude), and returns structured findings with severity classification, inline comments, per-file analysis, and AI-generated code fixes.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 17 |
| Framework | Spring Boot 3.3.5 |
| Build | Maven |
| AI Providers | Groq, Google Gemini, Anthropic Claude, OpenAI (stub) |
| Validation | Jakarta Bean Validation |
| Testing | JUnit 5, Mockito, jqwik (property-based) |

---

## Features

- **Multi-Model AI Architecture** — pluggable provider system with task-based routing, retry with exponential backoff, and automatic fallback
- **Git Diff Analysis** — enriched diff parser with language detection, change type classification, hunk grouping, and line mapping
- **PR Review** — AI-generated risk score (0–100), executive summary, and categorized findings (bugs, security, quality, improvements)
- **Inline Review Comments** — findings mapped to exact diff line numbers
- **File-Wise Analysis** — independent per-file AI analysis with concurrent processing (up to 4 parallel), chunking for large files, and async polling for large PRs (>20 files)
- **Apply AI Fix** — generates minimal before/after code patches for Critical/High severity findings
- **GitHub Integration** — fetches PR diffs directly from the GitHub API
- **LRU Response Cache** — 100-entry, 10-minute TTL cache to avoid redundant AI calls
- **Token Usage Tracking** — per-provider rolling statistics

---

## Project Structure

```
backend/src/main/java/com/aicodereviewer/backend/
├── ai/
│   ├── adapter/          # Provider adapters (Groq, Gemini, Claude, OpenAI)
│   ├── cache/            # LRU AI response cache
│   ├── exception/        # AiProviderException, ProviderUnavailableException
│   ├── model/            # AiRequest, NormalizedResponse, TokenUsage, ProviderMetadata
│   ├── port/             # AiProviderPort interface
│   ├── registry/         # ProviderRegistry
│   ├── router/           # AiRouter (routing + retry + fallback)
│   └── tracker/          # TokenUsageTracker
├── config/
│   ├── AsyncConfig.java  # Thread pool for file analysis
│   ├── CorsConfig.java
│   └── GlobalExceptionHandler.java
├── controller/
│   ├── FileAnalysisController.java
│   ├── GitHubController.java
│   ├── ProviderController.java
│   └── ReviewController.java
├── dto/                  # Request/Response DTOs
├── model/                # Domain models (Issue, InlineComment, CodeChangeSuggestion)
├── service/
│   ├── FileAnalysisService.java
│   ├── PRAnalysisAggregator.java
│   ├── GitHubService.java
│   └── ReviewService.java
└── util/
    └── DiffParser.java   # Enriched unified diff parser
```

---

## API Endpoints

### Review

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/review/analyze` | Full PR diff analysis with risk score and findings |
| `POST` | `/api/review/inline` | Inline review comments mapped to diff lines |
| `POST` | `/api/review/improve-code` | AI-generated code improvement plan |
| `POST` | `/api/review/fix` | Generate a minimal AI fix for a specific finding |
| `POST` | `/api/review/analyze-files` | Per-file independent analysis (sync ≤20 files, async >20) |
| `GET` | `/api/review/analyze-files/{jobId}` | Poll async file analysis job status |

### GitHub

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/github/pr-diff` | Fetch PR diff from GitHub using a personal access token |

### Providers

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/providers` | List all registered AI providers with status |
| `POST` | `/api/providers/select` | Validate a provider selection |
| `GET` | `/api/providers/usage` | Token usage statistics per provider |

---

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- A [Groq API key](https://console.groq.com) (free tier available)

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `GROQ_API_KEY` | ✅ Yes | Groq API key |
| `GROQ_API_MODEL` | No | Model name (default: `llama-3.3-70b-versatile`) |
| `GROQ_API_URL` | No | Groq endpoint (default: Groq OpenAI-compatible URL) |
| `GEMINI_API_KEY` | No | Google Gemini API key (enables Gemini provider) |
| `CLAUDE_API_KEY` | No | Anthropic Claude API key (enables Claude provider) |

### Run Locally

```bash
# Clone the repository
git clone <repo-url>
cd backend

# Set required environment variable
export GROQ_API_KEY=your_groq_api_key_here

# Run with Maven
mvn spring-boot:run
```

The server starts on **http://localhost:8081**.

### Build JAR

```bash
mvn clean package -DskipTests
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

### Run Tests

```bash
mvn test
```

---

## Configuration

All settings are in `src/main/resources/application.properties`. Key options:

```properties
# AI routing strategy: TASK_BASED | COST_OPTIMIZED | ROUND_ROBIN
ai.routing.strategy=TASK_BASED

# Task-to-provider mapping (used with TASK_BASED strategy)
ai.routing.task.fast-analysis=groq
ai.routing.task.deep-review=groq
ai.routing.task.security-review=groq
ai.routing.task.fix-generation=groq

# File analysis concurrency
ai.file-analysis.max-parallel=4
ai.file-analysis.chunk-size=300
ai.file-analysis.async-threshold=20

# Response cache
ai.cache.max-entries=100
ai.cache.ttl-minutes=10
```

---

## AI Provider Routing

The `AiRouter` selects providers using a configurable strategy:

- **TASK_BASED** — routes each task type (fast-analysis, deep-review, security-review, fix-generation) to a configured provider
- **COST_OPTIMIZED** — always uses the cheapest available provider
- **ROUND_ROBIN** — distributes requests evenly

When a user selects a specific provider from the UI, that provider is tried **3 times** with exponential backoff (1s, 2s, 3s) before falling back to other configured providers.

---

## Docker

```dockerfile
FROM eclipse-temurin:17-jre
COPY target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

```bash
docker build -t ai-code-reviewer-backend .
docker run -p 8081:8081 \
  -e GROQ_API_KEY=your_key \
  ai-code-reviewer-backend
```

---

## License

MIT
