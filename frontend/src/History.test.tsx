import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { streamResponse } from './test/stream-response';

const firstId = '11111111-1111-4111-8111-111111111111';
const secondId = '22222222-2222-4222-8222-222222222222';
const date = '2026-01-01T00:00:00Z';
const first = { sessionId: firstId, title: 'Learning Java', createdAt: date, updatedAt: date };
const second = { sessionId: secondId, title: 'Weekend plans', createdAt: date, updatedAt: date };
const page = (conversation = first) => ({
  conversation,
  turns: [
    {
      id: '1',
      requestId: firstId,
      userMessage: 'Remember my name',
      assistantMessage: 'Hello Siva',
    },
  ],
  hasMore: false,
  nextBefore: null,
});

beforeEach(() => {
  sessionStorage.clear();
  // jsdom does not implement native dialog methods; browser focus behavior is checked separately.
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open');
  };
});
afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe('saved history', () => {
  it('restores the current conversation after refresh and continues using its ID', async () => {
    sessionStorage.setItem('spring-ai-current-chat', firstId);
    const fetch = vi.fn(async (path: string) => {
      if (path.includes('?offset=')) return Response.json({ items: [first], hasMore: false });
      if (path === '/api/chat/stream') return streamResponse('Your name is Siva');
      return Response.json(page());
    });
    vi.stubGlobal('fetch', fetch);
    render(<App />);
    expect(await screen.findByText('Hello Siva')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox', { name: 'Message SHIVA_SMART_GPT' }), 'What is my name?');
    await user.click(screen.getByLabelText('Send message'));
    expect(await screen.findByText('Your name is Siva')).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(
      '/api/chat/stream',
      expect.objectContaining({ body: expect.stringContaining(firstId) }),
    );
    await user.click(screen.getByRole('button', { name: /New chat/ }));
    expect(sessionStorage.getItem('spring-ai-current-chat')).toBeNull();
    expect(fetch.mock.calls.some(([path]) => path === '/api/chat/sessions')).toBe(false);
    expect(screen.getByRole('button', { name: 'Learning Java' })).toBeInTheDocument();
  });

  it('renames a conversation and requires explicit confirmation before permanent deletion', async () => {
    let title = first.title;
    let deleted = false;
    let failDelete = true;
    const fetch = vi.fn(async (path: string, init: RequestInit) => {
      if (path.includes('?offset='))
        return Response.json({ items: deleted ? [] : [{ ...first, title }], hasMore: false });
      if (init.method === 'PATCH') {
        title = JSON.parse(init.body as string).title;
        return Response.json({ ...first, title });
      }
      if (init.method === 'DELETE') {
        if (failDelete) return new Response('', { status: 500 });
        deleted = true;
        return new Response(null, { status: 204 });
      }
      return Response.json(page());
    });
    vi.stubGlobal('fetch', fetch);
    render(<App />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Rename Learning Java' }));
    await user.clear(screen.getByLabelText('Conversation title'));
    await user.type(screen.getByLabelText('Conversation title'), 'Java notes');
    await user.click(screen.getByRole('button', { name: 'Save title' }));
    await user.click(await screen.findByRole('button', { name: 'Delete Java notes' }));
    expect(deleted).toBe(false);
    await user.click(screen.getByRole('button', { name: 'Delete permanently' }));
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Java notes' })).toBeInTheDocument();
    failDelete = false;
    await user.click(screen.getByRole('button', { name: 'Delete permanently' }));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Java notes' })).not.toBeInTheDocument(),
    );
    expect(deleted).toBe(true);
  });

  it('ignores stale history responses when switching conversations quickly', async () => {
    let resolveFirst!: (response: Response) => void;
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string) => {
        if (path.includes('?offset='))
          return Promise.resolve(Response.json({ items: [first, second], hasMore: false }));
        if (path.endsWith(firstId))
          return new Promise<Response>((resolve) => {
            resolveFirst = resolve;
          });
        return Promise.resolve(
          Response.json({
            ...page(second),
            turns: [
              {
                id: '2',
                requestId: secondId,
                userMessage: 'Plan a trip',
                assistantMessage: 'Visit the mountains',
              },
            ],
          }),
        );
      }),
    );
    render(<App />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Learning Java' }));
    await user.click(screen.getByRole('button', { name: 'Weekend plans' }));
    expect(await screen.findByText('Visit the mountains')).toBeInTheDocument();
    await act(async () => resolveFirst(Response.json(page())));
    expect(screen.queryByText('Hello Siva')).not.toBeInTheDocument();
    expect(sessionStorage.getItem('spring-ai-current-chat')).toBe(secondId);
  });
});
