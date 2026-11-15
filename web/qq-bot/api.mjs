// Client for the shared Repland team backend. The bot owns no data of its own:
// every command is executed server-side against app.team_state so the website,
// Android app and QQ always see the same board.

const DEFAULT_BASE = 'https://repland-days-i3hwem33xb3.qoder.zone';
const BASE = (process.env.REPLAND_API_BASE || DEFAULT_BASE).replace(/\/+$/, '');
const PREFIX = `${BASE}/functions/v1/app`;
const CODE = process.env.REPLAND_TEAM_CODE;
if (!CODE) {
  console.error('REPLAND_TEAM_CODE is required');
  process.exit(1);
}

async function call(path, { method = 'GET', body, timeout = 15_000 } = {}) {
  const response = await fetch(`${PREFIX}${path}`, {
    method,
    // The site gateway rejects writes whose Origin does not match the site host.
    headers: { 'content-type': 'application/json', 'x-team-code': CODE, origin: BASE },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeout),
  });
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { raw: text };
  }
  if (!response.ok) {
    const detail = data?.error || data?.message || text.slice(0, 120);
    throw new Error(`团队服务 ${response.status}：${detail}`);
  }
  return data;
}

export function apiBase() {
  return BASE;
}

export function teamHealth() {
  return call('/api/health');
}

export function teamCommand(chat, text) {
  return call('/api/team/command', { method: 'POST', body: { ...chat, text } });
}

export function teamBoard() {
  return call('/api/team/board');
}

export function queuePending(memberId, text, priority = 'normal') {
  return call('/api/team/pending', { method: 'POST', body: { memberId, text, priority } });
}

export async function uploadImage(buffer, kind = 'weekly') {
  const data = await call('/api/team/media', {
    method: 'POST',
    body: { base64: buffer.toString('base64'), kind },
    timeout: 30_000,
  });
  if (!data?.url) throw new Error('图片上传没有拿到地址');
  return data.url.startsWith('/') ? `${BASE}${data.url}` : data.url;
}
