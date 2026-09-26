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

## Conversation sessions

The frontend automatically creates a temporary conversation on the first message.
Follow-ups include recent user and assistant turns as context. New chat starts a
fresh session and requests deletion of the old one. Refreshing the page also starts
fresh; there is no database or saved history yet.

API lifecycle (all routes use the existing access-key and rate-limit policy):

1. `POST /api/chat/sessions` returns 201 with `{"sessionId":"<UUID>"}`.
2. `POST /api/chat` with `{"message":"My name is Siva","sessionId":"<UUID>","requestId":"<new UUID>"}`
   returns the existing `{"reply":"..."}` shape. Reuse the session ID for follow-ups.
3. Retry a message with the same request ID and identical text. Completed replies
   are reused while their turns remain in memory; failed calls do not commit turns.
4. `DELETE /api/chat/sessions/{sessionId}` releases memory and returns 204, even if
   already deleted. Deletion cannot cancel provider work already in progress.

Both IDs must be supplied together. Omitting both preserves the stateless API.
Only successful user/assistant pairs enter memory. Concurrent calls for one session
return 409; unknown, deleted, or expired sessions return 410. The frontend asks you
to start a new chat on expiry rather than silently forgetting earlier context.
Retrying an in-progress request returns 409 until the original call finishes.

Memory is bounded by session count, completed-turn count, and UTF-16 character count
(not tokens). The oldest whole turns are trimmed first; the configured system prompt
is supplied separately. Tune the character budget to the deployed model's token limit.
Oversized or empty provider replies return 502 and are not committed. Idle expiry
is checked on session creation and message access; active calls do not expire.

This store is process-local: restarting Spring loses sessions. Multiple instances
need sticky routing or a shared store. Session IDs are unguessable bearer capabilities,
not user authentication. Keep them private, avoid logging session URLs/request bodies,
and restrict deployment access until user ownership is implemented. No history-list
or history-read endpoint is exposed.

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
| CHAT_MAX_SESSIONS | 500 | Maximum temporary sessions per backend instance |
| CHAT_MEMORY_MAX_TURNS | 10 | Maximum retained completed user/assistant pairs |
| CHAT_MEMORY_MAX_CHARACTERS | 64000 | Retained context character budget; minimum 32000 |
| CHAT_SESSION_IDLE_TIMEOUT | 30m | Idle lifetime of temporary sessions |

Messages must be nonblank and at most 16,000 characters. Virtual threads handle
blocking model requests; a semaphore bounds provider concurrency without queuing.
The rate limit is shared across callers, not a per-user quota. Multi-instance
deployments needing a shared quota should enforce it at the gateway.

Errors use application/problem+json: invalid input is 400, missing/incorrect
configured access key is 401, rate limit is 429, exhausted concurrency or provider
connection/quota failures are 503, other provider failures are 502, and unexpected
failures are 500. Rate and model-concurrency limits include Retry-After. Error responses
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
