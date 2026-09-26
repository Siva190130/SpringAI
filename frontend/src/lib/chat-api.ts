export const MAX_MESSAGE_LENGTH = 16_000;
const REQUEST_TIMEOUT_MS = 90_000;

export class ChatApiError extends Error {}

/** Keep transport concerns outside React and never render raw upstream error bodies. */
export async function requestReply(message: string, signal: AbortSignal): Promise<string> {
  const timeout = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ message }),
      signal: AbortSignal.any([signal, timeout]),
    });
    if (!response.ok) {
      const errors: Record<number, string> = {
        400: 'Please send a message of up to 16,000 characters.',
        401: 'Access could not be verified. Check the server access configuration.',
        429: 'Too many requests. Please wait a moment before trying again.',
        502: 'The model could not complete your request. Please try again.',
        503: 'The model is temporarily busy or unavailable. Please try again shortly.',
      };
      throw new ChatApiError(errors[response.status] || 'Something went wrong. Please try again.');
    }
    const data: unknown = await response.json();
    if (
      !data ||
      typeof data !== 'object' ||
      !('reply' in data) ||
      typeof data.reply !== 'string' ||
      !data.reply.trim()
    ) {
      throw new ChatApiError('The model returned an empty or invalid response. Please try again.');
    }
    return data.reply;
  } catch (error) {
    if (signal.aborted) throw error;
    if (timeout.aborted) throw new ChatApiError('The response took too long. Please try again.');
    if (error instanceof ChatApiError) throw error;
    throw new ChatApiError(
      'Could not reach the model. Check your connection and that the server is running.',
    );
  }
}
