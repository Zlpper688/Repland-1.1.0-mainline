import { ApiError } from './api';
import type { TeamSchedule, TimetableEntry, TeamMeeting } from './types';

const BASE = '/functions/v1/app';
// The website is the demo surface; it reads and writes the demo team document
// so visitors never touch the team the bot and the app use.
const TEAM_KEY = 'demo';

async function request<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Team-Key': TEAM_KEY,
  };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
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
  const data = (await response.json().catch(() => null)) as { error?: string; message?: string } | null;
  if (!response.ok) {
    throw new ApiError(data?.error || `http_${response.status}`, response.status, data?.message);
  }
  return data as T;
}

export const teamApi = {
  schedule(params: { days?: number; duration?: number; members?: string[] }) {
    const query = new URLSearchParams();
    if (params.days) query.set('days', String(params.days));
    if (params.duration) query.set('duration', String(params.duration));
    if (params.members?.length) query.set('members', params.members.join(','));
    return request<TeamSchedule>(`/api/team/schedule?${query.toString()}`);
  },
  createMeeting(body: {
    title: string;
    date: string;
    startMinute: number;
    endMinute: number;
    memberIds: string[];
    note?: string;
  }) {
    return request<{ meeting: TeamMeeting }>('/api/team/meetings', { method: 'POST', body });
  },
  deleteMeeting(id: string) {
    return request<{ ok: boolean }>(`/api/team/meetings/${id}`, { method: 'DELETE' });
  },
  saveTimetable(memberId: string, entries: TimetableEntry[]) {
    return request<{ timetable: TimetableEntry[] }>('/api/team/timetable', {
      method: 'POST',
      body: { memberId, entries },
    });
  },
};
