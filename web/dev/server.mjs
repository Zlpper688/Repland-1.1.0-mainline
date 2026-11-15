// Local Function runtime: real routes, in-memory database, no cloud credentials.
// Vite proxies /functions/v1/app here. Restarting the process resets local data.
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { createFakeDatabase } from './fake-db.mjs';
import { application } from '../functions/routes.mjs';

// Local-only credentials: .env.local is gitignored and never bundled.
try {
  const text = readFileSync(new URL('../.env.local', import.meta.url), 'utf8');
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$/);
    if (!match || process.env[match[1]]) continue;
    process.env[match[1]] = match[2].trim().replace(/^["']|["']$/g, '');
  }
} catch {
  // No local env file; defaults apply.
}

const port = Number(process.env.PORT) || 8000;
process.env.REPLAND_ACCESS_CODE ||= 'repland-test';
process.env.REPLAND_AI_DAILY_LIMIT ||= '60';

const db = createFakeDatabase();
const handle = application({ db });

const server = createServer(async (request, response) => {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const url = `http://127.0.0.1:${port}${request.url}`;
  try {
    const incoming = new Request(url, {
      method: request.method,
      headers: request.headers,
      body: body && body.length ? body : undefined,
    });
    const result = await handle({ request: incoming, requestId: crypto.randomUUID() });
    const text = await result.text();
    response.writeHead(result.status, Object.fromEntries(result.headers.entries()));
    response.end(text);
  } catch (error) {
    response.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ error: 'local_runtime_error', message: String(error?.message || error) }));
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Local Repland function at http://127.0.0.1:${port}/functions/v1/app/api/health`);
  console.log(`Experience code: ${process.env.REPLAND_ACCESS_CODE}`);
});
