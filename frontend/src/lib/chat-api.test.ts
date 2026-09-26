import { afterEach, describe, expect, it, vi } from 'vitest';
import { requestReply } from './chat-api';

afterEach(() => vi.unstubAllGlobals());

describe('chat transport', () => {
  it.each([{}, { reply: null }, { reply: '   ' }])(
    'rejects invalid reply contracts: %j',
    async (body) => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body))));
      await expect(requestReply('Hello', new AbortController().signal)).rejects.toThrow(
        'empty or invalid',
      );
    },
  );

  it('never exposes upstream error content', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('sensitive upstream diagnostic', { status: 500 })),
    );
    await expect(requestReply('Hello', new AbortController().signal)).rejects.toThrow(
      'Something went wrong. Please try again.',
    );
  });

  it('explains network failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(requestReply('Hello', new AbortController().signal)).rejects.toThrow(
      'Could not reach the model',
    );
  });
});
