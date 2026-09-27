export function streamResponse(reply: string): Response {
  return new Response(
    JSON.stringify({ type: 'delta', text: reply }) +
      '\n' +
      JSON.stringify({ type: 'done', reply }) +
      '\n',
    { headers: { 'Content-Type': 'application/x-ndjson' } },
  );
}
