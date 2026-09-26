import { Pencil, Trash2, RefreshCw, MessageSquare } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { Conversation } from '../lib/chat-api';

interface Props {
  items: Conversation[];
  selectedId: string | null;
  loading: boolean;
  error: string | null;
  hasMore: boolean;
  onOpen: (id: string) => void;
  onRefresh: () => void;
  onMore: () => void;
  onRename: (id: string, title: string) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}

export function HistorySidebar(props: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [action, setAction] = useState<{
    type: 'rename' | 'delete';
    conversation: Conversation;
  } | null>(null);
  const [title, setTitle] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (action) dialog.current?.showModal();
  }, [action]);

  function show(type: 'rename' | 'delete', conversation: Conversation) {
    setAction({ type, conversation });
    setTitle(conversation.title);
    setError(null);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!action || saving) return;
    setSaving(true);
    setError(null);
    try {
      if (action.type === 'delete') await props.onDelete(action.conversation.sessionId);
      else await props.onRename(action.conversation.sessionId, title.trim());
      dialog.current?.close();
    } catch (error) {
      setError(
        error instanceof Error ? error.message : 'Could not save the change. Please try again.',
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <div className="history-heading">
        <span>YOUR CONVERSATIONS</span>
        <button
          className="icon-button"
          onClick={props.onRefresh}
          disabled={props.loading}
          aria-label="Refresh history"
        >
          <RefreshCw size={14} />
        </button>
      </div>
      <nav className="history-list" aria-label="Saved conversations">
        {props.error && (
          <p className="history-error" role="alert">
            {props.error}
          </p>
        )}
        {!props.items.length && !props.loading && !props.error && (
          <p className="history-empty">
            Your conversations will appear here after you start chatting.
          </p>
        )}
        {props.items.map((item) => (
          <div
            className={`history-row ${props.selectedId === item.sessionId ? 'selected' : ''}`}
            key={item.sessionId}
          >
            <button
              className="history-open"
              onClick={() => props.onOpen(item.sessionId)}
              aria-current={props.selectedId === item.sessionId ? 'page' : undefined}
              title={item.title}
            >
              <MessageSquare size={14} />
              <span>{item.title}</span>
            </button>
            <button
              className="history-action"
              aria-label={`Rename ${item.title}`}
              onClick={() => show('rename', item)}
            >
              <Pencil size={13} />
            </button>
            <button
              className="history-action"
              aria-label={`Delete ${item.title}`}
              onClick={() => show('delete', item)}
            >
              <Trash2 size={13} />
            </button>
          </div>
        ))}
        {props.loading && (
          <p className="history-empty" role="status">
            Loading history…
          </p>
        )}
        {props.hasMore && (
          <button className="load-more" onClick={props.onMore} disabled={props.loading}>
            Load more conversations
          </button>
        )}
      </nav>
      <dialog
        className="history-dialog"
        ref={dialog}
        onCancel={(event) => {
          if (saving) event.preventDefault();
        }}
        aria-labelledby="history-dialog-title"
      >
        <form onSubmit={(event) => void submit(event)}>
          <h2 id="history-dialog-title">
            {action?.type === 'delete' ? 'Delete conversation?' : 'Rename conversation'}
          </h2>
          {action?.type === 'delete' ? (
            <p>
              “{action.conversation.title}” and all its messages will be permanently deleted. This
              cannot be undone.
            </p>
          ) : (
            <>
              <label htmlFor="conversation-title">Conversation title</label>
              <input
                id="conversation-title"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                maxLength={120}
                required
                autoComplete="off"
              />
            </>
          )}
          {error && (
            <p role="alert" className="history-error">
              {error}
            </p>
          )}
          <div className="dialog-actions">
            <button type="button" disabled={saving} onClick={() => dialog.current?.close()}>
              Cancel
            </button>
            <button
              className={action?.type === 'delete' ? 'danger-button' : 'confirm-button'}
              type="submit"
              disabled={saving || (action?.type === 'rename' && !title.trim())}
            >
              {saving ? 'Saving…' : action?.type === 'delete' ? 'Delete permanently' : 'Save title'}
            </button>
          </div>
        </form>
      </dialog>
    </>
  );
}
