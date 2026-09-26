import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ChatApiError,
  MAX_MESSAGE_LENGTH,
  createSession,
  deleteSession,
  requestReply,
  getConversation,
  listConversations,
  renameConversation,
  type Conversation,
  type SavedTurn,
} from '../lib/chat-api';

export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
}
interface Failure {
  message: string;
  prompt: string;
  requestId: string;
  expired?: boolean;
}
interface ActiveRequest {
  controller: AbortController;
  prompt: string;
  requestId: string;
}
const CURRENT_CHAT_KEY = 'spring-ai-current-chat';

function remember(id: string | null) {
  try {
    if (id) sessionStorage.setItem(CURRENT_CHAT_KEY, id);
    else sessionStorage.removeItem(CURRENT_CHAT_KEY);
  } catch {
    /* In restricted browsers, history still works without automatic restoration. */
  }
}
function toMessages(turns: SavedTurn[]): Message[] {
  return turns.flatMap((turn) => [
    { id: `${turn.id}-user`, role: 'user' as const, content: turn.userMessage },
    { id: `${turn.id}-assistant`, role: 'assistant' as const, content: turn.assistantMessage },
  ]);
}
function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.';
}

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [history, setHistory] = useState<Conversation[]>([]);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [earlierCursor, setEarlierCursor] = useState<string | null>(null);
  const active = useRef<ActiveRequest | null>(null);
  const sessionId = useRef<string | null>(null);
  const navigation = useRef<AbortController | null>(null);
  const listing = useRef<AbortController | null>(null);

  const refreshHistory = useCallback(async (offset = 0) => {
    listing.current?.abort();
    const controller = new AbortController();
    listing.current = controller;
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      const page = await listConversations(controller.signal, offset);
      if (controller.signal.aborted) return;
      setHistory((previous) =>
        offset === 0
          ? page.items
          : [
              ...new Map(
                [...previous, ...page.items].map((item) => [item.sessionId, item]),
              ).values(),
            ],
      );
      setHasMoreHistory(page.hasMore);
    } catch (error) {
      if (!controller.signal.aborted) setHistoryError(errorMessage(error));
    } finally {
      if (!controller.signal.aborted) setHistoryLoading(false);
    }
  }, []);

  const openConversation = useCallback(async (id: string) => {
    active.current?.controller.abort();
    active.current = null;
    navigation.current?.abort();
    const controller = new AbortController();
    navigation.current = controller;
    sessionId.current = id;
    setSelectedId(id);
    remember(id);
    setLoading(true);
    setPending(false);
    setFailure(null);
    setLoadError(null);
    setMessages([]);
    setEarlierCursor(null);
    try {
      const page = await getConversation(id, controller.signal);
      if (controller.signal.aborted) return;
      setMessages(toMessages(page.turns));
      setEarlierCursor(page.nextBefore);
      setHistory((previous) =>
        previous.some((item) => item.sessionId === id)
          ? previous
          : [page.conversation, ...previous],
      );
    } catch (error) {
      if (!controller.signal.aborted) {
        setLoadError(errorMessage(error));
        if (error instanceof ChatApiError && error.status === 410) remember(null);
      }
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Initial synchronization with the server also exposes its loading state to the UI.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refreshHistory();
    try {
      const id = sessionStorage.getItem(CURRENT_CHAT_KEY);
      if (id) void openConversation(id);
    } catch {
      /* Restoration is optional; the sidebar remains available. */
    }
    return () => {
      active.current?.controller.abort();
      active.current = null;
      navigation.current?.abort();
      listing.current?.abort();
      // Navigating away must never delete durable history.
    };
  }, [refreshHistory, openConversation]);

  async function send(input: string, retry = false) {
    const prompt = input.trim();
    if (
      active.current ||
      loading ||
      loadError ||
      !prompt ||
      prompt.length > MAX_MESSAGE_LENGTH ||
      failure?.expired
    )
      return;
    const request = {
      controller: new AbortController(),
      prompt,
      requestId: retry && failure ? failure.requestId : crypto.randomUUID(),
    };
    active.current = request;
    setPending(true);
    setFailure(null);
    if (!retry)
      setMessages((previous) => [
        ...previous,
        { id: crypto.randomUUID(), role: 'user', content: prompt },
      ]);
    try {
      if (!sessionId.current) {
        const created = await createSession(request.controller.signal);
        if (active.current !== request) {
          void refreshHistory();
          return;
        }
        sessionId.current = created;
        setSelectedId(created);
        remember(created);
      }
      const reply = await requestReply(prompt, request.controller.signal, {
        sessionId: sessionId.current,
        requestId: request.requestId,
      });
      if (active.current === request) {
        setMessages((previous) => [
          ...previous,
          { id: crypto.randomUUID(), role: 'assistant', content: reply },
        ]);
        void refreshHistory();
      }
    } catch (error) {
      if (active.current === request && !request.controller.signal.aborted) {
        setFailure({
          message: errorMessage(error),
          prompt,
          requestId: request.requestId,
          expired: error instanceof ChatApiError && error.status === 410,
        });
        void refreshHistory();
      }
    } finally {
      if (active.current === request) {
        active.current = null;
        setPending(false);
      }
    }
  }

  function stop() {
    const request = active.current;
    request?.controller.abort();
    active.current = null;
    setPending(false);
    if (request)
      setFailure({
        message: 'Stopped waiting. The model may still finish; retry to retrieve the response.',
        prompt: request.prompt,
        requestId: request.requestId,
      });
  }
  function reset() {
    stop();
    navigation.current?.abort();
    sessionId.current = null;
    remember(null);
    setSelectedId(null);
    setMessages([]);
    setFailure(null);
    setLoading(false);
    setLoadError(null);
    setEarlierCursor(null);
    void refreshHistory();
  }
  async function removeConversation(id: string) {
    await deleteSession(id);
    if (sessionId.current === id) reset();
    await refreshHistory();
  }
  async function rename(id: string, title: string) {
    await renameConversation(id, title);
    await refreshHistory();
  }
  async function loadEarlier() {
    const id = sessionId.current;
    if (!id || !earlierCursor || loading || pending) return;
    const controller = new AbortController();
    navigation.current = controller;
    setLoading(true);
    try {
      const page = await getConversation(id, controller.signal, earlierCursor);
      if (!controller.signal.aborted && sessionId.current === id) {
        setMessages((previous) => [...toMessages(page.turns), ...previous]);
        setEarlierCursor(page.nextBefore);
      }
    } catch (error) {
      if (!controller.signal.aborted) setHistoryError(errorMessage(error));
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }
  return {
    messages,
    pending,
    failure,
    send,
    stop,
    reset,
    history,
    historyError,
    historyLoading,
    hasMoreHistory,
    refreshHistory,
    loading,
    loadError,
    selectedId,
    openConversation,
    removeConversation,
    rename,
    earlierCursor,
    loadEarlier,
  };
}
