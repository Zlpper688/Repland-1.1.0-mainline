import type {
  AppState,
  AssistantReply,
  FixedKind,
  Priority,
  Summary,
  StateWithExecution,
  StateWithPlan,
  TaskInput,
} from './types';

const BASE = '/functions/v1/app';
const TOKEN_KEY = 'repland.token';
const DEVICE_KEY = 'repland.device';

export class ApiError extends Error {
  code: string;
  status: number;

  constructor(code: string, status: number, message?: string) {
    super(message || code);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string) {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // Storage may be unavailable in private mode; the session then ends on refresh.
  }
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // ignore
  }
}

export function getDeviceId(): string {
  try {
    const existing = localStorage.getItem(DEVICE_KEY);
    if (existing && /^[A-Za-z0-9_-]{8,128}$/.test(existing)) return existing;
    const created = crypto.randomUUID().replace(/-/g, '');
    localStorage.setItem(DEVICE_KEY, created);
    return created;
  } catch {
    return crypto.randomUUID().replace(/-/g, '');
  }
}

async function request<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const token = getToken();
  // The hosting gateway drops the Authorization header, so the app token travels
  // in a custom header that reaches the Function intact.
  if (token) headers['X-Repland-Token'] = token;
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, {
      method: options.method || 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError('network_unreachable', 0, '网络没有接通，请稍后再试。');
  }
  let data: unknown = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }
  if (!response.ok) {
    const body = (data || {}) as { error?: string; message?: string };
    throw new ApiError(body.error || `http_${response.status}`, response.status, body.message);
  }
  return data as T;
}

export const api = {
  guest() {
    return request<{ token: string; profile: { id: string; name: string } }>('/api/auth/guest', {
      method: 'POST',
      body: { deviceId: getDeviceId() },
    });
  },
  bootstrap() {
    return request<AppState>('/api/bootstrap');
  },
  createTask(input: TaskInput) {
    return request<AppState>('/api/tasks', { method: 'POST', body: input });
  },
  updateTask(
    id: string,
    patch: Partial<{
      name: string;
      note: string;
      durationMinutes: number | null;
      priority: Priority;
      category: string;
      dueDate: string | null;
      plannedDate: string | null;
      progress: number;
      status: string;
      actualMinutes: number;
    }>,
  ) {
    return request<AppState>(`/api/tasks/${id}`, { method: 'PATCH', body: patch });
  },
  scheduleTask(id: string, date: string, start?: string | null) {
    return request<StateWithExecution>(`/api/tasks/${id}/schedule`, { method: 'POST', body: { date, start } });
  },
  moveSegment(id: string, body: { date: string; startMinute: number; endMinute: number }) {
    return request<AppState>(`/api/segments/${id}`, { method: 'PATCH', body });
  },
  deleteSegment(id: string) {
    return request<AppState>(`/api/segments/${id}`, { method: 'DELETE' });
  },
  addFixedBlock(body: {
    title: string;
    kind: FixedKind;
    weekday?: number | null;
    date?: string | null;
    startMinute: number;
    endMinute: number;
  }) {
    return request<AppState>('/api/fixed-blocks', { method: 'POST', body });
  },
  deleteFixedBlock(id: string) {
    return request<AppState>(`/api/fixed-blocks/${id}`, { method: 'DELETE' });
  },
  plan(mode: 'fill' | 'replan', horizonDays = 7) {
    return request<StateWithPlan>('/api/plan', { method: 'POST', body: { mode, horizonDays } });
  },
  assistant(message: string, date: string) {
    return request<AssistantReply>('/api/assistant', { method: 'POST', body: { message, date } });
  },
  confirm(proposalId: string) {
    return request<StateWithExecution>('/api/assistant/confirm', { method: 'POST', body: { proposalId } });
  },
  cancelProposal(proposalId: string) {
    return request<AppState>('/api/assistant/cancel', { method: 'POST', body: { proposalId } });
  },
  deleteChatMessage(id: string) {
    return request<AppState>(`/api/chat/${id}`, { method: 'DELETE' });
  },
  undo() {
    return request<StateWithExecution>('/api/undo', { method: 'POST', body: {} });
  },
  focusStart(taskId: string) {
    return request<AppState>('/api/focus/start', { method: 'POST', body: { taskId } });
  },
  focusEnd(sessionId: string, minutes: number, progress?: number) {
    return request<AppState>('/api/focus/end', { method: 'POST', body: { sessionId, minutes, progress } });
  },
  summary() {
    return request<Summary>('/api/summary');
  },
  resetData() {
    return request<AppState>('/api/data', { method: 'DELETE', body: { confirm: 'DELETE' } });
  },
};
