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

/** Deletion is explicit and must succeed before removing a conversation from the UI. */
export async function deleteSession(sessionId: string): Promise<void> {
  await requestJson(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}`,
    { method: 'DELETE' },
    AbortSignal.timeout(10000),
  );
}

export interface Conversation {
  sessionId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}
export interface SavedTurn {
  id: string;
  requestId: string;
  userMessage: string;
  assistantMessage: string;
}
export interface HistoryPage {
  conversation: Conversation;
  turns: SavedTurn[];
  hasMore: boolean;
  nextBefore: string | null;
}

function isConversation(data: unknown): data is Conversation {
  return (
    !!data &&
    typeof data === 'object' &&
    'sessionId' in data &&
    typeof data.sessionId === 'string' &&
    'title' in data &&
    typeof data.title === 'string' &&
    'createdAt' in data &&
    typeof data.createdAt === 'string' &&
    'updatedAt' in data &&
    typeof data.updatedAt === 'string'
  );
}

export async function listConversations(
  signal: AbortSignal,
  offset = 0,
): Promise<{ items: Conversation[]; hasMore: boolean }> {
  const data = await requestJson(`/api/chat/sessions?offset=${offset}`, { method: 'GET' }, signal);
  if (
    !data ||
    typeof data !== 'object' ||
    !('items' in data) ||
    !Array.isArray(data.items) ||
    !data.items.every(isConversation) ||
    !('hasMore' in data) ||
    typeof data.hasMore !== 'boolean'
  ) {
    throw new ChatApiError('The server returned invalid conversation history.');
  }
  return { items: data.items, hasMore: data.hasMore };
}

export async function getConversation(
  id: string,
  signal: AbortSignal,
  before?: string,
): Promise<HistoryPage> {
  const data = await requestJson(
    `/api/chat/sessions/${encodeURIComponent(id)}${before ? `?before=${encodeURIComponent(before)}` : ''}`,
    { method: 'GET' },
    signal,
  );
  if (
    !data ||
    typeof data !== 'object' ||
    !('conversation' in data) ||
    !isConversation(data.conversation) ||
    !('turns' in data) ||
    !Array.isArray(data.turns) ||
    !data.turns.every(
      (turn): turn is SavedTurn =>
        !!turn &&
        typeof turn === 'object' &&
        typeof turn.id === 'string' &&
        typeof turn.requestId === 'string' &&
        typeof turn.userMessage === 'string' &&
        typeof turn.assistantMessage === 'string',
    ) ||
    !('hasMore' in data) ||
    typeof data.hasMore !== 'boolean' ||
    !('nextBefore' in data) ||
    !(data.nextBefore === null || typeof data.nextBefore === 'string')
  ) {
    throw new ChatApiError('The server returned invalid conversation messages.');
  }
  return {
    conversation: data.conversation,
    turns: data.turns,
    hasMore: data.hasMore,
    nextBefore: data.nextBefore,
  };
}

export async function renameConversation(id: string, title: string): Promise<void> {
  await requestJson(
    `/api/chat/sessions/${encodeURIComponent(id)}`,
    { method: 'PATCH', body: JSON.stringify({ title }) },
    AbortSignal.timeout(10000),
  );
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
        410: 'This conversation was deleted. Start a new chat to continue.',
        429: 'Too many requests. Please wait a moment before trying again.',
        502: 'The model could not complete your request. Please try again.',
        503: 'The model is temporarily busy or unavailable. Please try again shortly.',
      };
      throw new ChatApiError(
        errors[response.status] || 'Something went wrong. Please try again.',
        response.status,
      );
    }
    return response.status === 204 ? null : await response.json();
  } catch (error) {
    if (signal.aborted) throw error;
    if (timeout.aborted) throw new ChatApiError('The response took too long. Please try again.');
    if (error instanceof ChatApiError) throw error;
    throw new ChatApiError(
      'Could not reach the model. Check your connection and that the server is running.',
    );
  }
}
