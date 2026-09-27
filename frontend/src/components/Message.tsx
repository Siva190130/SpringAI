import { Check, Copy, Sprout } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { Message as ChatMessage } from '../hooks/use-chat';

export function Message({ message }: { message: ChatMessage }) {
  const [copyState, setCopyState] = useState('Copy response');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  async function copy() {
    try {
      await navigator.clipboard.writeText(message.content);
      setCopyState('Copied');
    } catch {
      setCopyState('Could not copy');
    }
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopyState('Copy response'), 2000);
  }

  if (message.role === 'user')
    return (
      <article className="user-message" aria-label="Your message">
        <p>{message.content}</p>
      </article>
    );
  return (
    <article className="assistant-message" aria-label="SHIVA_SMART_GPT response">
      <div className="message-heading">
        <span className="small-brand">
          <Sprout size={16} />
        </span>{' '}
        SHIVA_SMART_GPT
      </div>
      <div className="markdown">
        <Markdown
          remarkPlugins={[remarkGfm]}
          components={{
            a: ({ children, ...props }) => (
              <a {...props} target="_blank" rel="noopener noreferrer">
                {children}
              </a>
            ),
            // Model-generated image URLs must not initiate third-party tracking requests.
            img: ({ alt }) => <span>[Image: {alt || 'image'}]</span>,
          }}
        >
          {message.content}
        </Markdown>
      </div>
      <button className="copy-button" onClick={() => void copy()} aria-label={copyState}>
        {copyState === 'Copied' ? <Check size={14} /> : <Copy size={14} />}
        <span>{copyState}</span>
      </button>
    </article>
  );
}
