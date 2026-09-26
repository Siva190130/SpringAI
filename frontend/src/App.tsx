import {
  ArrowRight,
  ArrowUp,
  BookOpen,
  Code2,
  Lightbulb,
  Menu,
  Moon,
  Plus,
  RotateCcw,
  Sparkles,
  Sprout,
  Square,
  Sun,
  X,
} from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Message } from './components/Message';
import { HistorySidebar } from './components/HistorySidebar';
import { useChat } from './hooks/use-chat';
import { MAX_MESSAGE_LENGTH } from './lib/chat-api';

const suggestions = [
  {
    icon: Lightbulb,
    title: 'Find a fresh perspective',
    subtitle: 'Make room for your next big idea',
    prompt: 'Help me brainstorm five creative ideas for a useful weekend project.',
  },
  {
    icon: Code2,
    title: 'Build something great',
    subtitle: 'Work through code, one step at a time',
    prompt: 'Explain how to design a clean REST API in Spring Boot, with a practical example.',
  },
  {
    icon: BookOpen,
    title: 'Make the complex simple',
    subtitle: 'Understand something new today',
    prompt: 'Explain how large language models work using a simple everyday analogy.',
  },
  {
    icon: Sparkles,
    title: 'Find the right words',
    subtitle: 'Turn a rough thought into a clear story',
    prompt: 'Give me a simple framework for writing a clear, engaging project introduction.',
  },
];

function initialTheme() {
  try {
    return localStorage.getItem('spring-ai-theme') === 'dark';
  } catch {
    return false;
  }
}

export default function App() {
  const {
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
  } = useChat();
  const [draft, setDraft] = useState('');
  const [dark, setDark] = useState(initialTheme);
  const [menuOpen, setMenuOpen] = useState(false);
  const composer = useRef<HTMLTextAreaElement>(null);
  const scrollArea = useRef<HTMLDivElement>(null);
  const follow = useRef(true);

  useEffect(() => {
    if (!menuOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [menuOpen]);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    try {
      localStorage.setItem('spring-ai-theme', dark ? 'dark' : 'light');
    } catch {
      /* Storage is optional in restricted browsers. */
    }
  }, [dark]);

  useEffect(() => {
    if (composer.current) {
      composer.current.style.height = 'auto';
      composer.current.style.height = `${Math.min(composer.current.scrollHeight, 180)}px`;
    }
  }, [draft]);

  useEffect(() => {
    if (follow.current && scrollArea.current)
      scrollArea.current.scrollTop = scrollArea.current.scrollHeight;
  }, [messages, pending, failure]);

  function submit(event?: FormEvent) {
    event?.preventDefault();
    if (
      !draft.trim() ||
      draft.length > MAX_MESSAGE_LENGTH ||
      pending ||
      failure?.expired ||
      loading ||
      loadError
    )
      return;
    follow.current = true;
    void send(draft);
    setDraft('');
    composer.current?.focus();
  }

  function newChat() {
    reset();
    setDraft('');
    setMenuOpen(false);
    composer.current?.focus();
  }

  return (
    <div className="app-shell">
      {menuOpen && (
        <button
          className="sidebar-backdrop"
          aria-label="Close navigation"
          onClick={() => setMenuOpen(false)}
        />
      )}
      <aside className={`sidebar ${menuOpen ? 'is-open' : ''}`} aria-label="Navigation">
        <a className="brand" href="./" aria-label="Spring AI home">
          <span className="brand-icon">
            <Sprout size={25} />
          </span>
          <span>
            spring<span className="brand-ai">ai</span>
            <small>A SPACE FOR YOUR IDEAS</small>
          </span>
        </a>
        <button className="new-chat" onClick={newChat}>
          <Plus size={18} /> New chat <span>↗</span>
        </button>
        <HistorySidebar
          items={history}
          selectedId={selectedId}
          loading={historyLoading}
          error={historyError}
          hasMore={hasMoreHistory}
          onRefresh={() => void refreshHistory()}
          onMore={() => void refreshHistory(history.length)}
          onOpen={(id) => {
            setDraft('');
            setMenuOpen(false);
            follow.current = true;
            void openConversation(id);
          }}
          onRename={rename}
          onDelete={removeConversation}
        />
        <div className="sidebar-bottom">
          <span className="version-tag">EARLY EDITION</span>
          <p>
            Follow-up questions welcome.
            <br />
            Saved history. Your personal workspace.
          </p>
          <button className="theme-button" onClick={() => setDark(!dark)}>
            {dark ? <Sun size={17} /> : <Moon size={17} />}
            {dark ? 'Light appearance' : 'Dark appearance'}
            <span className="theme-indicator" />
          </button>
        </div>
        <button
          className="close-menu icon-button"
          aria-label="Close navigation"
          onClick={() => setMenuOpen(false)}
        >
          <X size={19} />
        </button>
      </aside>

      <main className="main-panel">
        <header className="topbar">
          <div className="flex items-center gap-3">
            <button
              className="mobile-menu icon-button"
              onClick={() => setMenuOpen(true)}
              aria-label="Open navigation"
              aria-expanded={menuOpen}
            >
              <Menu size={20} />
            </button>
            <span className="topbar-title">
              {history.find((item) => item.sessionId === selectedId)?.title ||
                'Your thinking companion'}
            </span>
          </div>
          <span className="model-badge">
            <span className="status-dot" /> Spring AI
          </span>
        </header>
        <div
          className={`conversation-scroll ${messages.length ? 'has-messages' : ''}`}
          ref={scrollArea}
          onScroll={() => {
            const el = scrollArea.current;
            if (el) follow.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
          }}
        >
          {loadError ? (
            <div className="error-card" role="alert">
              <p>{loadError}</p>
              <button onClick={() => selectedId && void openConversation(selectedId)}>
                Retry loading
              </button>
              <button onClick={newChat}>Start a new chat</button>
            </div>
          ) : loading && messages.length === 0 ? (
            <p role="status" className="history-empty">
              Loading conversation…
            </p>
          ) : messages.length === 0 ? (
            <section className="welcome">
              <div className="welcome-emblem">
                <Sprout size={38} strokeWidth={1.4} />
              </div>
              <div className="eyebrow">A LITTLE CURIOSITY GOES A LONG WAY</div>
              <h1>What’s on your mind?</h1>
              <p className="welcome-description">
                Explore an idea, untangle a problem, or create something new.
                <br className="desktop-break" /> A thoughtful conversation starts right here.
              </p>
              <div className="suggestions">
                {suggestions.map(({ icon: Icon, title, subtitle, prompt }) => (
                  <button
                    className="suggestion"
                    key={title}
                    onClick={() => {
                      setDraft(prompt);
                      composer.current?.focus();
                    }}
                  >
                    <Icon size={21} strokeWidth={1.6} />
                    <h2>{title}</h2>
                    <p>{subtitle}</p>
                    <ArrowRight className="suggestion-arrow" size={16} />
                  </button>
                ))}
              </div>
              <div className="welcome-footnote">
                <span /> BIG IDEAS. SMALL BEGINNINGS. <span />
              </div>
            </section>
          ) : (
            <section className="messages" aria-label="Conversation">
              {earlierCursor && (
                <button
                  className="load-more"
                  disabled={loading || pending}
                  onClick={() => {
                    follow.current = false;
                    void loadEarlier();
                  }}
                >
                  {loading ? 'Loading…' : 'Load earlier messages'}
                </button>
              )}
              {messages.map((message) => (
                <Message key={message.id} message={message} />
              ))}
              {pending && (
                <div className="thinking" role="status">
                  <Sprout size={19} />
                  <span>Thinking it through</span>
                  <span className="thinking-dots">•••</span>
                </div>
              )}
              {failure && (
                <div className="error-card" role="alert">
                  <p>{failure.message}</p>
                  <button
                    onClick={() => (failure.expired ? newChat() : void send(failure.prompt, true))}
                  >
                    <RotateCcw size={14} /> {failure.expired ? 'Start a new chat' : 'Try again'}
                  </button>
                </div>
              )}
            </section>
          )}
        </div>
        <div className="composer-area">
          <form
            className={`composer ${draft.length > MAX_MESSAGE_LENGTH ? 'invalid' : ''}`}
            onSubmit={submit}
          >
            <label className="sr-only" htmlFor="message">
              Message Spring AI
            </label>
            <textarea
              id="message"
              ref={composer}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder="Ask anything, or start with an idea…"
              rows={1}
              aria-describedby="composer-help"
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  submit();
                }
              }}
            />
            <div className="composer-toolbar">
              <span className="composer-label">
                <Sparkles size={14} /> A little help, a lot of possibility
              </span>
              <div className="flex items-center gap-3">
                {draft.length > 14000 && (
                  <span className="character-count">{draft.length.toLocaleString()} / 16,000</span>
                )}
                {pending ? (
                  <button
                    className="send-button"
                    type="button"
                    onClick={stop}
                    aria-label="Stop waiting for response"
                  >
                    <Square size={16} fill="currentColor" />
                  </button>
                ) : (
                  <button
                    className="send-button"
                    type="submit"
                    disabled={
                      !draft.trim() ||
                      draft.length > MAX_MESSAGE_LENGTH ||
                      failure?.expired ||
                      loading ||
                      !!loadError
                    }
                    aria-label="Send message"
                  >
                    <ArrowUp size={20} />
                  </button>
                )}
              </div>
            </div>
          </form>
          <p id="composer-help" className="composer-help">
            {draft.length > MAX_MESSAGE_LENGTH
              ? 'Please shorten your message to 16,000 characters.'
              : 'Conversations are saved. Recent messages provide context. Double-check important details.'}
          </p>
        </div>
      </main>
    </div>
  );
}
