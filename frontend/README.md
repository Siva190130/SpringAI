# Spring AI frontend

React, TypeScript, Vite, and Tailwind CSS. Requires Node.js 22.12 or newer.

For the complete Docker setup, follow [Phase 3 in implement.md](../implement.md#phase-3--frontend-in-docker).
From the repository root, `powershell -NoProfile -File scripts/local.ps1 FrontendUp`
builds and starts Nginx, the backend, and MySQL. The provider API key must be available
in that terminal. Open `http://127.0.0.1:5173`; do not run Vite on that port simultaneously.
Host Node.js is only needed for the development workflow below, not the Docker workflow.

## Run locally

Start the Spring backend using the root README, then in a second terminal:

```powershell
cd frontend
npm ci
npm run dev
```

Open the local URL printed by Vite (normally http://127.0.0.1:5173).
The development proxy forwards `/api` to `http://127.0.0.1:8080`, so no backend
CORS changes are needed. Copy `.env.example` to `.env.local` to change the backend
address or supply its optional `CHAT_API_KEY`. Restart Vite after changing it.
The key is injected by the development server, never included in browser code.
Azure credentials belong only in the Spring backend environment.

## Structure and behavior

- `src/lib/chat-api.ts`: API contract, response validation, timeout, safe errors.
- `src/hooks/use-chat.ts`: history loading, refresh recovery, request lifecycle, cancellation, retry.
- `src/components/HistorySidebar.tsx`: saved conversations, rename, confirmed permanent deletion.
- `src/components/Message.tsx`: Markdown presentation and response copying.
- `src/App.tsx`: responsive layout, suggestions, theme, composer, scrolling.
- `src/styles.css`: theme tokens, layout, typography, Markdown, reduced motion.

The first message creates a session with `POST /api/chat/sessions`. Messages use
`POST /api/chat/stream` with `{ "message": "...", "sessionId": "...", "requestId": "..." }`
and receive newline-delimited JSON text updates followed by a completion acknowledgement.
The server supplies recent conversation context;
the browser does not resend or control earlier model messages. Manual retries reuse
the same request ID so a completed turn can be returned without another model call.

New chat clears the interface without deleting saved conversations. The sidebar loads
history from MySQL, supports pagination, reopening, renaming, and explicit permanent
deletion with confirmation. Failed deletion keeps the conversation visible. Messages
are loaded in pages with an option to load earlier turns. The current ID is kept in
`sessionStorage` so refreshing the tab restores it; message bodies are not stored in
browser storage. The theme preference uses `localStorage`.

The backend saves full completed turns, but only the latest 10 turns within a
64,000-character budget provide model context. Chats survive backend restarts and do
not expire. If a conversation was deleted elsewhere, the UI offers a new chat.

Enter sends; Shift+Enter inserts a newline. Composition input is respected.
Replies appear incrementally. Only the final `done` event confirms a complete saved reply.
Stop, network interruption, or a stream error discards partial text and offers a manual retry
with the same request ID. Stop cannot guarantee immediate cancellation of provider work;
a fully completed reply may already have been saved and will be retrieved on retry.
Retries are manual to avoid silently repeating paid provider calls.
Markdown does not execute raw HTML; remote images are suppressed to avoid
automatic third-party requests. Links open with opener isolation.

## Quality checks

```powershell
npm run lint
npm run format:check
npm run typecheck
npm test
npm run build
```

Tests mock transport; no live provider calls or credentials are needed.

## Production deployment

`npm run build` produces static files in `dist/`. Serve them over HTTPS with a
static web server. Route `/api/*` on the same origin to Spring through a trusted
gateway. Vite's development proxy is not part of the built application, and
`vite preview` is only for inspecting the build locally, not production hosting.

The existing Spring production profile requires an application key. Keep that
key server-side at the gateway; never put it in frontend source, a `VITE_*`
variable, local storage, or a publicly accessible configuration file. A gateway
that injects the key must itself restrict access (for example to a private
network or authenticated users); otherwise it exposes the paid API publicly.
This is a personal workspace with one shared history. User authentication and ownership
remain future work; do not expose the shared history publicly without access protection.
Keep identifiers private and avoid logging request bodies or session URLs. Configure gateway timeouts
to allow the provider response and security headers appropriate to your hosting.
Disable proxy response buffering for `/api/chat/stream` and permit requests lasting at least
90 seconds. The backend sends `X-Accel-Buffering: no`; confirm incremental delivery through
the actual gateway, since development proxy behavior does not verify production buffering.
