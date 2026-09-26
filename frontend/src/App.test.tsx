import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => vi.unstubAllGlobals());

const sessionId = '11111111-1111-4111-8111-111111111111';

function mockApi(reply: (init: RequestInit) => Promise<Response>) {
  const fetch = vi.fn((path: string, init: RequestInit) => {
    if (path === '/api/chat/sessions') return Promise.resolve(Response.json({ sessionId }));
    if (init.method === 'DELETE') return Promise.resolve(new Response(null, { status: 204 }));
    return reply(init);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

describe('chat experience', () => {
  it('sends the API contract and renders Markdown without executing HTML', async () => {
    const fetch = mockApi(async () =>
      Response.json({ reply: '**Hello** <script>alert(1)</script>' }),
    );
    render(<App />);
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox'), 'Hello model');
    await user.click(screen.getByRole('button', { name: 'Send message' }));
    expect(await screen.findByText('Hello', { selector: 'strong' })).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(
      '/api/chat',
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"sessionId":"' + sessionId + '"'),
      }),
    );
    expect(document.querySelector('script')).toBeNull();
  });

  it('retries a failed request without duplicating the user message', async () => {
    const reply = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 503 }))
      .mockResolvedValueOnce(new Response('{"reply":"Recovered"}'));
    mockApi(reply);
    render(<App />);
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox'), 'Try this');
    await user.click(screen.getByLabelText('Send message'));
    await user.click(await screen.findByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Recovered')).toBeInTheDocument();
    expect(screen.getAllByLabelText('Your message')).toHaveLength(1);
    expect(reply.mock.calls[0][0].body).toBe(reply.mock.calls[1][0].body);
  });

  it('discards late replies after starting a new conversation', async () => {
    let resolve!: (value: Response) => void;
    const fetch = mockApi(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    render(<App />);
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox'), 'Old request');
    await user.click(screen.getByLabelText('Send message'));
    await user.click(screen.getByRole('button', { name: /New chat/ }));
    resolve(new Response('{"reply":"Stale reply"}'));
    await waitFor(() => expect(screen.getByText('What’s on your mind?')).toBeInTheDocument());
    expect(screen.queryByText('Stale reply')).not.toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(
      '/api/chat/sessions/' + sessionId,
      expect.objectContaining({ method: 'DELETE' }),
    );
  });

  it('reuses the session for follow-ups and creates another after New chat', async () => {
    const fetch = mockApi(async () => Response.json({ reply: 'Answer' }));
    render(<App />);
    const user = userEvent.setup();
    for (const message of ['My name is Siva', 'What is my name?']) {
      await user.type(screen.getByRole('textbox'), message);
      await user.click(screen.getByLabelText('Send message'));
      await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
    }
    expect(fetch.mock.calls.filter(([path]) => path === '/api/chat/sessions')).toHaveLength(1);
    const turns = fetch.mock.calls
      .filter(([path]) => path === '/api/chat')
      .map(([, init]) => JSON.parse(init.body as string));
    expect(turns.map((turn) => turn.sessionId)).toEqual([sessionId, sessionId]);
    expect(turns[0].requestId).not.toBe(turns[1].requestId);
    await user.click(screen.getByRole('button', { name: /New chat/ }));
    await user.type(screen.getByRole('textbox'), 'Fresh start');
    await user.click(screen.getByLabelText('Send message'));
    await screen.findByText('Answer');
    expect(fetch.mock.calls.filter(([path]) => path === '/api/chat/sessions')).toHaveLength(2);
  });

  it('offers a new chat when memory expires instead of silently dropping context', async () => {
    mockApi(async () => new Response('', { status: 410 }));
    render(<App />);
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox'), 'Remember me?');
    await user.click(screen.getByLabelText('Send message'));
    expect(await screen.findByRole('button', { name: 'Start a new chat' })).toBeInTheDocument();
    await user.type(screen.getByRole('textbox'), 'Another question');
    expect(screen.getByLabelText('Send message')).toBeDisabled();
  });

  it('blocks blank and oversized prompts and lets suggestion cards fill the composer', async () => {
    render(<App />);
    expect(screen.getByLabelText('Send message')).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: /Build something great/ }));
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toContain('Spring Boot');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'x'.repeat(16001) } });
    expect(screen.getByLabelText('Send message')).toBeDisabled();
    expect(
      screen.getByText('Please shorten your message to 16,000 characters.'),
    ).toBeInTheDocument();
  });
});
