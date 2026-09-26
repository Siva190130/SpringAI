export const MAX_MESSAGE_LENGTH = 16_000;
const REQUEST_TIMEOUT_MS = 90_000;

export class ChatApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

export interface SessionRequest {
  sessionId: string;
  requestId: string;
}

export async function createSession(signal: AbortSignal): Promise<string> {
  const data = await requestJson('/api/chat/sessions', { method: 'POST' }, signal);
  if (
    !data ||
    typeof data !== 'object' ||
    !('sessionId' in data) ||
    typeof data.sessionId !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(data.sessionId)
  ) {
    throw new ChatApiError('Could not start a conversation. Please try again.');
  }
  return data.sessionId;
}

/** Best-effort cleanup; server expiry also covers closed tabs and lost connections. */
export async function deleteSession(sessionId: string): Promise<void> {
  try {
    await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
      signal: AbortSignal.timeout(5000),
      keepalive: true,
    });
  } catch {
    /* Expiry reclaims sessions when cleanup cannot reach the server. */
  }
}

/** Keep transport concerns outside React and never render raw upstream error bodies. */
export async function requestReply(
  message: string,
  signal: AbortSignal,
  session?: SessionRequest,
): Promise<string> {
  const data = await requestJson(
    '/api/chat',
    {
      method: 'POST',
      body: JSON.stringify({ message, ...session }),
    },
    signal,
  );
  if (
    !data ||
    typeof data !== 'object' ||
    !('reply' in data) ||
    typeof data.reply !== 'string' ||
    !data.reply.trim()
  ) {
    throw new ChatApiError('The model returned an empty or invalid response. Please try again.');
  }
  return data.reply;
}

async function requestJson(path: string, init: RequestInit, signal: AbortSignal): Promise<unknown> {
  const timeout = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      signal: AbortSignal.any([signal, timeout]),
    });
    if (!response.ok) {
      const errors: Record<number, string> = {
        400: 'Please send a message of up to 16,000 characters.',
        401: 'Access could not be verified. Check the server access configuration.',
        409: 'A response is still in progress. Please wait a moment and try again.',
        410: 'This conversation has expired. Start a new chat to continue.',
        429: 'Too many requests. Please wait a moment before trying again.',
        502: 'The model could not complete your request. Please try again.',
        503: 'The model is temporarily busy or unavailable. Please try again shortly.',
      };
      throw new ChatApiError(
        errors[response.status] || 'Something went wrong. Please try again.',
        response.status,
      );
    }
    return await response.json();
  } catch (error) {
    if (signal.aborted) throw error;
    if (timeout.aborted) throw new ChatApiError('The response took too long. Please try again.');
    if (error instanceof ChatApiError) throw error;
    throw new ChatApiError(
      'Could not reach the model. Check your connection and that the server is running.',
    );
  }
}
