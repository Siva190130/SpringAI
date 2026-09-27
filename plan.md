# Plan: Dockerize the application and deploy to Azure

## Our goal

Learn Docker and Azure App Service by deploying this chat application and accessing it through an internet HTTPS URL.

We will use:

- A separate Docker container for the Spring Boot backend.
- A separate Docker container for the React frontend, served by Nginx.
- An official MySQL Docker image during local development.
- Docker Compose to run all three services locally.
- Two Azure App Services for the frontend and backend, initially sharing one Linux App Service Plan.
- Azure Container Registry to store the application images.
- Azure Database for MySQL Flexible Server when we move to Azure.
- Azure sign-in restricted to your account, because the application currently has shared conversation history.

Local request flow:

```text
Browser -> Frontend container -> Backend container -> MySQL container
                                                 -> Azure OpenAI
```

Azure request flow:

```text
Browser -> Azure sign-in -> Frontend App Service -> Backend App Service
                                               -> Backend uses Azure MySQL and Azure OpenAI
```

See [review.md](review.md) for the detailed repository findings and deployment considerations.

## How we will use this plan

Work through the phases in order. Each phase should produce a working result before we move to the next one.

- Mark a task `[x]` only after it is implemented and verified.
- After each completed feature, update its checkbox and add a short progress entry with the change, verification result, and next action.
- Record blockers or changed decisions here rather than silently skipping a task.
- Keep credentials outside source control, Docker images, and browser code.
- Preserve unrelated existing changes in the repository.

**Current status:** Phases 1 through 4 are complete. Azure frontend and backend App Services are deployed in `rg-springai-learning`. On 2026-09-27, the frontend Nginx TLS verification-depth fix was built, pushed, and deployed; App Service reported `Running` and `/healthz` returned 200. The user subsequently confirmed the application is working at https://springai-frontend-siva123.azurewebsites.net. Azure phases 5 through 8 remain partially verified; the unchecked items below are not implied complete by functional access. See [implement.md](implement.md#azure-deployment-and-tls-proxy-fix) for evidence and commands.

**Next implementation task:** Reconcile the existing Azure resources with the remaining checklist, verify account-restricted frontend access and backend application-key enforcement, then complete the Azure workflow, persistence, and operations checks. Do not recreate existing resources.

## Phase 1 — Run MySQL in Docker

Keep the backend and frontend running normally on the computer for this phase. Change only where the local database runs.

- [x] Confirm Docker is installed and can run Linux containers.
- [x] Select and pin an official MySQL image version compatible with the intended Azure MySQL version.
- [x] Create a Docker Compose configuration containing the MySQL service first.
- [x] Configure the database name and a dedicated application user through an uncommitted environment file; provide a safe example file without real credentials.
- [x] Use a named volume so the database survives container replacement and ordinary Compose shutdown.
- [x] Publish MySQL only on the local computer, using a free port to avoid conflicts with any existing MySQL installation.
- [x] Add a database health check and document startup/shutdown commands. Explain that removing the database volume deletes its data.
- [x] Point the existing backend at this database and let Flyway create the tables.
- [x] Run the existing frontend and backend, send a message, and verify that saved history survives database and backend restarts.

**Done when:** The application works locally with MySQL running in Docker and its data persists across restarts.

**Verified result:** Official MySQL 8.4.11, pinned by image digest, is healthy at `127.0.0.1:3307`. Flyway applied migration V1. A live `gpt-5.4` reply sent through the frontend proxy was stored and recovered after Compose down/up and backend restart. Chromium reopened the saved chat and restored it after refresh with zero page errors. All 43 existing backend tests passed.

Start with a fresh local learning database. Moving any existing database contents is a separate optional task, not an automatic part of switching connections.

## Phase 2 — Dockerize the backend

- [x] Add a multi-stage Dockerfile that builds the application with Java 21 and runs the executable JAR in a Java 21 runtime image.
- [x] Add a Docker ignore file to exclude credentials, local outputs, and unrelated files from the build context.
- [x] Run the application as a non-root user and configure it to listen on `0.0.0.0:8080`.
- [x] Supply database settings, Azure OpenAI settings, the production profile, and `CHAT_API_KEY` at runtime.
- [x] Add the backend to Compose and connect it to MySQL using the database service name, not localhost.
- [x] Make backend startup wait for database readiness and verify that Flyway completes successfully.
- [x] Run the existing backend checks and verify container startup, `/actuator/health`, and rejection of API requests without the configured key.
- [x] Test one authenticated chat request with the actual provider configuration.

**Done when:** The backend runs successfully in Docker, uses the MySQL container, and enforces its production application key.

**Verified result:** The image build ran all 43 backend tests successfully. The container runs as UID/GID 10001 with Java 21, the `prod` profile, and MySQL service DNS. Health returned 200; missing/wrong keys returned 401; authenticated metrics access returned 404. A browser request through the local Vite proxy produced a real `gpt-5.4` reply without exposing the application key to the browser. Saved history survived refresh and backend restart. Graceful shutdown was confirmed. Image metadata has no application/database/provider credentials, and the runtime image excludes the source workspace and local environment files.

## Phase 3 — Dockerize the frontend

- [x] Add a multi-stage frontend Dockerfile: build React with a compatible Node version and `npm ci`, then serve the built files with unprivileged Nginx.
- [x] Add a frontend Docker ignore file to exclude local dependencies, generated output, and credentials.
- [x] Configure Nginx to serve the frontend on port 8080 with a fallback to `index.html` for frontend navigation.
- [x] Configure `/api/*` forwarding to preserve the complete API path, request method, body, and query parameters.
- [x] Supply the backend address and application key through a server-side startup template. Keep both out of the built browser configuration.
- [x] Have Nginx overwrite the upstream `X-API-Key` with the configured server-side key.
- [x] Disable response buffering and caching for streaming API responses and configure suitable timeouts.
- [x] Add a lightweight frontend `/healthz` endpoint.
- [x] Add the frontend to Compose and connect it to the backend using its Compose service name.
- [x] Run the existing frontend lint, type checks, tests, and production build.

**Done when:** Opening the local frontend container lets the browser use the backend through the frontend's `/api` routes, including streaming replies.

**Verified result:** Lint, type checks, all 18 frontend tests, and the production build passed inside Docker. All three containers are healthy. Nginx runs as UID/GID 101 and keeps its rendered configuration at permissions 600. SPA fallback, health, query forwarding, POST/PATCH/DELETE routes, and application-key replacement passed. A real model reply delivered 7 delta events across 6 network reads through Nginx, was saved, and appeared after browser refresh. Public JavaScript/CSS contained none of the checked credentials, and browser API requests contained no application key. Startup without a key was rejected.

## Phase 4 — Verify the complete local setup

- [x] Start frontend, backend, and MySQL together through Docker Compose using documented commands.
- [x] Confirm that only the necessary local ports are published and that the key-injecting frontend is restricted to local access during development.
- [x] Verify new chats, streaming replies, refresh recovery, history loading, rename, and delete.
- [x] Restart the frontend, backend, and database independently and confirm completed conversations remain available.
- [x] Verify interruption and retry behavior without treating partial responses as completed replies.
- [x] Check that application keys and provider/database credentials are absent from browser assets and logs.
- [x] Document how to build, start, stop, inspect logs, and intentionally reset the local learning database.

**Done when:** The complete application works through the local Dockerized frontend and can be started reproducibly.

**Verified result:** All 8 original conversation hashes stayed unchanged through full Compose stop/start and independent frontend/backend/MySQL restarts, with the original volume retained. Browser tests passed new chat, streaming, reopen, refresh, rename, cancelled delete, and confirmed delete. A real Stop removed partial text and saved no partial turn; retry reused the request ID and saved exactly one full reply. Simulating loss of the final acknowledgement at the browser boundary also discarded partial text, then recovered the already-saved reply as a `done`-only replay. Four actual credential values were absent from served HTML/JS/CSS and all three container logs. All containers are healthy, and reset instructions are documented without performing a destructive reset.

This is the first major milestone. Move to Azure after it works.

## Phase 5 — Prepare Azure resources

- [ ] Confirm the Azure subscription, region, resource names, and current estimated costs.
- [x] Confirm the dedicated resource group exists: `rg-springai-learning`.
- [ ] Create or verify a budget alert. Budget alerts notify us; they do not cap spending automatically.
- [x] Confirm Azure Container Registry exists and accepts frontend image pushes: `springailearningacr123`.
- [x] Confirm both Web Apps exist and report Running: `springai-frontend-siva123` and `springai-backend-siva123`.
- [ ] Verify the Linux App Service Plan, sharing arrangement, and sizing.
- [ ] Create Azure Database for MySQL Flexible Server using a version compatible with the local database.
- [ ] Create the application database and an account with the permissions required by Flyway and the application.
- [ ] Configure database network access for the backend and TLS certificate verification.
- [ ] Confirm that the Azure OpenAI deployment is available and has sufficient quota.
- [ ] Enable managed identities for image pulls and grant each Web App the appropriate registry permissions.

**Done when:** Azure resources exist and the required access, networking, and runtime settings are ready for deployment.

For the first Azure deployment, use a fresh database. Flyway creates its tables but does not copy local conversations. Transfer local data only if we explicitly decide to keep it.

## Phase 6 — Publish images and deploy the backend

- [x] Build and push the frontend image to `springailearningacr123.azurecr.io/spring-ai-frontend:latest`.
- [x] Verify the deployed backend sessions endpoint returns 200 JSON at `/api/chat/sessions?offset=0`.
- [ ] Build and push separately versioned backend and frontend images to Azure Container Registry.
- [ ] Configure the backend Web App to pull its image using its managed identity.
- [ ] Set the container port correctly for the selected App Service container mode and set the backend listener to `0.0.0.0:8080`.
- [ ] Configure `SPRING_PROFILES_ACTIVE=prod`, `CHAT_API_KEY`, Azure OpenAI settings, and the hosted database connection through backend App Service settings.
- [ ] Use a JDBC connection with verified TLS; do not reuse the local database address.
- [ ] Enable HTTPS Only, container logs, and the `/actuator/health` health check.
- [ ] Verify startup, database connectivity, and successful Flyway migrations.
- [ ] Verify rejection of unauthenticated application requests and perform one authenticated live chat request.

**Done when:** The backend runs in Azure, connects to Azure MySQL, and can obtain a model response.

The first setup can use a public backend HTTPS endpoint protected by the application key. This is not a private network connection. Private endpoints and tighter network restrictions can be a later learning exercise.

## Phase 7 — Protect and deploy the frontend

- [ ] Configure App Service Authentication on the frontend and restrict access to your account before exposing the key-injecting proxy.
- [ ] Protect both the frontend page and `/api/*` routes; verify the authentication configuration does not permit anonymous API use.
- [ ] Configure the frontend Web App to pull its image with its managed identity and listen on the selected container port.
- [x] Set the proxy backend address to `https://springai-backend-siva123.azurewebsites.net`; confirmed in frontend App Settings.
- [ ] Supply the same application key as the backend through server-side frontend settings.
- [x] Configure the HTTPS upstream with the correct host, TLS SNI, certificate verification, and `proxy_ssl_verify_depth 2` for the observed Azure certificate chain.
- [x] Deploy the frontend TLS fix and verify App Service Running and `/healthz` 200; user confirmed the application works afterward.
- [ ] Enable HTTPS Only, container logs, and the frontend health check. Keep any health authentication exception limited to `/healthz`.
- [ ] Verify streaming through the actual Azure gateway and proxy, not just locally.

**Done when:** You can sign in at the frontend's Azure URL and use the application over the internet.

User sign-in protects this personal workspace. It does not add separate histories for different users. Do not open access to multiple users expecting private conversations without implementing ownership checks first.

## Phase 8 — Verify Azure behavior and practice operations

- [ ] Test the frontend URL from an external network and verify that unauthorized visitors cannot use its API.
- [ ] Verify chat, incremental streaming, refresh recovery, history, rename, and delete.
- [ ] Restart both Web Apps and confirm saved conversations remain in Azure MySQL.
- [ ] Check health status and logs without exposing secrets or conversation content.
- [ ] Deploy a new image version and practice returning to the previous version.
- [ ] Document that image rollback does not undo database migrations; retain a database backup and compatibility strategy.
- [ ] Review resource usage, model usage, and costs; keep limits suitable for a small personal application.
- [ ] Document cleanup steps for the dedicated learning resources and any data that should be backed up first.

**Done when:** The application is accessible over the internet with protected access, durable history, useful diagnostics, and a repeatable deployment process.

## Later improvements

These are optional after the main goal is complete:

- Automated builds and deployments.
- Key Vault references for secrets.
- Private backend and database networking.
- Separate App Service Plans if independent scaling becomes useful.
- A custom domain.
- Real user ownership and isolated conversation histories for multiple users.

## Progress log

| Date | Phase or feature | Result and verification | Next action |
| --- | --- | --- | --- |
| 2026-09-27 | Planning | Created this ordered implementation plan. No implementation or deployment was performed in this task. | Start Phase 1 with the local MySQL Docker setup. |
| 2026-09-27 | Phase 1: MySQL setup | Added Compose, a pinned MySQL 8.4.11 image, localhost port 3307, a named volume, an application-account health check, and generated ignored credentials. Container became healthy. | Connect the existing backend. |
| 2026-09-27 | Phase 1: Backend connection | Added the local PowerShell helper. Java 21 package build passed, Flyway applied V1 to Docker MySQL, and backend health returned UP. | Verify a live chat and persistence. |
| 2026-09-27 | Phase 1: End-to-end verification | Live provider reply persisted through the Vite proxy. Container replacement and backend restart retained it; completed-request retry returned the saved reply. Chromium verified history and refresh recovery. All 43 backend tests passed. | Phase 1 complete; proceed to Phase 2 when we start the next implementation task. |
| 2026-09-27 | Phase 2: Image and Compose | Added the Java 21 multi-stage image, restricted build context, non-root runtime, health check, backend Compose profile, runtime settings template, and helper commands. All 43 tests passed inside the image build. | Verify container access and a live browser request. |
| 2026-09-27 | Phase 2: Runtime verification | Replaced the old host backend with the Docker backend. Verified production key enforcement, health, Flyway validation, live browser chat, replay, refresh recovery, and persisted history after graceful restart. MySQL data and credentials were preserved. | Phase 2 complete. Next: Phase 3, frontend containerization. |
| 2026-09-27 | Phase 3: Frontend image and proxy | Added the Node/Nginx multi-stage image, build-context allowlist, runtime template, health check, DNS-aware proxy, Compose frontend profile, and helper commands. Lint, types, all 18 tests, and production build passed. | Verify the running Nginx application. |
| 2026-09-27 | Phase 3: Runtime verification | All three containers healthy. Verified static assets, SPA fallback, query/body/method forwarding, key replacement, live incremental NDJSON, saved history, browser refresh, and credential isolation. | Phase 3 complete. Next: broader Phase 4 verification. |
| 2026-09-27 | Phase 4: Restart and operating commands | Added and verified `Restart` and `Logs` helper actions. Full Compose down/up and each independent service restart preserved hashes of all 8 original conversations and reused the original MySQL volume. | Verify browser recovery and credentials. |
| 2026-09-27 | Phase 4: Browser and credential verification | Passed complete browser lifecycle, real Stop/retry with one complete saved turn, simulated lost-acknowledgement recovery, and actual credential scans of public assets/container logs. All 8 original histories remained unchanged after the full test run. Documented normal operations and a future intentional reset. | Phase 4 complete. Next: Phase 5 Azure planning and resource preparation. |
| 2026-09-27 | Azure deployment diagnosis | Confirmed both Web Apps and ACR exist. Deployed frontend paths match the backend controller. Direct backend sessions returned 200; frontend proxy returned 502. Enabled Nginx error logging and captured TLS issuer verification error 20. | Inspect the certificate chain without disabling verification. |
| 2026-09-27 | Azure TLS fix and deployment | Existing CA bundle validated Azure's two-intermediate chain. Nginx depth 1 reproduced the exact 502; depth 2 returned 200. Added only the depth directive, built/pushed the image, and restarted frontend. Running and health 200 verified; final sessions curl failed to connect, so automation stopped. User subsequently confirmed the application works. | Complete the remaining Azure access, workflow, persistence, and operations checks. |

## Blockers and decisions

- Azure frontend 502 diagnosis is resolved: the existing CA bundle trusts the Azure chain, but Nginx's default verification depth of 1 was insufficient for its two intermediate certificates. Depth 2 passed a controlled Nginx test and was deployed without disabling certificate verification.
- The final automated Azure sessions check encountered curl exit 28 (connection failure to the frontend) and stopped before a browser chat test, as requested. The user subsequently confirmed the application is working; this does not substitute for all Phase 8 checks.
- Backend sessions returned 200 without an application key during diagnosis. Production key enforcement and frontend account restrictions still require verification; no authentication settings were changed during the proxy fix.
- Phases 1 through 4 have no outstanding blockers. Startup, logs, restart, and intentional reset instructions are documented in `implement.md`.
- Agreed: use the official MySQL Docker image locally and Azure Database for MySQL when moving to Azure.
- Agreed: frontend and backend deploy to separate Azure App Services.
- Proposed starting setup: share one Linux App Service Plan and restrict frontend sign-in to your account.
- Local image selected: official MySQL 8.4.11, pinned by digest in `compose.yaml`. Target Azure MySQL 8.4; confirm availability in the selected region/tier during Phase 5.
- Local MySQL uses `127.0.0.1:3307` and volume `springai-local_mysql-data`. Existing databases were left untouched.
- Backend image: `springai-backend:local`, published locally on `127.0.0.1:8080`. The container uses `mysql:3306` internally, not the host's port 3307.
- `.env.backend` stores the generated application key and non-secret deployment settings. The provider API key is supplied only at runtime from the launching terminal.
- Use `scripts/local.ps1 FrontendUp` for the complete Docker application. It loads the application key server-side and starts all dependencies. The older `Frontend` action remains available for Vite development; do not run it on the same port as Nginx.
- Frontend image: `springai-frontend:local`, published on `127.0.0.1:5173` and listening on port 8080 inside its container. The API upstream is `http://backend:8080` on the Compose network.
- Azure region, resource sizing, database configuration, identity-based image pulls, and costs remain to be recorded and verified against the existing deployment.
- Phase 4 preserved all original conversations. Two new verification chats remain; the temporary lifecycle chat was deleted through its UI confirmation. No database-volume deletion was performed.
