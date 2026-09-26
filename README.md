# Spring AI chat API

The React + Vite + Tailwind frontend lives in [`frontend/`](frontend/README.md).
See its README for local setup, architecture, checks, and deployment requirements.

Java 21, Spring Boot 4.1 and Spring AI 2.0. Configure
AZURE_OPENAI_BASE_URL (your Azure OpenAI v1 URL), AZURE_OPENAI_API_KEY and
AZURE_OPENAI_DEPLOYMENT, then run:

```powershell
.\mvnw.cmd spring-boot:run
```

POST /api/chat with Content-Type: application/json and
`{"message":"Hello"}`. Success remains `{"reply":"..."}`.

## Access and limits

Local use preserves unauthenticated access when CHAT_API_KEY is unset. Setting
CHAT_API_KEY requires the X-API-Key header for API and exposed management routes;
this is a separate application key, never the Azure provider credential.
Health remains public and does not expose details.

For production, set SPRING_PROFILES_ACTIVE=prod and a nonblank CHAT_API_KEY.
Startup fails without the key. Only health is exposed through Actuator in this
profile. Serve the API over HTTPS through your deployment infrastructure.

| Setting | Default | Meaning |
| --- | --- | --- |
| CHAT_MAX_CONCURRENT_REQUESTS | 16 | Maximum simultaneous model calls per instance |
| CHAT_REQUESTS_PER_MINUTE | 60 | Shared token bucket refill rate and burst capacity per instance |
| AI_TIMEOUT | 60s | Provider SDK request timeout |
| AI_MAX_RETRIES | 0 | Additional provider attempts; raising this increases total latency |

Messages must be nonblank and at most 16,000 characters. Virtual threads handle
blocking model requests; a semaphore bounds provider concurrency without queuing.
The rate limit is shared across callers, not a per-user quota. Multi-instance
deployments needing a shared quota should enforce it at the gateway.

Errors use application/problem+json: invalid input is 400, missing/incorrect
configured access key is 401, rate limit is 429, exhausted concurrency or provider
connection/quota failures are 503, other provider failures are 502, and unexpected
failures are 500. Rate and capacity limits include Retry-After. Error responses
omit internal exception messages. Unexpected failures are logged server-side;
restrict access to those logs.

## Tests

```powershell
.\mvnw.cmd test
```

Tests supply placeholder provider configuration and mock model calls. No Azure
credentials or live provider requests are required. Tests cover the existing
success contract, validation, safe errors, authentication, rate limiting and
concurrency recovery. A passing suite does not verify live Azure connectivity.
