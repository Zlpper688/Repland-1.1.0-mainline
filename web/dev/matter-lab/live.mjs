// Live AI side: one disposable guest, state reset before every case.
import { TODAY } from './corpus.mjs';

const HOST = 'https://repland-days-i3hwem33xb3.qoder.zone';
const BASE = `${HOST}/functions/v1/app`;
const ORIGIN = HOST;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class LiveClient {
  constructor({ host = HOST } = {}) {
    this.host = host;
    this.base = `${host}/functions/v1/app`;
    this.origin = host;
    this.token = null;
    this.deviceId = `matterlab-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  }

  async raw(path, { method = 'GET', body, timeout = 60000 } = {}) {
    const headers = { origin: this.origin };
    if (body !== undefined) headers['content-type'] = 'application/json';
    if (this.token) headers['x-repland-token'] = this.token;
    const response = await fetch(`${this.base}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeout),
    });
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = { raw: text.slice(0, 300) };
    }
    return { status: response.status, data };
  }

  async request(path, options = {}) {
    let last = null;
    for (let attempt = 0; attempt < 4; attempt += 1) {
      try {
        last = await this.raw(path, options);
      } catch (error) {
        last = { status: 0, data: { error: error.name } };
      }
      if (last.status === 401 || last.status === 403) {
        this.token = null;
        await this.login();
        continue;
      }
      if (last.status === 429 || last.status >= 500 || last.status === 0) {
        await sleep(800 * (attempt + 1));
        continue;
      }
      return last;
    }
    return last;
  }

  async login() {
    const result = await this.raw('/api/auth/guest', {
      method: 'POST',
      body: { deviceId: this.deviceId },
    });
    if (result.status !== 200 || !result.data?.token) {
      throw new Error(`guest login failed: ${result.status} ${JSON.stringify(result.data).slice(0, 200)}`);
    }
    this.token = result.data.token;
    return result.data;
  }

  async reset() {
    if (!this.token) return;
    await this.request('/api/data', { method: 'DELETE', body: { confirm: 'DELETE' } });
  }

  async seed(task) {
    if (!task?.name) return;
    const body = {
      name: task.name,
      durationMinutes: task.durationMinutes ?? null,
      plannedDate: task.date ?? null,
    };
    if (task.date && task.startMinute != null) {
      body.date = task.date;
      body.start = `${String(Math.floor(task.startMinute / 60)).padStart(2, '0')}:${String(task.startMinute % 60).padStart(2, '0')}`;
      body.schedule = true;
    }
    await this.request('/api/tasks', { method: 'POST', body });
  }

  async ask(message) {
    const result = await this.request('/api/assistant', {
      method: 'POST',
      body: { message, date: TODAY },
      timeout: 90000,
    });
    return result;
  }
}

export function toPrediction(response) {
  const proposal = response?.proposal || null;
  const reply = response?.reply || '';
  if (!proposal) {
    const looksLikeQuestion = /[?？]|要不要|哪个|几点|什么|哪一天|先排|确认/.test(reply);
    return { intent: looksLikeQuestion ? 'clarify' : 'query', items: [], reply, source: response?.source || 'none' };
  }
  const intent = proposal.intent || 'plan';
  const items = (proposal.items || []).map((item) => {
    const startMinute = item.startMinute ?? null;
    const endMinute = item.endMinute ?? null;
    // A server-picked routine slot carries start/end but drops the duration; the block is the duration.
    const picked = Boolean(item.autoPicked) || (item.action || item.kind) === 'routine';
    const durationMinutes = item.durationMinutes ?? (picked && startMinute != null && endMinute != null ? endMinute - startMinute : null);
    return {
      action: item.action || item.kind || 'create',
      name: item.name || '',
      date: item.date ?? item.placement?.date ?? null,
      startMinute,
      endMinute,
      durationMinutes,
      weekdays: item.weekdays ?? null,
      move: Boolean(item.move),
      progress: item.progress ?? null,
      dueDate: item.dueDate ?? null,
      dueMinute: item.dueMinute ?? null,
      actualMinutes: item.actualMinutes ?? null,
      placement: item.placement || null,
      autoPicked: Boolean(item.autoPicked),
    };
  });
  return { intent, items, reply, source: response?.source || proposal.source || 'ai', proposalId: proposal.id };
}
