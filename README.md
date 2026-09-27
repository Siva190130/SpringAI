# Spring AI chat API

The React + Vite + Tailwind frontend lives in [`frontend/`](frontend/README.md).
See its README for local setup, architecture, checks, and deployment requirements.

For the current Docker frontend + backend + MySQL workflow, follow [implement.md](implement.md#phase-4--complete-local-verification-and-operating-guide).
The phased Azure deployment checklist is in [plan.md](plan.md).

Java 21, Spring Boot 4.1 and Spring AI 2.0. Configure
AZURE_OPENAI_BASE_URL (your Azure OpenAI v1 URL), AZURE_OPENAI_API_KEY and
AZURE_OPENAI_DEPLOYMENT, plus the MySQL settings described below, then run:

```powershell
.\mvnw.cmd spring-boot:run
```

POST /api/chat with Content-Type: application/json and
`{"message":"Hello"}`. Success remains `{"reply":"..."}`.

## MySQL setup

MySQL 8.0+ should be running on `127.0.0.1:3306`. Use your existing account with
permission to create databases and tables. In the PowerShell terminal used to
start Spring, configure the credentials without putting the password in command history:

```powershell
$env:DB_USERNAME = 'your_mysql_username'
$mysqlPassword = Read-Host 'MySQL password' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $mysqlPassword).Password
Remove-Variable mysqlPassword
.\mvnw.cmd spring-boot:run
```

The default JDBC URL creates the `spring_ai` database if absent. Flyway then creates
and versions `conversations`, `conversation_turns`, and its migration history table.
An existing unrelated database is never dropped or modified. If the database has
already been created, the account needs DDL permissions for migrations and normal
SELECT/INSERT/UPDATE/DELETE permissions. Override `DB_URL` for another host/port;
the default is `jdbc:mysql://127.0.0.1:3306/spring_ai?createDatabaseIfNotExist=true&connectionTimeZone=UTC`.
Use TLS with certificate verification for a remote database.

These variables are local to that terminal. If launching from IntelliJ, configure
`DB_USERNAME` and `DB_PASSWORD` in the local Run Configuration environment instead.
Do not commit credentials or put database credentials in frontend `.env` files.
Spring does not automatically load `.env` files. The Azure variables are still required.

## Saved conversations

The frontend creates a conversation on the first message. Full completed turns are
stored in MySQL and survive refreshes and backend restarts. New chat clears the
current view without deleting old conversations. The sidebar reopens, renames, and
permanently deletes chats with confirmation. A first-message excerpt becomes the
automatic title without an extra model request. Manual titles are never overwritten.
The current conversation ID is kept in tab-scoped session storage for refresh recovery;
message content lives in MySQL. Old in-memory sessions from earlier versions cannot be migrated.

API lifecycle (all routes use the existing access-key and rate-limit policy):

1. `POST /api/chat/sessions` returns 201 with `{"sessionId":"<UUID>"}`.
2. `POST /api/chat` with `{"message":"My name is Siva","sessionId":"<UUID>","requestId":"<new UUID>"}`
   returns the existing `{"reply":"..."}` shape. Reuse the session ID for follow-ups.
3. Retry a message with the same request ID and identical text. Completed replies
   are reused from durable history, including after a restart; failed calls do not save half a turn.
4. `GET /api/chat/sessions?offset=0` returns `{items, hasMore}` with up to 30 conversations,
   ordered by most recently completed activity. Use increasing offsets for more pages.
5. `GET /api/chat/sessions/{sessionId}` returns `{conversation, turns, hasMore, nextBefore}`.
   It returns the newest 30 completed turns in chronological order. Pass `?before=<nextBefore>`
   to retrieve older turns. Turn IDs are JSON strings to preserve BIGINT precision.
6. `PATCH /api/chat/sessions/{sessionId}` with `{"title":"Java notes"}` renames the chat (1–120 characters).
7. `DELETE /api/chat/sessions/{sessionId}` permanently removes the conversation and its
   messages in a cascading database operation; it returns 204 even if already deleted.
   Deletion cannot cancel provider work already in progress, but a late response cannot recreate the chat.

Both IDs must be supplied together. Omitting both preserves the stateless API.

### Streaming replies

The frontend uses `POST /api/chat/stream` with the same request body as `/api/chat`.
The response is UTF-8 `application/x-ndjson`: one JSON event per line, flushed as it arrives.

```json
{"type":"delta","text":"Hello"}
{"type":"delta","text":" there"}
{"type":"done","reply":"Hello there"}
```

Only `done` confirms success. It is emitted after the complete turn is committed.
A completed retry can return only `done`, containing the saved reply. Provider failures,
timeouts, invalid replies, or detected client write failures do not save a partial turn.
After streaming begins, errors use `{"type":"error","status":502}` (or another appropriate
status) because the HTTP headers may already have been sent. Authorization and input
validation still return normal HTTP errors before streaming starts.

Streams have a 75-second total provider deadline and a 64,000-character reply ceiling;
saved turns also obey the configured memory character limit. Keep the request lease longer
than the stream deadline. Stop aborts the browser request and discards partial text. The server
detects disconnection on a subsequent write; provider cancellation is best effort. A complete
turn committed just before a disconnect remains available through the same request ID.
The existing `/api/chat` JSON contract remains available.

Disable response buffering and compression buffering for `/api/chat/stream` at the gateway,
and allow at least 90 seconds for the request. The server sends `X-Accel-Buffering: no` for
compatible proxies. Verify streaming through the actual deployment gateway.

Only successful user/assistant pairs are persisted. Concurrent calls for one conversation
return 409; unknown or deleted conversations return 410. Conversations no longer expire.
Database leases coordinate overlapping calls across backend instances without holding
a transaction or connection during the model request. A crashed process's lease becomes
available after five minutes. Configure the lease longer than the maximum provider call
duration, including retries. A lease token fences off stale completions. Completed requests
are idempotent; a crash after the provider responds but before the database commit can still
require another provider call and incur additional cost.

Saved history is separate from model memory. Only the latest 10 completed turns within
64,000 UTF-16 characters are included as context; the configured system prompt is separate.
Older turns remain in the database. Tune the budget to the deployed model's token limit.
Empty or oversized replies return 502 without committing a turn. Back up MySQL if chat
history matters; UI deletion is permanent in the live database, not a purge of backups.

This version is for personal use with one shared history, not private per-user histories.
Spring binds to localhost by default. Authentication and ownership must be added before
exposing the shared workspace publicly. `SERVER_ADDRESS` can change the bind address for
an appropriately protected deployment. Session IDs do not substitute for authentication.

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
| CHAT_MEMORY_MAX_TURNS | 10 | Maximum completed pairs sent to the model (1–100) |
| CHAT_MEMORY_MAX_CHARACTERS | 64000 | Retained context character budget; minimum 32000 |
| CHAT_REQUEST_LEASE_SECONDS | 300 | Per-conversation in-flight request lease; minimum 60 |

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
concurrency recovery, Flyway migrations, persistence, paging, rename, delete, and restart recovery.
Normal tests use isolated H2 databases in MySQL compatibility mode. The storage tests can
also run against an isolated local MySQL test instance via `-Dtest.mysql.port=3307`;
the test fixture expects root with an empty password and creates random `spring_ai_test_*`
schemas. Never point this test setting at your normal database server. The implementation
was also checked against an isolated MySQL 8.0 instance. A passing suite does not verify
live Azure connectivity.
