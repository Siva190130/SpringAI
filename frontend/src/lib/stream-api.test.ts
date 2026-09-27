import { afterEach, expect, it, vi } from 'vitest';
import { streamReply } from './chat-api';

afterEach(() => vi.unstubAllGlobals());
const session = { sessionId: 'session', requestId: 'request' };
const encoder = new TextEncoder();
function openStream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const cancel = vi.fn();
  const body = new ReadableStream<Uint8Array>({
    start(value) {
      controller = value;
    },
    cancel,
  });
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(body, {
        headers: { 'Content-Type': 'application/x-ndjson' },
      }),
    ),
  );
  return { controller, cancel };
}

it('renders deltas before completion and decodes fragmented UTF-8 and JSON records', async () => {
  const { controller } = openStream();
  const delta = vi.fn();
  const result = streamReply('Hi', new AbortController().signal, session, delta);
  const bytes = encoder.encode(JSON.stringify({ type: 'delta', text: 'Hello 🌱\n' }) + '\n');
  // Split every byte, including inside the four-byte emoji.
  for (const byte of bytes) controller.enqueue(new Uint8Array([byte]));
  await vi.waitFor(() => expect(delta).toHaveBeenCalledWith('Hello 🌱\n'));
  controller.enqueue(encoder.encode(JSON.stringify({ type: 'done', reply: 'Hello 🌱\n' }) + '\n'));
  await expect(result).resolves.toBe('Hello 🌱\n');
});

it('rejects an interrupted stream even when partial text arrived', async () => {
  const { controller } = openStream();
  const delta = vi.fn();
  const result = streamReply('Hi', new AbortController().signal, session, delta);
  controller.enqueue(encoder.encode('{"type":"delta","text":"Partial"}\n'));
  controller.close();
  await expect(result).rejects.toThrow('interrupted');
  expect(delta).toHaveBeenCalledWith('Partial');
});

it('sanitizes in-stream failures and cancels the response reader', async () => {
  const { controller, cancel } = openStream();
  const result = streamReply('Hi', new AbortController().signal, session, vi.fn());
  controller.enqueue(encoder.encode('{"type":"error","status":410,"detail":"secret"}\n'));
  await expect(result).rejects.toMatchObject({
    status: 410,
    message: expect.not.stringContaining('secret'),
  });
  expect(cancel).toHaveBeenCalled();
});
