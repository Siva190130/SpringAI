# Spring AI frontend

React, TypeScript, Vite, and Tailwind CSS. Requires Node.js 22.12 or newer.

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
- `src/hooks/use-chat.ts`: in-memory messages, request lifecycle, cancellation, retry.
- `src/components/Message.tsx`: Markdown presentation and response copying.
- `src/App.tsx`: responsive layout, suggestions, theme, composer, scrolling.
- `src/styles.css`: theme tokens, layout, typography, Markdown, reduced motion.

The first message creates a session with `POST /api/chat/sessions`. Messages use
`POST /api/chat` with `{ "message": "...", "sessionId": "...", "requestId": "..." }`
and receive `{ "reply": "..." }`. The server supplies recent conversation context;
the browser does not resend or control earlier model messages. Manual retries reuse
the same request ID so a completed turn can be returned without another model call.

New chat clears the interface and requests deletion of the previous session.
Sessions and messages are held in memory, not local storage. Reloading starts fresh;
server sessions expire after 30 idle minutes or a backend restart. Expired sessions
offer an explicit New chat action. Memory retains up to 10 completed turns within
a 64,000-character budget, so older visible messages can fall outside model context.
Only the appearance preference is stored locally.

Enter sends; Shift+Enter inserts a newline. Composition input is respected.
Replies arrive in full because the backend does not stream. Stop cancels browser
waiting; it cannot guarantee cancellation of a model call already running on the
server. Retries are manual to avoid silently repeating paid provider calls.
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
User authentication, sessions shared across backend instances, saved history, and production gateway
configuration remain future work. Session IDs act as temporary bearer capabilities;
keep them private and avoid logging request bodies or session URLs. Configure gateway timeouts
to allow the provider response and security headers appropriate to your hosting.
