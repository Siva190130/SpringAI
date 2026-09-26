import { useEffect, useRef, useState } from 'react';
import { MAX_MESSAGE_LENGTH, requestReply } from '../lib/chat-api';

export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
}
interface Failure {
  message: string;
  prompt: string;
}

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const active = useRef<AbortController | null>(null);

  useEffect(() => () => active.current?.abort(), []);

  async function send(input: string, retry = false) {
    const prompt = input.trim();
    if (active.current || !prompt || prompt.length > MAX_MESSAGE_LENGTH) return;
    const controller = new AbortController();
    active.current = controller;
    setPending(true);
    setFailure(null);
    if (!retry)
      setMessages((previous) => [
        ...previous,
        { id: crypto.randomUUID(), role: 'user', content: prompt },
      ]);
    try {
      const reply = await requestReply(prompt, controller.signal);
      // A cleared conversation must never receive a late response from an older request.
      if (active.current === controller) {
        setMessages((previous) => [
          ...previous,
          { id: crypto.randomUUID(), role: 'assistant', content: reply },
        ]);
      }
    } catch (error) {
      if (active.current === controller && !controller.signal.aborted) {
        setFailure({
          message: error instanceof Error ? error.message : 'Unable to get a response.',
          prompt,
        });
      }
    } finally {
      if (active.current === controller) {
        active.current = null;
        setPending(false);
      }
    }
  }

  function stop() {
    active.current?.abort();
    active.current = null;
    setPending(false);
  }

  function reset() {
    stop();
    setMessages([]);
    setFailure(null);
  }

  return { messages, pending, failure, send, stop, reset };
}
