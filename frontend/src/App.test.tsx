import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => vi.unstubAllGlobals());

describe('chat experience', () => {
  it('sends the API contract and renders Markdown without executing HTML', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ reply: '**Hello** <script>alert(1)</script>' })),
      );
    vi.stubGlobal('fetch', fetch);
    render(<App />);
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox'), 'Hello model');
    await user.click(screen.getByRole('button', { name: 'Send message' }));
    expect(await screen.findByText('Hello', { selector: 'strong' })).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(
      '/api/chat',
      expect.objectContaining({ method: 'POST', body: '{"message":"Hello model"}' }),
    );
    expect(document.querySelector('script')).toBeNull();
  });

  it('retries a failed request without duplicating the user message', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response('', { status: 503 }))
        .mockResolvedValueOnce(new Response('{"reply":"Recovered"}')),
    );
    render(<App />);
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox'), 'Try this');
    await user.click(screen.getByLabelText('Send message'));
    await user.click(await screen.findByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Recovered')).toBeInTheDocument();
    expect(screen.getAllByLabelText('Your message')).toHaveLength(1);
  });

  it('discards late replies after starting a new conversation', async () => {
    let resolve!: (value: Response) => void;
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((done) => {
            resolve = done;
          }),
      ),
    );
    render(<App />);
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox'), 'Old request');
    await user.click(screen.getByLabelText('Send message'));
    await user.click(screen.getByRole('button', { name: /New chat/ }));
    resolve(new Response('{"reply":"Stale reply"}'));
    await waitFor(() => expect(screen.getByText('What’s on your mind?')).toBeInTheDocument());
    expect(screen.queryByText('Stale reply')).not.toBeInTheDocument();
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
