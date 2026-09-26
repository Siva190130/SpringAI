import { useEffect, useRef, useState } from 'react';
import {
  ChatApiError,
  MAX_MESSAGE_LENGTH,
  createSession,
  deleteSession,
  requestReply,
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

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const active = useRef<ActiveRequest | null>(null);
  const sessionId = useRef<string | null>(null);

  useEffect(
    () => () => {
      active.current?.controller.abort();
      active.current = null;
      if (sessionId.current) void deleteSession(sessionId.current);
      sessionId.current = null;
    },
    [],
  );

  async function send(input: string, retry = false) {
    const prompt = input.trim();
    if (active.current || !prompt || prompt.length > MAX_MESSAGE_LENGTH || failure?.expired) return;
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
          void deleteSession(created);
          return;
        }
        sessionId.current = created;
      }
      const reply = await requestReply(prompt, request.controller.signal, {
        sessionId: sessionId.current,
        requestId: request.requestId,
      });
      // Reset and cancellation invalidate this request even if its response arrives late.
      if (active.current === request) {
        setMessages((previous) => [
          ...previous,
          { id: crypto.randomUUID(), role: 'assistant', content: reply },
        ]);
      }
    } catch (error) {
      if (active.current === request && !request.controller.signal.aborted) {
        setFailure({
          message: error instanceof Error ? error.message : 'Unable to get a response.',
          prompt,
          requestId: request.requestId,
          expired: error instanceof ChatApiError && error.status === 410,
        });
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
    if (sessionId.current) void deleteSession(sessionId.current);
    sessionId.current = null;
    setMessages([]);
    setFailure(null);
  }

  return { messages, pending, failure, send, stop, reset };
}
