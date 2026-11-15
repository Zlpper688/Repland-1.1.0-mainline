import {
  TEAM_KEY,
  availabilityView,
  createTeamDoc,
  handleTeamCommand,
  normalizeDoc,
  planMeeting,
  queuePending,
  removeMeeting,
  replaceTimetable,
  teamBoard,
  weeklyReportData,
} from './team.mjs';
import {
  CATEGORIES,
  DAY_END_MINUTE,
  DAY_START_MINUTE,
  PRIORITIES,
  addDays,
  autoSchedule,
  clockToMinutes,
  dayBriefing,
  diffDays,
  earliestPlacementMinute,
  freeMinutes,
  freeWindows,
  isActiveTask,
  minutesToClock,
  nowMinuteInShanghai,
  occupiedForDate,
  parseDate,
  planDay,
  rankTasks,
  spreadDay,
  taskRemainingMinutes,
  todayInShanghai,
  validatePlacement,
  weekdayLabel,
  weekdayOf,
} from './planner.mjs';
import { applyAiOverrides, buildRulePlan, ruleClarifyReply } from './rule-parser.mjs';

const DEFAULT_BASE_URL = 'https://apihub.agnes-ai.com/v1';
const ALTERNATE_BASE_URL = 'https://api.agnes-ai.cn/v1';
const DEFAULT_MODEL = 'agnes-3.0-flash';
const SESSION_DAYS = 30;
const AI_DAILY_LIMIT_DEFAULT = 60;
const MAX_JSON_BYTES = 32 * 1024;

function env(name) {
  try {
    if (globalThis.Deno?.env?.get) return globalThis.Deno.env.get(name) || '';
  } catch {
    // fall through to Node
  }
  try {
    return globalThis.process?.env?.[name] || '';
  } catch {
    return '';
  }
}

function envInt(name, fallback) {
  const raw = env(name).trim();
  if (!raw) return fallback;
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? Math.floor(value) : fallback;
}

function aiKey() {
  // Keys copied from a console often carry zero-width or non-breaking spaces that
  // trim() keeps, and the upstream answers "invalid token" for those.
  return env('AGNES_API_KEY').replace(/[^A-Za-z0-9\-_./+=]/g, '');
}

// Pasted codes often carry zero-width marks or full-width characters; compare
// the visible text only.
function normalizeCode(value) {
  return value
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[\s\u00A0\u200B-\u200D\uFEFF]/g, '');
}

function nowIso() {
  return new Date().toISOString();
}

function randomId() {
  return crypto.randomUUID();
}

function sha256Hex(text) {
  return crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)).then((buffer) =>
    [...new Uint8Array(buffer)].map((byte) => byte.toString(16).padStart(2, '0')).join(''),
  );
}

function randomToken() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  let text = '';
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function safeEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false;
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let index = 0; index < left.length; index += 1) diff |= left.charCodeAt(index) ^ right.charCodeAt(index);
  return diff === 0;
}

function sanitizeName(value) {
  if (typeof value !== 'string') return null;
  const name = value.trim().replace(/\s+/g, ' ');
  if (!name || name.length > 20) return null;
  if (/[\u0000-\u001f\u007f]/.test(name)) return null;
  return name;
}

function cleanText(value, maxLength) {
  if (typeof value !== 'string') return '';
  const text = value.trim();
  return text.length > maxLength ? text.slice(0, maxLength) : text;
}

function normalizePriority(value, fallback = 'MEDIUM') {
  const priority = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return PRIORITIES.includes(priority) ? priority : fallback;
}

function normalizeCategory(value, fallback = 'UNSPECIFIED') {
  const category = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return CATEGORIES.includes(category) ? category : fallback;
}

function normalizeDate(value, fallback = null) {
  if (typeof value !== 'string') return fallback;
  const text = value.trim();
  if (!parseDate(text)) return fallback;
  return text;
}

function normalizeClock(value, { allowEnd = false } = {}) {
  const maximum = allowEnd ? 24 * 60 : 24 * 60 - 1;
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.max(0, Math.min(maximum, Math.round(value)));
  }
  if (typeof value !== 'string') return null;
  const text = value.trim();
  if (text === '24:00' || text === '24：00') return allowEnd ? 24 * 60 : null;
  if (/^\d{1,2}:\d{2}$/.test(text)) return clockToMinutes(text);
  if (/^\d{1,2}$/.test(text)) {
    const hour = Number(text);
    return hour >= 0 && hour <= 23 ? hour * 60 : null;
  }
  return null;
}

function durationOf(value, fallback = null) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  const rounded = Math.round(number);
  if (rounded < 5 || rounded > 720) return fallback;
  return rounded;
}

function jsonResponse(body, status = 200) {
  return Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });
}

class AppError extends Error {
  constructor(code, status = 400, extra = {}) {
    super(code);
    this.code = code;
    this.status = status;
    this.extra = extra;
  }
}

function fail(code, status = 400, extra = {}) {
  throw new AppError(code, status, extra);
}

function rows(result) {
  if (!result) fail('database_unavailable', 503);
  if (result.error) {
    const code = result.error.code || 'database_unavailable';
    const status = Number(result.error.status) >= 400 ? Number(result.error.status) : 503;
    fail(code === 'PGRST116' ? 'not_found' : code, status >= 500 ? 503 : status);
  }
  return result.data;
}

async function readJson(request, maxBytes = MAX_JSON_BYTES) {
  const type = request.headers.get('content-type') || '';
  if (!type.startsWith('application/json')) fail('invalid_input', 400);
  const declared = Number(request.headers.get('content-length') || 0);
  if (declared > maxBytes) fail('payload_too_large', 413);
  const text = await request.text();
  if (new TextEncoder().encode(text).length > maxBytes) fail('payload_too_large', 413);
  try {
    return JSON.parse(text);
  } catch {
    fail('invalid_input', 400);
  }
}

function bearer(request) {
  const custom = request.headers.get('x-repland-token') || '';
  if (/^[A-Za-z0-9_-]{20,}$/.test(custom)) return custom;
  const header = request.headers.get('authorization') || '';
  const match = header.match(/^Bearer\s+([A-Za-z0-9_-]{20,})$/);
  return match ? match[1] : null;
}

async function authenticate(request, db) {
  const token = bearer(request);
  if (!token) fail('login_required', 401);
  const tokenHash = await sha256Hex(token);
  const session = rows(
    await db.from('sessions').select('id,user_id,token_hash,expires_at').eq('token_hash', tokenHash).limit(1),
  )?.[0];
  if (!session) fail('login_required', 401);
  if (new Date(session.expires_at).getTime() <= Date.now()) fail('login_required', 401);
  const profile = rows(await db.from('profiles').select('id,name,created_at').eq('id', session.user_id).limit(1))?.[0];
  if (!profile) fail('login_required', 401);
  return profile;
}

function mapSegment(row) {
  return row ? { ...row, date: row.on_date } : row;
}

function mapBlock(row) {
  return row ? { ...row, date: row.specific_date } : row;
}

async function loadState(db, user, { includeChat = true } = {}) {
  const today = todayInShanghai();
  const nowMinute = nowMinuteInShanghai();
  const from = addDays(today, -14);
  const to = addDays(today, 45);
  const tasks = rows(
    await db.from('tasks').select('*').eq('user_id', user.id).order('created_at', { ascending: false }).limit(300),
  ) || [];
  const segmentRows = rows(
    await db
      .from('segments')
      .select('*')
      .eq('user_id', user.id)
      .gte('on_date', from)
      .lte('on_date', to)
      .order('on_date', { ascending: true })
      .order('start_minute', { ascending: true })
      .limit(500),
  ) || [];
  const blockRows = rows(
    await db.from('fixed_blocks').select('*').eq('user_id', user.id).order('start_minute', { ascending: true }).limit(120),
  ) || [];
  const pendingRows = rows(
    await db
      .from('proposals')
      .select('id,payload,created_at')
      .eq('user_id', user.id)
      .eq('status', 'pending')
      .gte('created_at', new Date(Date.now() - DRAFT_TTL_MS).toISOString())
      .order('created_at', { ascending: false })
      .limit(1),
  ) || [];
  const operationRows = rows(
    await db
      .from('operations')
      .select('id,kind,label,created_at')
      .eq('user_id', user.id)
      .is('undone_at', null)
      .order('created_at', { ascending: false })
      .limit(1),
  ) || [];
  const focusRows = rows(
    await db
      .from('focus_sessions')
      .select('*')
      .eq('user_id', user.id)
      .gte('started_at', new Date(Date.now() - 30 * 86_400_000).toISOString())
      .order('started_at', { ascending: false })
      .limit(80),
  ) || [];
  const aiUsage = rows(
    await db
      .from('ai_usage')
      .select('id')
      .eq('user_id', user.id)
      .gte('created_at', new Date(Date.now() - 86_400_000).toISOString())
      .limit(200),
  ) || [];
  let chat = [];
  if (includeChat) {
    chat = (rows(
      await db.from('chat_messages').select('id,role,content,created_at').eq('user_id', user.id).order('created_at', { ascending: false }).limit(60),
    ) || []).reverse();
  }
  return {
    today,
    nowMinute,
    timezone: 'Asia/Shanghai',
    profile: { id: user.id, name: user.name },
    tasks,
    segments: segmentRows.map(mapSegment),
    fixedBlocks: blockRows.map(mapBlock),
    chat,
    pendingProposal: pendingRows[0] ? { id: pendingRows[0].id, payload: pendingRows[0].payload, created_at: pendingRows[0].created_at } : null,
    lastOperation: operationRows[0] || null,
    focusSessions: focusRows,
    ai: {
      configured: Boolean(aiKey()),
      usedToday: aiUsage.length,
      dailyLimit: envInt('REPLAND_AI_DAILY_LIMIT', AI_DAILY_LIMIT_DEFAULT),
    },
  };
}

async function appendChat(db, user, role, content) {
  await db.from('chat_messages').insert({
    id: randomId(),
    user_id: user.id,
    role,
    content: cleanText(content, 4000),
    created_at: nowIso(),
  });
}

async function recordAiUsage(db, user) {
  await db.from('ai_usage').insert({ id: randomId(), user_id: user.id, created_at: nowIso() });
}

// 日常安排是"每周重复的固定占用"，fixed_blocks 一行一天；模型只给一次
// 语义（每天/工作日/周末/每周几），这里统一展开成 1–7 的星期编号。
const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

function normalizeWeekdays(value) {
  const list = Array.isArray(value) ? value : value == null ? [] : [value];
  const days = new Set();
  for (const entry of list) {
    const day = Number(entry);
    if (Number.isInteger(day) && day >= 1 && day <= 7) days.add(day);
  }
  return [...days].sort((a, b) => a - b);
}

function weekdaysFromRepeat(raw) {
  const repeat = String(raw?.repeat ?? raw?.recurrence ?? '').trim().toLowerCase();
  if (!repeat) return [];
  if (/daily|每天|天天|每日/.test(repeat)) return [1, 2, 3, 4, 5, 6, 7];
  if (/weekday|工作日|周一到周五/.test(repeat)) return [1, 2, 3, 4, 5];
  if (/weekend|周末/.test(repeat)) return [6, 7];
  return [];
}

// The model usually repeats the user's recurrence, but a dropped weekdays field must
// not silently turn "每天" into a single day. Read it back from the sentence itself.
function weekdaysFromMessage(message) {
  const text = String(message || '');
  if (!text) return [];
  if (/(每天|每日|天天|每一天)/.test(text)) return [1, 2, 3, 4, 5, 6, 7];
  if (/(工作日|平日|平时|周一到周五|周一至周五|星期一到星期五|礼拜一到礼拜五)/.test(text)) {
    return [1, 2, 3, 4, 5];
  }
  if (/(周末|周六周日|周六、周日|星期六日)/.test(text)) return [6, 7];
  const days = new Set();
  for (const match of text.matchAll(/每(?:周|星期|礼拜)([一二三四五六日天])/g)) {
    const index = '一二三四五六日'.indexOf(match[1].replace('天', '日'));
    if (index >= 0) days.add(index + 1);
  }
  for (const match of text.matchAll(
    /(?:周|星期|礼拜)([一二三四五六日天])\s*(?:到|至|-|~|～)\s*(?:周|星期|礼拜)?([一二三四五六日天])/g,
  )) {
    const from = '一二三四五六日'.indexOf(match[1].replace('天', '日')) + 1;
    const to = '一二三四五六日'.indexOf(match[2].replace('天', '日')) + 1;
    if (from > 0 && to > 0 && from <= to) {
      for (let day = from; day <= to; day += 1) days.add(day);
    }
  }
  return [...days].sort((a, b) => a - b);
}

function routinePreference(message) {
  const text = String(message || '');
  if (/(早上|早晨|上午|清晨|一大早|一早)/.test(text)) return 'morning';
  if (/(下午|午后|中午)/.test(text)) return 'afternoon';
  if (/(晚上|傍晚|夜里|夜间|晚间|今晚)/.test(text)) return 'evening';
  return null;
}

function weekdayRangeText(weekdays) {
  const days = normalizeWeekdays(weekdays);
  if (days.length === 7) return '每天';
  if (days.length === 5 && days.every((day, index) => day === index + 1)) return '工作日';
  if (days.length === 2 && days[0] === 6 && days[1] === 7) return '周末';
  return `每周${days.map((day) => WEEKDAY_LABELS[day - 1]).join('、')}`;
}

function routineWhenText(item) {
  if (item?.startMinute == null || item?.endMinute == null) return '';
  const days = item.weekdays?.length ? weekdayRangeText(item.weekdays) : item.date || '';
  return `${days} ${minutesToClock(item.startMinute)}–${minutesToClock(item.endMinute)}`.trim();
}

// 「八点二十分」里的分钟也要跟着钟点一起收走，否则它会被当成 20 分钟的时长。
const CLOCK_WORD_PATTERN =
  /([一二两三四五六七八九十]{1,3}|\d{1,2})\s*(?:点|(?<![小个])时)\s*(半|一刻|三刻|(?:[一二两三四五六七八九十]{1,2}|\d{1,2})\s*分?)?/g;

function clockMinuteValue(raw) {
  const text = String(raw || '').replace(/分$/, '').trim();
  if (!text) return 0;
  if (text === '半') return 30;
  if (text === '一刻') return 15;
  if (text === '三刻') return 45;
  return chineseNumberValue(text) ?? 0;
}

// A routine repeats at one clock time. Choosing it is a constraint-satisfaction job over
// the same facts the day view already has, so it belongs here and not in the model's head.
function clocksFromMessage(message) {
  const text = String(message || '');
  // Shift applies per clock: "晚上8点到10点" shifts both, "晚上11点到凌晨1点" only the first.
  const shiftAt = (index) => {
    const tail = text.slice(Math.max(0, index - 8), index);
    if (/(凌晨|早上|早晨|上午|清早)/.test(tail)) return 0;
    if (/(下午|晚上|傍晚|夜里|夜间|晚间|今晚|中午)/.test(tail)) return 12;
    return 0;
  };
  const clocks = [];
  const push = (hour, minute, index) => {
    const value = shiftAt(index) && hour >= 1 && hour <= 11 ? hour + 12 : hour;
    if (value <= 23 && minute <= 59) clocks.push(value * 60 + minute);
  };
  for (const match of text.matchAll(/(\d{1,2})[:：](\d{2})/g)) {
    push(Number(match[1]), Number(match[2]), match.index);
  }
  for (const match of text.matchAll(CLOCK_WORD_PATTERN)) {
    const hour = chineseNumberValue(match[1]);
    if (hour == null) continue;
    const minute = clockMinuteValue(match[2]);
    push(hour, minute, match.index);
  }
  return [...new Set(clocks)];
}

function mergedMinuteIntervals(intervals, startMinute, endMinute) {
  const sorted = intervals
    .map(([start, end]) => [Math.max(startMinute, Number(start)), Math.min(endMinute, Number(end))])
    .filter(([start, end]) => end > start)
    .sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const merged = [];
  for (const interval of sorted) {
    const last = merged[merged.length - 1];
    if (last && interval[0] <= last[1]) last[1] = Math.max(last[1], interval[1]);
    else merged.push([...interval]);
  }
  return merged;
}

function freeMinuteIntervals(busy, startMinute, endMinute) {
  const merged = mergedMinuteIntervals(busy, startMinute, endMinute);
  const free = [];
  let cursor = startMinute;
  for (const [start, end] of merged) {
    if (start > cursor) free.push([cursor, start]);
    cursor = Math.max(cursor, end);
  }
  if (cursor < endMinute) free.push([cursor, endMinute]);
  return free;
}

function intersectMinuteIntervals(left, right) {
  const result = [];
  for (const [aStart, aEnd] of left) {
    for (const [bStart, bEnd] of right) {
      const start = Math.max(aStart, bStart);
      const end = Math.min(aEnd, bEnd);
      if (end > start) result.push([start, end]);
    }
  }
  return result.sort((a, b) => a[0] - b[0]);
}

function routineBusyByWeekday(state, weekdays, { excludeName = '', tasksById }) {
  const byDay = new Map(weekdays.map((day) => [day, []]));
  for (const block of state.fixedBlocks) {
    const day = block.specific_date ? weekdayOf(block.specific_date) : Number(block.weekday);
    if (!byDay.has(day)) continue;
    if (excludeName && String(block.title || '').trim() === excludeName) continue;
    byDay.get(day).push([Number(block.start_minute), Number(block.end_minute)]);
  }
  // One-off task slots still belong to this week's answer: a routine that lands on top
  // of tonight's scheduled work is not "看着空档".
  for (let offset = 0; offset < 7; offset += 1) {
    const date = addDays(state.today, offset);
    const day = weekdayOf(date);
    if (!byDay.has(day)) continue;
    for (const segment of state.segments) {
      if (segment.date !== date) continue;
      const task = tasksById.get(segment.task_id);
      if (task && !isActiveTask(task)) continue;
      byDay.get(day).push([Number(segment.start_minute), Number(segment.end_minute)]);
    }
  }
  return byDay;
}

function reversedRoutineQuestion(name, first, second, message = '') {
  // 22:00 到 02:00 说的是跨夜，不是把两个钟点说反了；给一个 02:00–22:00 的建议是荒谬的。
  const crossesMidnight =
    first >= 18 * 60 && (second <= 6 * 60 || /凌晨|半夜|深夜|次日|第二天/.test(String(message || '')));
  if (crossesMidnight) {
    return (
      `「${name}」从 ${minutesToClock(first)} 到 ${minutesToClock(second)} 会跨过午夜，固定安排不能跨天。` +
      `要拆成 ${minutesToClock(first)}–24:00 和 00:00–${minutesToClock(second)} 两段，还是换一个不跨夜的时间？`
    );
  }
  const start = Math.min(first, second);
  const end = Math.max(first, second);
  return `「${name}」的 ${minutesToClock(first)} 和 ${minutesToClock(second)} 在同一天里是倒着的，固定安排不能跨天。要按 ${minutesToClock(start)}–${minutesToClock(end)} 设吗？`;
}

function overflowRoutineQuestion(name, startMinute, duration) {
  const latest = 24 * 60 - startMinute;
  const shrink = Math.max(5, Math.min(duration, latest));
  const crosses = minutesToClock((startMinute + duration) % (24 * 60));
  const option = latest >= 30
    ? `缩短到 ${shrink} 分钟（${minutesToClock(startMinute)}–24:00）`
    : '换个更早的开始时间';
  return `「${name}」从 ${minutesToClock(startMinute)} 开始 ${duration} 分钟会排到次日 ${crosses}，固定安排不能跨天。要${option}吗？`;
}

function routineSlotFor(state, { name, weekdays, date = null, durationMinutes, message = '', excludeName = '' }) {
  const days = date ? [weekdayOf(date)] : normalizeWeekdays(weekdays);
  const dayStart = DAY_START_MINUTE;
  const dayEnd = DAY_END_MINUTE;
  const duration = Math.max(5, Math.min(720, Math.round(Number(durationMinutes) || 60)));
  const daysText = date || weekdayRangeText(days);
  if (!days.length) return { error: `「${name}」是每天、每周几，还是只锁定某一天？` };
  if (duration > dayEnd - dayStart) {
    return { error: `「${name}」要 ${duration} 分钟，但可排的时间只有 ${minutesToClock(dayStart)}–${minutesToClock(dayEnd)}。要缩短时长，还是把时间点明？` };
  }
  const tasksById = new Map(state.tasks.map((task) => [task.id, task]));
  const busyByDay = routineBusyByWeekday(state, days, { excludeName, tasksById });
  const freeByDay = new Map();
  for (const day of days) freeByDay.set(day, freeMinuteIntervals(busyByDay.get(day) || [], dayStart, dayEnd));
  const starts = [];
  for (let start = dayStart; start + duration <= dayEnd; start += 15) {
    if (days.every((day) => freeByDay.get(day).some(([from, to]) => start >= from && start + duration <= to))) {
      starts.push(start);
    }
  }
  if (starts.length) {
    const preference = routinePreference(message);
    if (preference === 'morning') {
      const morning = starts.filter((start) => start < 12 * 60);
      const startMinute = Math.min(...(morning.length ? morning : starts));
      return { startMinute, endMinute: startMinute + duration };
    }
    if (preference === 'afternoon') {
      const afternoon = starts.filter((start) => start >= 12 * 60 && start + duration <= 18 * 60);
      const evening = starts.filter((start) => start >= 17 * 60);
      const pool = afternoon.length ? afternoon : evening.length ? evening : starts;
      const startMinute = Math.max(...pool);
      return { startMinute, endMinute: startMinute + duration };
    }
    const evening = starts.filter((start) => start >= 17 * 60);
    const pool = evening.length ? evening : starts;
    const startMinute = Math.max(...pool);
    return { startMinute, endMinute: startMinute + duration };
  }
  let common = null;
  for (const free of freeByDay.values()) {
    common = common == null ? free.map((interval) => [...interval]) : intersectMinuteIntervals(common, free);
  }
  const longest = (common || []).sort((a, b) => b[1] - b[0] - (a[1] - a[0]))[0];
  if (!longest || longest[1] - longest[0] < 30) {
    return { error: `「${name}」要排${daysText}，但这几天已经排满了，找不到共同空档。要减少天数，还是换个时长？` };
  }
  return {
    error: `「${name}」要排${daysText} ${duration} 分钟，但这几天没有共同的长空档；最长的一段是 ${minutesToClock(longest[0])}–${minutesToClock(longest[1])}（${longest[1] - longest[0]} 分钟）。要缩短到这个时长，还是只排其中几天？`,
  };
}

function describeFixedRoutines(state) {
  const grouped = new Map();
  for (const block of state.fixedBlocks) {
    const title = String(block.title || '').trim();
    if (!title) continue;
    if (!grouped.has(title)) grouped.set(title, []);
    grouped.get(title).push(block);
  }
  return [...grouped.entries()]
    .slice(0, 5)
    .map(([title, blocks]) => {
      const first = blocks[0];
      const days = [...new Set(blocks.map((block) => (block.specific_date ? weekdayOf(block.specific_date) : Number(block.weekday))))];
      const aligned = blocks.every(
        (block) => Number(block.start_minute) === Number(first.start_minute) && Number(block.end_minute) === Number(first.end_minute),
      );
      const when = aligned
        ? `${weekdayRangeText(days)} ${minutesToClock(Number(first.start_minute))}–${minutesToClock(Number(first.end_minute))}`
        : `${weekdayRangeText(days)}（时间不一）`;
      return `「${title}」${when}`;
    })
    .join('；');
}

function factBundle(state, activeDate) {
  const tasksById = new Map(state.tasks.map((task) => [task.id, task]));
  const occupiedToday = occupiedForDate(activeDate, {
    segments: state.segments,
    fixedBlocks: state.fixedBlocks,
    tasksById,
  });
  // 今天只把此刻之后的空档算进来：早上已经过去的小时不是"空档"。
  const windows = freeWindows(
    activeDate,
    occupiedToday,
    activeDate === state.today ? { fromMinute: state.nowMinute } : undefined,
  );
  const briefing = dayBriefing({
    dateText: activeDate,
    occupied: occupiedToday,
    tasks: state.tasks,
    freeWindowList: windows,
  });
  const activeTasks = state.tasks.filter(isActiveTask).slice(0, 24);
  const taskLines = activeTasks.length
    ? activeTasks
        .map(
          (task) =>
            `- id=${task.id}｜${task.name}｜${task.status === 'IN_PROGRESS' ? '进行中' : '未开始'}${
              task.progress ? ` ${task.progress}%` : ''
            }｜${task.duration_minutes || '未估时'} 分钟｜${task.priority || 'MEDIUM'}｜截止 ${
              task.due_date || '无'
            }｜计划 ${task.planned_date || '无'}`,
        )
        .join('\n')
    : '- 暂无待办任务';
  const upcoming = state.segments
    .filter((segment) => segment.date >= state.today && segment.date <= addDays(state.today, 14))
    .sort((a, b) => String(a.date).localeCompare(String(b.date)) || a.start_minute - b.start_minute)
    .slice(0, 30);
  const scheduleLines = upcoming.length
    ? upcoming
        .map((segment) => {
          const task = tasksById.get(segment.task_id);
          const status = task ? (task.status === 'COMPLETED' ? '已完成' : task.status === 'IN_PROGRESS' ? '进行中' : '未开始') : '未知';
          return `- ${task?.name || '任务'}（${status}）｜${segment.date} ${minutesToClock(segment.start_minute)}–${minutesToClock(segment.end_minute)}｜taskId=${segment.task_id}｜segmentId=${segment.id}`;
        })
        .join('\n')
    : '- 暂无已排时段';
  // Routines (weekly fixed blocks) are hard-busy and invisible in the task lists;
  // without this section the model can neither honour nor cancel them.
  const routineLines = state.fixedBlocks.length
    ? state.fixedBlocks
        .slice()
        .sort((a, b) => Number(a.weekday || 0) - Number(b.weekday || 0) || a.start_minute - b.start_minute)
        .map((block) => {
          const when = block.specific_date
            ? block.specific_date
            : `每周${WEEKDAY_LABELS[Number(block.weekday) - 1] || '?'}`;
          return `- id=${block.id}｜${block.title}｜${when}｜${minutesToClock(block.start_minute)}–${minutesToClock(block.end_minute)}｜${block.kind || 'FIXED'}`;
        })
        .join('\n')
    : '- 暂无固定安排';
  // A conversation about "tomorrow" or "this week" needs the same free-time facts
  // that today already gets; otherwise the model can only guess.
  const horizonLines = [];
  let horizonFreeMinutes = 0;
  for (let offset = 0; offset < 7; offset += 1) {
    const date = addDays(activeDate, offset);
    const occupied = offset === 0
      ? occupiedToday
      : occupiedForDate(date, { segments: state.segments, fixedBlocks: state.fixedBlocks, tasksById });
    // "还剩多少空" must not count hours that already passed earlier today.
    const horizonStart = date === state.today ? state.nowMinute : null;
    const dayWindows = (offset === 0 ? windows : freeWindows(date, occupied))
      .map((window) => (horizonStart == null ? window : { startMinute: Math.max(window.startMinute, horizonStart), endMinute: window.endMinute }))
      .filter((window) => window.endMinute > window.startMinute);
    const dayFree = dayWindows.reduce((total, window) => total + window.endMinute - window.startMinute, 0);
    horizonFreeMinutes += dayFree;
    const busy = occupied
      .filter((entry) => Number(entry.endMinute) > Number(entry.startMinute))
      .sort((a, b) => a.startMinute - b.startMinute)
      .slice(0, 6)
      .map((entry) => `${entry.title || '安排'} ${minutesToClock(entry.startMinute)}–${minutesToClock(entry.endMinute)}`)
      .join('、');
    const windowText = dayWindows.map((window) => `${minutesToClock(window.startMinute)}–${minutesToClock(window.endMinute)}`).join('、') || '无';
    horizonLines.push(`- ${date}（${weekdayLabel(date)}）已排：${busy || '无'}；空档：${windowText}，共 ${Math.floor(dayFree / 60)} 小时 ${dayFree % 60} 分`);
  }
  const weekAgoIso = new Date(Date.now() - 7 * 86_400_000).toISOString();
  const completed = state.tasks
    .filter((task) => task.status === 'COMPLETED' && task.completed_at && String(task.completed_at) >= weekAgoIso)
    .sort((a, b) => String(b.completed_at).localeCompare(String(a.completed_at)))
    .slice(0, 8);
  const completedLines = completed.length
    ? completed.map((task) => `- ${task.name}｜完成于 ${String(task.completed_at).slice(0, 10)}`).join('\n')
    : '- 近 7 天没有完成记录';
  const overdue = state.tasks.filter((task) => isActiveTask(task) && task.due_date && task.due_date < state.today).slice(0, 8);
  const overdueLines = overdue.length
    ? overdue.map((task) => `- ${task.name}｜截止 ${task.due_date}｜${task.duration_minutes || '未估时'} 分钟`).join('\n')
    : '- 没有逾期任务';
  const focusMinutes7 = state.focusSessions
    .filter((session) => Date.parse(session.started_at || '') >= Date.now() - 7 * 86_400_000)
    .reduce((sum, session) => sum + (Number(session.net_minutes) || 0), 0);
  const focusLine = focusMinutes7
    ? `- 近 7 天专注 ${Math.floor(focusMinutes7 / 60)} 小时 ${focusMinutes7 % 60} 分钟`
    : '- 近 7 天没有专注记录';
  const horizonTotalLine = `- 未来七天可安排合计 ${Math.floor(horizonFreeMinutes / 60)} 小时 ${horizonFreeMinutes % 60} 分（今天只算此刻之后）`;
  return {
    briefing,
    taskLines,
    scheduleLines,
    routineLines,
    horizonLines,
    horizonTotalLine,
    completedLines,
    overdueLines,
    focusLine,
    windows,
    occupiedToday,
    tasksById,
  };
}

function systemPrompt() {
  return [
    '你是 Repland 的规划助手「小满」。你先是一个会理解人、会给判断的对话伙伴，其次才是把话变成日程的执行者。',
    '',
    '判断顺序（每一句都先做这一步）：',
    'A. 先判断用户是在聊天、倾诉、提问、征求建议，还是要求改动日程。不要因为句子里出现“任务/待办/日程/安排”等词就当成某一类操作。',
    'B. 聊天、情绪、解释、比较、建议、评价 → intent=query，items 留空，直接自然回答；可以有观点、取舍，也可以不同意用户。',
    'C. 需要改动日程 → 把真正的操作写进 items；信息不够时 intent=clarify，只问一个最关键的问题，不要猜着执行。',
    'D. 用户只是描述状态或想法（“今天好累”“不想学”）→ 先回应状态；给建议时基于事实，不要擅自创建或挪动任务。',
    '',
    '对话质量：',
    '- 结合“最近对话”理解“它/那个/刚才那个/第一个/还是原来的”等指代；不确定就用一句话确认。',
    '- 用户问“先做什么/来不来得及/该不该/怎么安排更好”时，结合待办、截止和空档给出明确建议和理由，而不是只罗列事实。',
    '- 事实只来自给你的清单。清单没有的完成记录、空档、他人安排不要编造；不知道就直说，并给出下一步。',
    '- say 用自然中文。闲聊、建议、解释可以到 200 字；确认执行时保持一两句话。不要机械复述，也不要说教。',
    '- 不要断言服务端最终排到几点：只有用户给出明确钟点时才在 items 里给 start；say 说“我按…来排/排期结果在下面”，不要编造具体时间。',
    '',
    '执行规则：',
    '1. 自动排期由服务端确定性排程器完成；你负责理解意图、估时、优先级、可选日期和明确钟点。',
    '2. 默认 08:00–22:00，23:00–07:00 不主动安排；今天只排此刻之后——已经过去的钟点不算空档，今天剩不下就直说并建议明天，不要为了排进去而挤到过去。用户点名了今天已经过去的钟点，说明会挪到最近的空档（落点以确认卡为准）；点名的凌晨时间照办，但不要说教。',
    '3. 时长未说明时合理估计：小事 15–30，复习/作业 45–90，写报告/准备比赛 90–180，开会按用户说的或 60；不超过 240。',
    '4. 优先级：必须/一定要/截止 → REQUIRED；重要/优先 → HIGH；普通 → MEDIUM；有空再说/不急 → LOW。',
    '5. 只有用户明确说完成、取消、做到一半、进度多少时才给 complete/cancel/progress/postpone；时间到了不等于完成。已排时段的时间已经过去，也不代表任务完成——完成与否只看待办列表里的状态。',
    '6. “待办任务”和“已排时段”是事实；不要声称用户没说过的事已经完成。',
    '7. 用户说“只记着/不要安排/先记下”时不要给 date/start，action=create。',
    '8. 用户只说新建/记下一个任务、没给日期时间时，不要 clarify 追问；action=create 不带 date/start 先记下，say 里再问要不要排时间。即使 say 里要问排期，intent 也必须是 plan 且带上 items，不能用 clarify。',
    '9. 任务名只取用户给的名字（“建立一个任务，名字叫吃饭”→ name="吃饭"），不要把指令词带进 name。',
    '10. 任务已在待办列表里时用它的 taskId + action=schedule，不要新建重复任务。',
    '11. 用户只给时间没说任务名时，用 clarify 问清是哪一个，不要替用户挑。',
    '12. 用户在确认前继续说，通常是在改上一份草案；以最新一句为准，只输出修改后的完整结果。',
    '13. 改已排任务（改到/挪到/换到/调整到/提前到/推迟到）必须用该任务 taskId、action=schedule、新 date/start、move:true；服务端会移除原时段，不要 create，也不要换任务名。',
    '14. 只有确实要新增一段独立时间（“再复习一次”）时才不带 move，服务端会保留原时段并追加新时段。',
    '15. 用户嫌排得太紧、要“排开点/松一点/别排那么紧”时，intent=spread，items 留空，可给 gapMinutes（10–120，默认 30）；这不是新任务，不要创建名字叫“排开”的任务，也不要为了留空删任务。',
    '16. 用户要的是每周重复的固定占用（“排成日常事项”“每天这个时间”“每个工作日下午”“每周三晚上”）时，action=routine：name 写事项名，weekdays 写清（每天=[1,2,3,4,5,6,7]，工作日=[1,2,3,4,5]，周末=[6,7]，每周三=[3]），durationMinutes 写时长。用户点名了钟点才写 start（或 end）；说“看着空档/合适时间/你定/你安排”或没给钟点时，不要自己编时间，省掉 start 和 end，只给 durationMinutes，服务端会按那几天的共同空档挑一个时段。这时 say 只能说“按那几天的共同空档挑一个固定时段”，挑中的钟点由确认卡告诉你；不要写成“每天自动挑/不固定钟点/每天临时看”，那是每天都要重排的意思，和每周同一时间不符，也不要在 say 里报任何钟点。只锁定某一天就给 date、不给 weekdays。绝不能把“每天”只排成一天，也不能在 say 里承诺“每天都会自动排上”而 items 只有单日。',
    '17. 改固定安排的时间或日子，用 action=routine + replace=true，只提交一条（weekdays 写改后的全部星期，start/end 写改后的时段），不要 remove+create 两条。取消固定安排才用 remove=true，并用固定安排里的原名；只取消其中几天就给 weekdays。action=routine 登记固定占用，不创建待办任务。',
    '18. 固定安排不能跨天：end 必须晚于 start，最多到 24:00。拿不准就不写 end，只写 durationMinutes。say 里不要报一个 items 里没有的钟点。',
    '',
    '只输出一个严格 JSON 对象，不要 Markdown 围栏或前后解释。',
    'JSON 结构：',
    '{"say":"用自然中文回复","intent":"plan|status|query|clarify|update|spread","gapMinutes":30,"items":[{"name":"任务名","taskId":"已有任务 id，可选","durationMinutes":60,"end":"22:00","weekdays":[1,2,3,4,5,6,7],"remove":false,"replace":false,"priority":"REQUIRED|HIGH|MEDIUM|LOW","category":"COURSE|EXTRACURRICULAR|OFFICE|LEISURE","date":"YYYY-MM-DD","start":"HH:mm","action":"create|schedule|routine|complete|progress|cancel|postpone|update","move":true,"progress":50,"dueDate":"YYYY-MM-DD","plannedDate":"YYYY-MM-DD"}]}',
    'intent=query 时 items 留空，say 直接回答；intent=clarify 时 items 留空，say 只问一个问题；intent=spread 时 items 留空。',
  ].join('\n');
}

function extractJson(text) {
  if (typeof text !== 'string') return null;
  let value = text.trim();
  if (value.startsWith('```')) {
    value = value.replace(/^```[a-zA-Z]*\s*/, '').replace(/```$/, '').trim();
  }
  const start = value.indexOf('{');
  const end = value.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(value.slice(start, end + 1));
  } catch {
    return null;
  }
}

async function callAgnes({ system, user }) {
  const apiKey = aiKey();
  if (!apiKey) fail('service_not_configured', 503);
  const configured = env('AGNES_BASE_URL').trim();
  // The provider runs two interchangeable regional hosts; a key issued for one is
  // rejected by the other, so only the default setup tries both.
  const baseUrls = configured ? [configured] : [DEFAULT_BASE_URL, ALTERNATE_BASE_URL];
  const model = env('AGNES_MODEL').trim() || DEFAULT_MODEL;
  let lastStatus = 0;
  for (const candidate of baseUrls) {
    const baseUrl = candidate.replace(/\/+$/, '');
    let response;
    try {
      response = await fetch(`${baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify({
          model,
          temperature: 0.1,
          messages: [
            { role: 'system', content: system },
            { role: 'user', content: user },
          ],
        }),
        signal: AbortSignal.timeout(45_000),
      });
    } catch {
      fail('ai_unreachable', 503);
    }
    if (response.ok) {
      let body;
      try {
        body = await response.json();
      } catch {
        fail('ai_invalid_response', 502);
      }
      const content = body?.choices?.[0]?.message?.content;
      return typeof content === 'string' ? content : Array.isArray(content) ? content.map((part) => part?.text || '').join('') : '';
    }
    lastStatus = response.status;
    if (response.status === 429) fail('ai_rate_limited', 429);
    if (response.status !== 401 && response.status !== 403) break;
  }
  if (lastStatus === 401 || lastStatus === 403) fail('ai_auth_failed', 502, { upstream: lastStatus });
  fail('ai_unavailable', 502, { upstream: lastStatus });
}

function findTask(tasks, { taskId, name }) {
  if (taskId) {
    const byId = tasks.find((task) => task.id === taskId);
    if (byId) return byId;
  }
  if (!name) return null;
  const needle = name.toLowerCase().replace(/\s+/g, '');
  const matches = tasks.filter((task) => {
    const haystack = task.name.toLowerCase().replace(/\s+/g, '');
    return haystack === needle || haystack.includes(needle) || needle.includes(haystack);
  });
  const active = matches.filter(isActiveTask);
  return (active.length ? active : matches)[0] || null;
}

const STATUS_WORDS = {
  complete: /(完成|做完|搞定|弄完了?|弄好了?|写完了?|写好了?|学完了?|看完了?|吃完(?:饭)?了?|吃过了?|读完了?|交了|提交了|结束了|已经做了|已做|干完|处理完|改好了?|画完了?|背完了?|练完了?|上完课)/,
  cancel: /(取消|不做了|删掉|别做了|不用做了|去掉)/,
  postpone: /(推迟|延后|往后挪|顺延|挪后)/,
  progress: /(进度|做到|完成了一半|一半了|\d+\s*%)/,
};
// Same vocabulary, but global so it can be stripped out of a task name.
const STATUS_STRIP = /(完成|做完|搞定|弄完了?|弄好了?|写完了?|写好了?|学完了?|看完了?|吃完(?:饭)?了?|吃过了?|读完了?|交了|提交了|结束了|已经做了|已做|干完|处理完|改好了?|画完了?|背完了?|练完了?|上完课|取消|不做了|删掉|别做了|不用做了|去掉|推迟|延后|往后挪|顺延|挪后|进度|做到|完成了一半|一半了|\d+\s*%)/g;
// "明天写完作业" is a plan, not a finished thing. Future wording cancels a
// past-tense reading unless the sentence also says it already happened.
const FUTURE_CUE = /(明天|后天|大后天|下周|下星期|下礼拜|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]|要|打算|准备|帮我排|安排|排进|排到)/;
const PAST_CUE = /(已经|刚刚|刚|昨天|前天)/;

function inferAction(message) {
  if (STATUS_WORDS.complete.test(message)) return 'complete';
  if (STATUS_WORDS.cancel.test(message)) return 'cancel';
  if (STATUS_WORDS.postpone.test(message)) return 'postpone';
  if (STATUS_WORDS.progress.test(message)) return 'progress';
  return null;
}

const RESCHEDULE_PATTERN =
  /(改到|改成|改在|改一下|换个时间|换到|换成|挪到|挪成|调到|调成|移到|移成|调整到|提前到|延后到|推迟到|时间改成|重新安排|重新排)/;

function isRescheduleMessage(message) {
  return RESCHEDULE_PATTERN.test(String(message || ''));
}

const SPREAD_PATTERN = /(排开|散开|松开|松一点|宽松|太紧|太挤|别排那么紧|别排这么紧|隔开|拉开|分散|留点空|留出空|空一点|喘口气|中间空)/;

/**
 * "排开点" is an instruction about the day's shape, not a new task. It has to be
 * recognised before the planner/model so it can never become a task named 排开.
 */
function isSpreadMessage(message) {
  const text = String(message || '').replace(/\s+/g, '');
  return text.length > 0 && text.length <= 24 && SPREAD_PATTERN.test(text);
}

// A sentence that names the task to create leaves no room for interpretation.
// The model still owns the wording, but this phrase must never be answered
// with a question when the task itself was already given.
const EXPLICIT_CREATE_PATTERN = /(?:新建|建立|创建|添加|新增|建)(?:一个|个)?(?:新的?)?(?:任务|待办|日程)|名字(?:叫|是|为)/;

function isExplicitCreateMessage(message) {
  return EXPLICIT_CREATE_PATTERN.test(String(message || ''));
}

function spreadGapMinutes(message) {
  const text = String(message || '');
  if (/一个半小时|一小时半|一个小时半/.test(text)) return 90;
  if (/半小时|半个小时|半钟头/.test(text)) return 30;
  const minute = text.match(/(?:空|隔|间隔|留)\s*(\d{1,3}|[一二两三四五六七八九十]{1,3})\s*(?:分钟|分)/);
  const minutes = minute ? chineseNumberValue(minute[1]) : null;
  if (minutes != null) return Math.max(10, Math.min(120, Math.round(minutes)));
  const hour = text.match(/(?:空|隔|间隔|留)\s*(\d|[一二两])\s*个?\s*(?:小时|钟头)/);
  const hours = hour ? Number(hour[1]) || chineseNumberValue(hour[1]) : null;
  if (hours != null) return Math.max(10, Math.min(120, Math.round(hours * 60)));
  return 30;
}

function normalizeAiItems(payload, { tasks, activeDate, message = '' }) {
  const rawItems = Array.isArray(payload?.items) ? payload.items.slice(0, 10) : [];
  const intent = ['plan', 'status', 'query', 'clarify', 'update', 'spread'].includes(payload?.intent) ? payload.intent : 'plan';
  const rawGap = Number(payload?.gapMinutes);
  const gapMinutes = Number.isFinite(rawGap) ? Math.max(10, Math.min(120, Math.round(rawGap))) : null;
  const moving = isRescheduleMessage(message);
  const futurePlan = FUTURE_CUE.test(message) && !PAST_CUE.test(message);
  const items = [];
  let unsupportedStatus = null;
  for (const raw of rawItems) {
    if (!raw || typeof raw !== 'object') continue;
    // A routine is a fixed weekly commitment, never a task; matching it against the
    // task list would turn "每天玩游戏" into a task with today's taskId.
    const routine = raw.action === 'routine';
    let existing = routine
      ? null
      : findTask(tasks, { taskId: typeof raw.taskId === 'string' ? raw.taskId : null, name: cleanText(raw.name, 80) });
    if (!existing && !routine && moving && raw.name) {
      // Models sometimes echo the whole instruction into "name"; the verbs and
      // time words have to come off before the task can be recognised.
      existing = findTask(tasks, { name: stripTaskWords(cleanText(raw.name, 80)) });
    }
    let action = ['create', 'schedule', 'routine', 'complete', 'progress', 'cancel', 'postpone', 'update'].includes(raw.action)
      ? raw.action
      : intent === 'status'
        ? 'complete'
        : existing
          ? 'schedule'
          : 'create';
    const hasWhen = raw.date != null || raw.start != null;
    // "把开会改到三点" spoken about a task that already has a slot must move it;
    // creating a second task with the same name is the failure this guards against.
    const move = !routine && Boolean(raw.move === true || (existing && moving && hasWhen && !['complete', 'progress', 'cancel', 'postpone', 'update'].includes(action)));
    if (move) action = 'schedule';
    // A status write needs the user's own words. Models sometimes turn an unclear
    // follow-up like "吃饭，吃一个消失" into "记为完成"; never let that reach a draft.
    if (STATUS_WORDS[action] && !STATUS_WORDS[action].test(message)) unsupportedStatus = action;
    else if ((action === 'complete' || action === 'progress') && futurePlan) unsupportedStatus = action;
    const name = cleanText(raw.name, 80) || existing?.name || '';
    if (!name && !existing) continue;
    if (action === 'routine' && !name) continue;
    const progressNumber = Number(raw.progress);
    const weekdays = routine
      ? (normalizeWeekdays(raw.weekdays ?? raw.weekday).length
          ? normalizeWeekdays(raw.weekdays ?? raw.weekday)
          : weekdaysFromRepeat(raw).length
            ? weekdaysFromRepeat(raw)
            : weekdaysFromMessage(message))
      : [];
    items.push({
      name,
      taskId: existing?.id || null,
      action,
      move,
      durationMinutes: durationOf(raw.durationMinutes, existing?.duration_minutes || null),
      priority: normalizePriority(raw.priority, existing?.priority || 'MEDIUM'),
      category: normalizeCategory(raw.category, existing?.category || 'UNSPECIFIED'),
      // A recurring routine must not collapse into a single day because the model also
      // echoed a date; date only means a one-off lock when no weekdays were given.
      date: routine && weekdays.length ? null : raw.date == null ? null : normalizeDate(raw.date, null),
      start: normalizeClock(raw.start),
      ...(routine
        ? {
            weekdays,
            end: normalizeClock(raw.end, { allowEnd: true }),
            remove: raw.remove === true,
            replace: raw.replace === true,
            blockKind: ['CLASS', 'FIXED', 'REST'].includes(String(raw.blockKind || '').toUpperCase())
              ? String(raw.blockKind).toUpperCase()
              : 'FIXED',
          }
        : {}),
      progress: Number.isFinite(progressNumber) ? Math.max(0, Math.min(100, Math.round(progressNumber))) : null,
      actualMinutes: durationOf(raw.actualMinutes, null),
      dueDate: normalizeDate(raw.dueDate, existing?.due_date || null),
      plannedDate: normalizeDate(raw.plannedDate, existing?.planned_date || null),
      note: cleanText(raw.note, 500),
    });
  }
  if (unsupportedStatus) {
    const name = cleanText(rawItems[0]?.name, 80) || '';
    const label = name ? `「${name}」` : '这件事';
    const ask = {
      complete: `要把${label}记为完成吗？`,
      cancel: `要取消${label}吗？`,
      postpone: `要把${label}往后挪吗？`,
      progress: `${label}现在做到多少了？`,
    }[unsupportedStatus];
    return {
      intent: 'clarify',
      items: [],
      // A safety block is not model hesitation; local rules must not override it.
      blocked: true,
      say: ask ? `没听准：${ask}` : '没听准你想做什么，可以再说一句。',
    };
  }
  return { intent, items, say: cleanText(payload?.say, 300), gapMinutes };
}

function localDateWords(message, today) {
  if (/大后天/.test(message)) return addDays(today, 3);
  if (/后天/.test(message)) return addDays(today, 2);
  if (/明天|明日/.test(message)) return addDays(today, 1);
  if (/今天|今日|今晚|今早|今下午/.test(message)) return today;
  const weekdayMatch = message.match(/(?:下|本|这)?(?:周|星期|礼拜)([一二三四五六日天])/);
  if (weekdayMatch) {
    const target = '一二三四五六日'.indexOf(weekdayMatch[1].replace('天', '日')) + 1;
    if (target > 0) {
      const current = Number(new Date(`${today}T00:00:00Z`).getUTCDay() || 7);
      const delta = (target - current + 7) % 7;
      if (/下周|下星期|下礼拜/.test(message)) {
        const nextMonday = ((8 - current) % 7) || 7;
        return addDays(today, nextMonday + target - 1);
      }
      return addDays(today, delta);
    }
  }
  return null;
}

function chineseNumberValue(text) {
  const digits = { 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9 };
  if (!text) return null;
  if (/^\d+(?:\.\d+)?$/.test(text)) return Number(text);
  if (text === '十') return 10;
  if (/^十[一二三四五六七八九]$/.test(text)) return 10 + digits[text[1]];
  if (/^[一二两三四五六七八九]十$/.test(text)) return digits[text[0]] * 10;
  if (/^[一二两三四五六七八九]十[一二三四五六七八九]$/.test(text)) return digits[text[0]] * 10 + digits[text[2]];
  if (/^[一二两三四五六七八九]$/.test(text)) return digits[text];
  return null;
}

function localTimeFromMessage(message) {
  const prefix = /下午|晚上|傍晚/.test(message) ? 'pm' : /中午/.test(message) ? 'noon' : /上午|早上|早晨/.test(message) ? 'am' : '';
  let match = message.match(/(\d{1,2})[:：](\d{2})/);
  if (match) {
    let hour = Number(match[1]);
    const minute = Number(match[2]);
    if ((prefix === 'pm' || prefix === 'noon') && hour >= 1 && hour <= 11) hour += 12;
    if (!prefix && hour >= 7 && hour <= 12 && /晚上/.test(message)) hour += 12;
    return hour * 60 + minute;
  }
  match = message.match(/([一二两三四五六七八九十]{1,2})\s*(?:点|時|时)(半|一刻|三刻)?/);
  if (match) {
    let hour = chineseNumberValue(match[1]);
    const minute = match[2] === '半' ? 30 : match[2] === '一刻' ? 15 : match[2] === '三刻' ? 45 : 0;
    if (hour != null) {
      if ((prefix === 'pm' || prefix === 'noon') && hour >= 1 && hour <= 11) hour += 12;
      if (hour <= 23) return hour * 60 + minute;
    }
  }
  match = message.match(/(\d{1,2})\s*(?:点|時|时)(半|\d{1,2}\s*分?)?/);
  if (match) {
    let hour = Number(match[1]);
    const minute = match[2] === '半' ? 30 : match[2] ? Number(String(match[2]).replace(/\D/g, '')) || 0 : 0;
    if ((prefix === 'pm' || prefix === 'noon') && hour >= 1 && hour <= 11) hour += 12;
    if (!prefix && hour >= 7 && hour <= 12 && /晚上/.test(message)) hour += 12;
    if (hour <= 23 && minute <= 59) return hour * 60 + minute;
  }
  return null;
}

/**
 * When the assistant asked "13:00 排哪个任务？", a short next reply like "吃饭"
 * must inherit that time even on the no-model fallback path. The question itself
 * is the only place the time was said, so read it back from the last turn.
 */
function carryClarification(chat, message) {
  if (!message || localTimeFromMessage(message) != null) return null;
  const lastAssistant = [...(chat || [])].reverse().find((entry) => entry.role === 'assistant');
  if (!lastAssistant) return null;
  const text = String(lastAssistant.content || '');
  if (!/(哪个任务|哪件事|排哪|安排哪|哪一项|做什么)/.test(text)) return null;
  const match = text.match(/(\d{1,2})[:：](\d{2})/);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (!(hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59)) return null;
  return { clock: `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}` };
}

function localDuration(message) {
  if (/一个半小时|一个小时半|一小时半/.test(message)) return 90;
  if (/两个半小时|两个小时半|两小时半/.test(message)) return 150;
  if (/半小时|半个小时/.test(message)) return 30;
  // 先抹掉钟点：不然「八点二十分」里的「二十分」会被读成 20 分钟。
  const rest = String(message || '')
    .replace(/(\d{1,2})[:：](\d{2})/g, ' ')
    .replace(CLOCK_WORD_PATTERN, ' ');
  let match = rest.match(/(\d+(?:\.\d+)?|[一二两三四五六七八九十]{1,3})\s*(?:分钟|分|min)/i);
  if (match) {
    const value = chineseNumberValue(match[1]);
    if (value != null) return durationOf(value, 60);
  }
  match = rest.match(/(\d+(?:\.\d+)?|[一二两三四五六七八九十]{1,3})\s*(?:个?小时|钟头|h)/i);
  if (match) {
    const value = chineseNumberValue(match[1]);
    if (value != null) return durationOf(value * 60, 60);
  }
  return null;
}

function stripTaskWords(text) {
  return text
    .replace(/(只记着|先记下|不要安排|别安排|记一下|记着|帮我|我想|我要|需要|安排|排一下|排进|排在|排个|排上|排给|排到|今天|明天|后天|大后天|今晚|今早|今下午|上午|下午|晚上|中午|早上|下周|本周|这周|下星期|本星期|这星期|下礼拜|本礼拜|这礼拜|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天])/g, ' ')
    .replace(/(改到|改成|改在|改一下|换个时间|换到|换成|挪到|挪成|调到|调成|移到|移成|调整到|提前到|延后到|推迟到|时间改成|重新安排|重新排)/g, ' ')
    .replace(/(排开|散开|松开|宽松|太紧|太挤|隔开|拉开|分散|留点空|留出空|空一点)/g, ' ')
    .replace(/一个半小时|一个小时半|一小时半|两个半小时|两个小时半|两小时半|半个小时|半小时/g, ' ')
    .replace(/(\d+(?:\.\d+)?|[一二两三四五六七八九十]{1,3})\s*(?:分钟|分|min|个?小时|钟头|h)/gi, ' ')
    .replace(/([一二两三四五六七八九十]{1,2})\s*(?:点|時|时)(半|一刻|三刻)?/g, ' ')
    .replace(/(\d{1,2})\s*(?:点|時|时)(半|\d{1,2}\s*分?)?/g, ' ')
    .replace(/(\d{1,2})[:：]\d{2}/g, ' ')
    .replace(/大概|大约|左右|差不多|之前|以前|尽量|争取/g, ' ')
    .replace(/[，,。.!！?？:：；;]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * "帮我排在今天中午一点" names only a time. The model is not allowed to pick a
 * task from the list for that, so catch it before the model gets a chance to.
 */
function timeOnlyMessage(message) {
  if (localTimeFromMessage(message) == null) return false;
  if (inferAction(message) || isRescheduleMessage(message) || isSpreadMessage(message)) return false;
  const residue = stripTaskWords(message)
    .replace(STATUS_STRIP, ' ')
    .replace(/(到|至|之间|开始|结束|左右|吧|呢|啊|的话)/g, ' ')
    .replace(/[，,。.!！?？:：；;、]/g, ' ')
    .replace(/\s+/g, '');
  return residue.length === 0;
}

function localPlanFromMessage(message, { tasks, segments = [], activeDate }) {
  const text = message.trim();
  if (!text) return null;
  const today = activeDate;
  if (isRescheduleMessage(text)) {
    const namePart = stripTaskWords(
      text.replace(/(把|请|帮我把|将|给我)/g, ' ').replace(/(这个|那个|那件事|一下|时间|日程)/g, ' '),
    ).replace(/的/g, ' ');
    const task = namePart ? findTask(tasks, { name: namePart }) : null;
    const date = localDateWords(text, today);
    const start = localTimeFromMessage(text);
    if (task && (date != null || start != null)) {
      const current = segments
        .filter((segment) => segment.task_id === task.id && segment.date >= today)
        .sort((a, b) => String(a.date).localeCompare(String(b.date)) || a.start_minute - b.start_minute)[0];
      return {
        intent: 'plan',
        say: '',
        items: [
          {
            name: task.name,
            taskId: task.id,
            action: 'schedule',
            move: true,
            durationMinutes: task.duration_minutes,
            priority: task.priority,
            category: task.category,
            date: date || current?.date || today,
            start: start != null ? start : current ? Number(current.start_minute) : null,
            progress: null,
            dueDate: task.due_date,
            plannedDate: task.planned_date,
            note: '',
          },
        ],
      };
    }
  }
  const actionHint = inferAction(text);
  const futurePlan = FUTURE_CUE.test(text) && !PAST_CUE.test(text);
  if (actionHint && !((actionHint === 'complete' || actionHint === 'progress') && futurePlan)) {
    const progressMatch = text.match(/(\d{1,3})\s*%/);
    const namePart = text
      .replace(STATUS_STRIP, ' ')
      .replace(/[，,。.!！?？:：；;]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    const task = findTask(tasks, { name: namePart });
    if (!task) return null;
    return {
      intent: actionHint === 'complete' || actionHint === 'progress' || actionHint === 'cancel' || actionHint === 'postpone' ? 'status' : 'plan',
      say: '',
      items: [
        {
          name: task.name,
          taskId: task.id,
          action: actionHint,
          durationMinutes: task.duration_minutes,
          priority: task.priority,
          category: task.category,
          date: today,
          start: null,
          progress: progressMatch ? Number(progressMatch[1]) : null,
          dueDate: task.due_date,
          plannedDate: task.planned_date,
          note: '',
        },
      ],
    };
  }
  const saveOnly = /(只记着|记一下|记着|先记下|不要安排|别安排)/.test(text);
  // "帮我建立一个任务，名字叫吃饭" is a creation request, not a list query.
  const explicitCreate = isExplicitCreateMessage(text);
  let explicitName = '';
  if (explicitCreate) {
    const named = text.match(/名字(?:叫|是|为)\s*[:：]?\s*([^\s，。！？,.!?:：]{1,20})/);
    if (named) explicitName = named[1];
    if (!explicitName) {
      const after = text.match(/(?:任务|待办|日程)\s*(?:叫|是|为|[:：])?\s*(.+)$/);
      if (after) explicitName = stripTaskWords(after[1]).slice(0, 20);
    }
    if (!explicitName) return null;
  }
  const duration = localDuration(text);
  const date = localDateWords(text, today);
  const start = saveOnly ? null : localTimeFromMessage(text);
  const cleaned = stripTaskWords(text);
  const name = explicitName || (cleaned.length > 0 ? cleaned.slice(0, 80) : '');
  if (!name) return null;
  // "吃饭，13:00" — or a one-word answer to "13:00 排哪个任务？" — points at a task
  // that already exists. Scheduling it beats creating a second task with the same name.
  const existing = findTask(tasks, { name });
  if (existing && !saveOnly && (date != null || start != null)) {
    return {
      intent: 'plan',
      say: '',
      items: [
        {
          name: existing.name,
          taskId: existing.id,
          action: 'schedule',
          move: true,
          durationMinutes: existing.duration_minutes,
          priority: existing.priority,
          category: existing.category,
          date: date || today,
          start,
          progress: null,
          dueDate: existing.due_date,
          plannedDate: existing.planned_date,
          note: '',
        },
      ],
    };
  }
  if (!explicitCreate && !/(复习|学习|写|做|看|读|背|整理|准备|交|买|联系|开会|报告|作业|论文|实验|比赛|练习|预习|刷题|复习)/.test(cleaned) && !duration && !date) {
    return null;
  }
  const scheduleDate = saveOnly || (explicitCreate && !date && start == null) ? null : date || today;
  return {
    intent: 'plan',
    say: '',
    items: [
      {
        name,
        taskId: null,
        action: 'create',
        durationMinutes: duration || 60,
        priority: /(必须|一定要|截止|重要|优先)/.test(text) ? (/必须|一定要|截止/.test(text) ? 'REQUIRED' : 'HIGH') : 'MEDIUM',
        category: /(课|考试|作业|论文|复习|学习)/.test(text) ? 'COURSE' : /(比赛|社团)/.test(text) ? 'EXTRACURRICULAR' : /(开会|值班|工作|实习)/.test(text) ? 'OFFICE' : 'UNSPECIFIED',
        date: scheduleDate,
        start: saveOnly ? null : start,
        progress: null,
        dueDate: null,
        plannedDate: null,
        note: '',
      },
    ],
  };
}

function answerLocalQuery(message, state, activeDate) {
  const tasksById = new Map(state.tasks.map((task) => [task.id, task]));
  const occupied = occupiedForDate(activeDate, { segments: state.segments, fixedBlocks: state.fixedBlocks, tasksById });
  const windowOptions = activeDate === state.today ? { fromMinute: state.nowMinute } : undefined;
  if (/(还剩|还有|剩).*(时间|空|小时|分钟)|有多少.*(时间|空)|空档|排得下/.test(message)) {
    const minutes = freeMinutes(activeDate, occupied, windowOptions);
    const windows = freeWindows(activeDate, occupied, windowOptions);
    const list = windows.map((window) => `${minutesToClock(window.startMinute)}–${minutesToClock(window.endMinute)}`).join('、');
    return `今天还有 ${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟的空档${list ? `：${list}` : ''}。`;
  }
  if (/还有(什么|哪些|多少).*(没|要|需要|待).*(做|完成|交|办)/.test(message)) {
    const active = state.tasks.filter(isActiveTask);
    if (!active.length) return '待办清单现在是空的。';
    return `还没做的有 ${active.length} 件：${active.slice(0, 6).map((task) => task.name).join('、')}${active.length > 6 ? ' 等' : ''}。`;
  }
  if (/好累|不想动|想休息|累死/.test(message)) {
    return '那就先歇一会儿。要我把今天剩下的事往后挪，还是先留半小时空档？';
  }
  if (/先做|先干|哪个先|哪件事先|先安排/.test(message)) {
    const active = state.tasks.filter(isActiveTask);
    const pick = active
      .slice()
      .sort((a, b) => String(a.due_date || '9999-12-31').localeCompare(String(b.due_date || '9999-12-31')))[0];
    return pick ? `先做「${pick.name}」吧${pick.due_date ? `，它 ${pick.due_date} 截止` : ''}。` : '待办是空的，先加一件吧。';
  }
  if (/(今天|今日).*(安排|日程|什么|干嘛|干啥|做啥)|现在.*(干|做)/.test(message)) {
    const fixed = occupied.filter((entry) => entry.kind !== 'TASK');
    const scheduled = occupied.filter((entry) => entry.kind === 'TASK');
    const parts = [];
    if (fixed.length) parts.push(`固定安排 ${fixed.map((entry) => `${entry.title} ${minutesToClock(entry.startMinute)}–${minutesToClock(entry.endMinute)}`).join('、')}`);
    if (scheduled.length) parts.push(`已排 ${scheduled.map((entry) => `${entry.title} ${minutesToClock(entry.startMinute)}–${minutesToClock(entry.endMinute)}`).join('、')}`);
    if (!parts.length) return '今天还没有具体安排。想让我先放哪件事进去？';
    return `今天：${parts.join('；')}。`;
  }
  const wantsCreate = /(新建|建立|创建|添加|新增|加一个|加个|记一下|记下|记着|安排)/.test(message);
  if (!wantsCreate && /(任务|待办|清单|还有啥|有哪些)/.test(message)) {
    const active = state.tasks.filter(isActiveTask);
    if (!active.length) return '待办清单现在是空的。';
    return `现在有 ${active.length} 件待办：${active.slice(0, 6).map((task) => `${task.name}${task.duration_minutes ? `（${task.duration_minutes} 分钟）` : ''}`).join('、')}${active.length > 6 ? ' 等' : ''}。`;
  }
  return null;
}

/**
 * While a draft is waiting for confirmation the user may simply keep talking.
 * A short "算了" drops it; a time/date/duration mention edits that same draft
 * instead of starting a second, competing proposal.
 */
function draftRevision(message, pending, { activeDate }) {
  const items = (pending?.payload?.items || []).filter((item) => item.kind === 'create' || item.kind === 'schedule');
  if (!items.length) return null;
  const text = String(message || '').trim();
  if (/(算了|不要了|先不|不排了|别排了|取消这次|不用了)/.test(text) && text.replace(/\s+/g, '').length <= 12) {
    return { kind: 'drop' };
  }
  const compact = text.replace(/\s+/g, '');
  const named = items.find((item) => item.name && compact.includes(String(item.name).replace(/\s+/g, '')));
  const residue = stripTaskWords(text).replace(/[的了呢吧啊，。！？!?,.]/g, '').trim();
  const target = named || (items.length === 1 && residue.length <= 3 ? items[0] : null);
  if (!target) return null;
  const date = localDateWords(text, activeDate);
  const start = localTimeFromMessage(text);
  const duration = localDuration(text);
  if (date == null && start == null && duration == null) return null;
  return {
    kind: 'revise',
    target,
    date: date || target.date || target.placement?.date || activeDate,
    start: start != null ? start : (target.placement?.startMinute ?? null),
    durationMinutes: duration || target.durationMinutes || null,
  };
}

function draftDigest(pending) {
  const items = pending?.payload?.items || [];
  if (!items.length) return '';
  return items
    .map((item) => {
      if (item.action === 'routine') {
        return `- ${item.name}｜${item.remove ? '取消固定安排' : '日常固定安排'}｜${routineWhenText(item) || '待定'}`;
      }
      const when = item.placement
        ? `${item.date || item.placement.date} ${minutesToClock(item.placement.startMinute)}–${minutesToClock(item.placement.endMinute)}`
        : '暂未排时间';
      return `- ${item.name}｜${item.action === 'create' ? '新建' : '已有任务'}｜${when}`;
    })
    .join('\n');
}

const DRAFT_TTL_MS = 12 * 60 * 60 * 1000;

function draftExpired(pending) {
  const created = Date.parse(pending?.created_at || '');
  return Number.isFinite(created) && Date.now() - created > DRAFT_TTL_MS;
}

function chatConfirmIntent(message) {
  const text = String(message || '').replace(/[\s，。！？!?,.~～]/g, '');
  if (!text || text.length > 10) return false;
  if (/[0-9点时半分钟小时天号周明后今上下晚早改换挪]/.test(text)) return false;
  return /^(确认|确定|执行|就按这个|就按这个来|按这个来|按你说的来|就这样|就这么办|写进去|安排吧|ok|okay)$/i.test(text);
}

function chatCancelIntent(message) {
  const text = String(message || '').replace(/[\s，。！？!?,.~～]/g, '');
  return text.length <= 10 && /^(取消|算了|先不|先不要|不用了|不要了|不排了|别排了|撤回|再想想|不了)$/.test(text);
}

function proposalSummary(items, source) {
  const parts = [];
  for (const item of items) {
    if (item.action === 'routine') {
      if (item.remove) parts.push(`取消「${item.name}」的固定安排${item.weekdays?.length ? `（${weekdayRangeText(item.weekdays)}）` : ''}`);
      else parts.push(`把「${item.name}」设为${routineWhenText(item)} 的固定安排${item.replace ? `（替换旧的 ${item.blockIds?.length || 0} 段）` : ''}`);
    } else if (item.action === 'complete') parts.push(`把「${item.name}」记为完成`);
    else if (item.action === 'cancel') parts.push(`取消「${item.name}」`);
    else if (item.action === 'progress') parts.push(item.progress == null ? `把「${item.name}」记为未完成` : `把「${item.name}」更新为 ${item.progress}%`);
    else if (item.action === 'postpone') parts.push(`把「${item.name}」往后推`);
    else if (item.placement) {
      const when = `${item.date} ${minutesToClock(item.placement.startMinute)}–${minutesToClock(item.placement.endMinute)}${item.placement.nestedUnderTitle ? `（挂进「${item.placement.nestedUnderTitle}」）` : ''}`;
      if (item.move && item.moveFrom) parts.push(`把「${item.name}」改到 ${when}`);
      else if (item.move) parts.push(`把「${item.name}」排到 ${when}`);
      else if (item.taskId && item.action === 'schedule') parts.push(`把「${item.name}」放到 ${when}`);
      else parts.push(`安排「${item.name}」到 ${when}`);
    } else if (item.action === 'create') parts.push(`记下「${item.name}」${item.durationMinutes ? `（${item.durationMinutes} 分钟）` : ''}，暂未排时间`);
    else if (item.action === 'schedule') parts.push(`把「${item.name}」排进日程`);
  }
  if (!parts.length) return source === 'local' ? '这次没有需要写入的改动。' : '';
  return `${parts.slice(0, 4).join('；')}${parts.length > 4 ? ` 等 ${parts.length} 项` : ''}。`;
}

/** 只有"今天"有过去：别的日子整天都还在前面。 */
function earliestStartOn(date, state) {
  return date === state.today ? earliestPlacementMinute(state.nowMinute) : null;
}

async function buildProposal(db, user, state, { activeDate, intent, items, say, source, gapMinutes, message = '' }) {
  const tasksById = new Map(state.tasks.map((task) => [task.id, task]));
  const prepared = [];
  const schedulingGroups = new Map();
  const unplaced = [];
  let adjustedPinned = false;
  let pinnedPast = false;

  // "remove the old routine + create the new one" is one edit. Merge the pair before
  // building anything: a missing old row must not abort the edit the user asked for.
  const routineCreates = new Map();
  for (const item of items) {
    if (item?.action === 'routine' && item.remove !== true) {
      const name = cleanText(item.name, 80);
      if (name) routineCreates.set(name, item);
    }
  }
  const queued = [];
  for (const item of items) {
    if (!item) continue;
    if (item.action !== 'routine' || item.remove !== true) {
      queued.push(item);
      continue;
    }
    const name = cleanText(item.name, 80);
    const twin = routineCreates.get(name);
    if (twin) {
      twin.replace = true;
      if (!normalizeWeekdays(twin.weekdays ?? twin.weekday).length && normalizeWeekdays(item.weekdays ?? item.weekday).length) {
        twin.weekdays = normalizeWeekdays(item.weekdays ?? item.weekday);
      }
      continue;
    }
    // A single remove that really matches is a normal cancellation; only a miss
    // needs the "confused edit vs. cancel" recovery below.
    const matchesExisting = state.fixedBlocks.some((block) => {
      if (String(block.title || '').trim() !== name) return false;
      const given = normalizeWeekdays(item.weekdays ?? item.weekday);
      if (!given.length) return true;
      return given.includes(block.specific_date ? weekdayOf(block.specific_date) : Number(block.weekday));
    });
    const cancelling = /(取消|删掉|去掉|关掉|停了|不要了|不用了|别排)/.test(message);
    const hasTarget = item.start != null || item.end != null || item.durationMinutes != null
      || normalizeWeekdays(item.weekdays ?? item.weekday).length > 0
      || item.date != null;
    const editing = /(改成|改到|改为|改在|换成|换到|变为|变成|调到|挪到|移到|调整|重新排)/.test(message);
    if (editing && !cancelling) {
      if (!hasTarget) return { error: `把「${name}」改成什么时间？说个钟点或时长，我来换。` };
      // "把 X 改成…" with a confused remove is an edit, not a deletion.
      queued.push({ ...item, remove: false, replace: true });
      continue;
    }
    if (matchesExisting) {
      queued.push(item);
      continue;
    }
    if (!cancelling && hasTarget) {
      // A "remove" that matches nothing, in a sentence that is not a cancellation,
      // is a confused update; finish the update instead of bouncing the user.
      queued.push({ ...item, remove: false });
      continue;
    }
    const existing = describeFixedRoutines(state);
    if (cancelling) {
      return {
        error: existing
          ? `没找到「${name}」这项固定安排。现在的固定安排有：${existing}。要取消哪一项？`
          : `现在还没有固定安排，没有可取消的「${name}」。要新建一项吗？`,
      };
    }
    return {
      error: `没找到「${name}」这项固定安排。要新建的话，告诉我每天还是每周几、多长时间。现在的固定安排：${existing || '暂无'}。`,
    };
  }
  const singleRoutine = queued.filter((item) => item.action === 'routine' && item.remove !== true).length === 1;

  for (const raw of queued) {
    const isRoutine = raw.action === 'routine';
    const existing = isRoutine
      ? null
      : raw.taskId
        ? tasksById.get(raw.taskId)
        : findTask(state.tasks, { name: raw.name }) || (raw.move ? findTask(state.tasks, { name: stripTaskWords(raw.name) }) : null);
    const action = raw.action || (existing ? 'schedule' : 'create');
    if (isRoutine) {
      const name = cleanText(raw.name, 80);
      if (!name) return { error: '这项日常安排叫什么名字？' };
      const weekdays = normalizeWeekdays(raw.weekdays ?? raw.weekday);
      const specificDate = raw.date ? normalizeDate(raw.date, null) : null;
      if (raw.remove === true) {
        const matches = state.fixedBlocks.filter((block) => {
          if (String(block.title || '').trim() !== name) return false;
          if (!weekdays.length) return true;
          const day = block.specific_date ? weekdayOf(block.specific_date) : Number(block.weekday);
          return weekdays.includes(day);
        });
        if (!matches.length) {
          const existing = describeFixedRoutines(state);
          return { error: `没找到「${name}」这项固定安排。现在的固定安排：${existing || '暂无'}。要取消哪一项？` };
        }
        prepared.push({
          itemId: randomId(),
          kind: 'routine',
          action: 'routine',
          remove: true,
          name,
          weekdays: normalizeWeekdays(matches.map((block) => (block.specific_date ? weekdayOf(block.specific_date) : Number(block.weekday)))),
          blockIds: matches.map((block) => block.id),
          startMinute: null,
          endMinute: null,
          date: null,
          taskId: null,
          placement: null,
        });
        continue;
      }
      if (!weekdays.length && !specificDate) {
        return { error: `「${name}」是每天、每周几，还是只锁定某一天？` };
      }
      const spokenDuration = singleRoutine ? localDuration(message) : null;
      const duration = spokenDuration ?? durationOf(raw.durationMinutes, 60) ?? 60;
      const clocks = singleRoutine ? clocksFromMessage(message) : [];
      // Pinned clocks come from the sentence even when other items share the turn; the
      // conflict gate below must not overrule a time the user actually named.
      const pinnedClocks = clocksFromMessage(message);
      const endAnchored = /(?:(?:到|至|直到|截止到|结束于?)\s*(?:下午|晚上|上午|中午|早上|凌晨)?\s*(?:\d{1,2}[:：]\d{2}|\d{1,2}\s*(?:点|时)|[一二两三四五六七八九十]{1,3}\s*(?:点|时)))|(?:(?:\d{1,2}[:：]\d{2}|\d{1,2}\s*(?:点|时)|[一二两三四五六七八九十]{1,3}\s*(?:点|时))\s*(?:结束|截止|收尾))/.test(message);
      let startMinute = raw.start == null ? null : normalizeClock(raw.start);
      let endMinute = raw.end == null ? null : normalizeClock(raw.end, { allowEnd: true });
      let autoPicked = false;
      const pickFreeSlot = () => {
        const slot = routineSlotFor(state, {
          name,
          weekdays,
          date: specificDate,
          durationMinutes: duration,
          message,
          excludeName: name,
        });
        if (slot.error) return slot;
        startMinute = slot.startMinute;
        endMinute = slot.endMinute;
        autoPicked = true;
        return null;
      };

      // The sentence outranks fields the model echoed. Two clocks are a start/end pair;
      // one clock is the start, unless the sentence anchors an end with 到/至/结束.
      if (singleRoutine && pinnedClocks.length >= 2) {
        if (pinnedClocks[0] < pinnedClocks[1]) {
          startMinute = pinnedClocks[0];
          endMinute = pinnedClocks[1];
        } else {
          return { error: reversedRoutineQuestion(name, pinnedClocks[0], pinnedClocks[1], message) };
        }
      } else if (singleRoutine && pinnedClocks.length === 1) {
        const clock = pinnedClocks[0];
        if (endAnchored) {
          if (clock - duration < 0) {
            return {
              error: `「${name}」要在 ${minutesToClock(clock)} 结束、排 ${duration} 分钟就得跨天了，固定安排不能跨天。要缩短时长，还是换个结束时间？`,
            };
          }
          startMinute = clock - duration;
          endMinute = clock;
        } else {
          startMinute = clock;
          if (endMinute == null || endMinute <= clock) endMinute = clock + duration;
        }
      }

      // The user's own clock is the only anchor. A start/end the model invented to fill
      // the old required fields may be reversed or overflow; recover instead of echoing
      // an internal validation line back as the assistant's answer.
      if (startMinute != null && endMinute == null) {
        endMinute = clocks.length >= 2 && clocks[0] === startMinute && clocks[1] > clocks[0]
          ? clocks[1]
          : startMinute + duration;
      }
      if (endMinute != null && endMinute <= startMinute) {
        if (clocks.length >= 2 && clocks[1] > clocks[0]) {
          startMinute = clocks[0];
          endMinute = clocks[1];
        } else if (clocks.length >= 2) {
          return { error: reversedRoutineQuestion(name, clocks[0], clocks[1], message) };
        } else {
          startMinute = null;
          endMinute = null;
        }
      }
      if (startMinute != null && endMinute != null && endMinute > startMinute) {
        if (spokenDuration != null && clocks.length < 2 && endMinute - startMinute !== spokenDuration) {
          if (startMinute + spokenDuration <= 24 * 60) {
            endMinute = startMinute + spokenDuration;
          } else if (clocks.length) {
            return { error: overflowRoutineQuestion(name, startMinute, spokenDuration) };
          } else {
            startMinute = null;
            endMinute = null;
          }
        }
      }
      if (startMinute != null && endMinute != null && endMinute > 24 * 60) {
        if (clocks.length) return { error: overflowRoutineQuestion(name, startMinute, endMinute - startMinute) };
        startMinute = null;
        endMinute = null;
      }
      if (startMinute == null || endMinute == null) {
        if (clocks.length >= 2) {
          if (clocks[1] > clocks[0]) {
            startMinute = clocks[0];
            endMinute = clocks[1];
          } else {
            return { error: reversedRoutineQuestion(name, clocks[0], clocks[1], message) };
          }
        } else if (clocks.length === 1 && !endAnchored) {
          if (clocks[0] + duration <= 24 * 60) {
            startMinute = clocks[0];
            endMinute = clocks[0] + duration;
          } else {
            return { error: overflowRoutineQuestion(name, clocks[0], duration) };
          }
        } else {
          const failure = pickFreeSlot();
          if (failure) return { error: failure.error };
        }
      }
      // A slot the model inferred (no clock in the sentence) is still only a suggestion.
      // "看着空档放" means it must clear courses, routines and this week's task slots;
      // when it collides, choose from the actual free windows instead of double-booking.
      if (pinnedClocks.length === 0 && startMinute != null && endMinute != null) {
        const daysForCheck = specificDate ? [weekdayOf(specificDate)] : weekdays;
        const tasksById = new Map(state.tasks.map((task) => [task.id, task]));
        const busyByDay = routineBusyByWeekday(state, daysForCheck, { excludeName: name, tasksById });
        const collides = daysForCheck.some((day) =>
          (busyByDay.get(day) || []).some(([from, to]) => startMinute < to && endMinute > from),
        );
        if (collides) {
          const failure = pickFreeSlot();
          if (failure) return { error: failure.error };
        }
      }
      if (startMinute == null || endMinute == null || endMinute <= startMinute || endMinute > 24 * 60) {
        return {
          error: clocks.length
            ? overflowRoutineQuestion(name, clocks[0], duration)
            : `「${name}」的时间没排开，再说一下想放在几点到几点？`,
        };
      }
      // A one-off lock only replaces other one-off locks on that date; the weekly
      // routine is a different fact and must survive an exception day.
      const sameName = state.fixedBlocks.filter((block) => String(block.title || '').trim() === name);
      const replacePool = specificDate
        ? sameName.filter((block) => block.specific_date === specificDate)
        : sameName.filter((block) => !block.specific_date);
      const sameSlot = (block) => Number(block.start_minute) === startMinute && Number(block.end_minute) === endMinute;
      const coveredByWeekly = specificDate && sameName.some(
        (block) => !block.specific_date && Number(block.weekday) === weekdayOf(specificDate) && sameSlot(block),
      );
      if (coveredByWeekly) {
        return { error: `「${name}」${specificDate} ${minutesToClock(startMinute)}–${minutesToClock(endMinute)} 已经被每周固定安排覆盖了。` };
      }
      const existingDays = new Set(
        replacePool
          .filter((block) => sameSlot(block))
          .map((block) => (block.specific_date ? weekdayOf(block.specific_date) : Number(block.weekday))),
      );
      const freshWeekdays = specificDate ? [] : weekdays.filter((day) => !existingDays.has(day));
      const staleBlocks = raw.replace === true
        ? replacePool.filter((block) => (specificDate ? !sameSlot(block) : !weekdays.includes(Number(block.weekday)) || !sameSlot(block)))
        : [];
      if (specificDate && existingDays.has(weekdayOf(specificDate))) {
        return { error: `「${name}」${specificDate} ${minutesToClock(startMinute)}–${minutesToClock(endMinute)} 已经是固定安排了。` };
      }
      if (!specificDate && !freshWeekdays.length && !staleBlocks.length) {
        return { error: `「${name}」${weekdayRangeText(weekdays)} ${minutesToClock(startMinute)}–${minutesToClock(endMinute)} 已经是固定安排了。` };
      }
      prepared.push({
        itemId: randomId(),
        kind: 'routine',
        action: 'routine',
        remove: false,
        replace: staleBlocks.length > 0,
        blockIds: staleBlocks.map((block) => block.id),
        name,
        weekdays: specificDate ? [] : weekdays,
        insertWeekdays: specificDate ? [] : freshWeekdays,
        date: specificDate,
        startMinute,
        endMinute,
        blockKind: raw.blockKind || 'FIXED',
        autoPicked,
        taskId: null,
        placement: null,
      });
      continue;
    }
    const isStatus = ['complete', 'progress', 'cancel', 'postpone'].includes(action);
    if (isStatus) {
      if (!existing) {
        return { error: `我没在待办里找到「${raw.name}」，可以说得更完整一点吗？` };
      }
      prepared.push({
        itemId: randomId(),
        kind: 'status',
        action,
        taskId: existing.id,
        name: existing.name,
        taskSnapshot: existing,
        progress: action === 'progress' ? raw.progress ?? existing.progress ?? 0 : action === 'complete' ? 100 : existing.progress,
        actualMinutes: action === 'complete' ? (durationOf(raw.actualMinutes, null) || null) : null,
      });
      continue;
    }
    if (action === 'update') {
      if (!existing) return { error: `我没在待办里找到「${raw.name}」。` };
      prepared.push({
        itemId: randomId(),
        kind: 'update',
        action: 'update',
        taskId: existing.id,
        name: raw.name || existing.name,
        taskSnapshot: existing,
        patch: {
          name: raw.name || existing.name,
          duration_minutes: raw.durationMinutes ?? existing.duration_minutes,
          priority: raw.priority || existing.priority,
          category: raw.category || existing.category,
          due_date: raw.dueDate ?? existing.due_date,
          planned_date: raw.plannedDate ?? existing.planned_date,
        },
      });
      continue;
    }

    const wantsSchedule = action !== 'create' || Boolean(raw.date || raw.start);
    const targetDate = normalizeDate(raw.date, activeDate);
    const duration = durationOf(raw.durationMinutes, existing?.duration_minutes || 60) || 60;
    const move = Boolean(raw.move) && wantsSchedule && Boolean(existing);
    const currentSegments = move
      ? state.segments
          .filter((segment) => segment.task_id === existing.id && segment.date >= state.today)
          .sort((a, b) => String(a.date).localeCompare(String(b.date)) || a.start_minute - b.start_minute)
      : [];
    const item = {
      itemId: randomId(),
      kind: existing ? 'schedule' : 'create',
      action: existing ? 'schedule' : 'create',
      taskId: existing?.id || randomId(),
      name: raw.name || existing?.name || '新任务',
      note: raw.note || existing?.note || '',
      durationMinutes: duration,
      priority: raw.priority || existing?.priority || 'MEDIUM',
      category: raw.category || existing?.category || 'UNSPECIFIED',
      dueDate: raw.dueDate ?? existing?.due_date ?? null,
      plannedDate: raw.plannedDate ?? existing?.planned_date ?? null,
      wantsSchedule,
      date: wantsSchedule ? targetDate : null,
      startMinute: raw.start,
      placement: null,
      existingTask: Boolean(existing),
      move,
      replaceSegmentIds: currentSegments.map((segment) => segment.id),
      moveFrom: currentSegments.length
        ? {
            date: currentSegments[0].date,
            startMinute: Number(currentSegments[0].start_minute),
            endMinute: Number(currentSegments[0].end_minute),
            count: currentSegments.length,
          }
        : null,
    };
    prepared.push(item);
    if (wantsSchedule) {
      if (!schedulingGroups.has(targetDate)) schedulingGroups.set(targetDate, []);
      schedulingGroups.get(targetDate).push(item);
    }
  }

  for (const [date, group] of schedulingGroups) {
    // 已经过去的日子不排：排上去只会得到一条永远无法执行的记录。
    if (date < state.today) {
      return { error: `${date} 已经过去了，要排到哪一天？` };
    }
    // A moved task must not collide with the slot it is leaving.
    const replacedIds = new Set(group.flatMap((item) => item.replaceSegmentIds || []));
    const occupied = occupiedForDate(date, { segments: state.segments, fixedBlocks: state.fixedBlocks, tasksById }).filter(
      (entry) => !replacedIds.has(entry.id),
    );
    const candidates = group.map((item) => ({
      name: item.name,
      durationMinutes: item.durationMinutes,
      priority: item.priority,
      category: item.category,
      explicitStartMinute: item.startMinute,
      existingTaskId: item.existingTask ? item.taskId : null,
    }));
    const earliestStartMinute = earliestStartOn(date, state);
    const options = gapMinutes ? { gapMinutes, bufferMinutes: Math.min(10, gapMinutes) } : {};
    const result = planDay(
      candidates,
      occupied,
      earliestStartMinute == null ? options : { ...options, earliestStartMinute },
    );
    for (const placement of result.placements) {
      const item = group[placement.index];
      if (!item) continue;
      if (item.startMinute != null && !placement.fromUser) {
        adjustedPinned = true;
        if (earliestStartMinute != null && Number(item.startMinute) < earliestStartMinute) pinnedPast = true;
      }
      item.placement = {
        id: randomId(),
        date,
        startMinute: placement.startMinute,
        endMinute: placement.endMinute,
        trackId: placement.trackId || 'focus',
        nestedUnderId: placement.nestedUnderId || null,
        nestedUnderTitle: placement.nestedUnderTitle || null,
        fromUser: Boolean(placement.fromUser),
      };
      item.date = date;
    }
    for (const miss of result.unplaced) {
      const item = group[miss.index];
      if (!item) continue;
      unplaced.push({ itemId: item.itemId, name: item.name, reason: miss.reason });
      if (miss.reason === 'MISSING_DURATION') item.durationMinutes = 60;
    }
  }

  const summary = proposalSummary(prepared, source);
  const reply = cleanText(say, 300) || (summary ? `我先把这件事接住了：${summary}` : '说说你想安排什么？');
  const payload = {
    intent,
    source,
    reply,
    items: prepared,
    unplaced,
    summary,
    activeDate,
    adjustedPinned,
    pinnedPast,
  };
  const proposalId = randomId();
  // A new draft means the previous unconfirmed one is stale; the user can keep
  // talking without answering it, so it must not linger as a second card.
  await db
    .from('proposals')
    .update({ status: 'superseded', consumed_at: nowIso() })
    .eq('user_id', user.id)
    .eq('status', 'pending');
  rows(
    await db.from('proposals').insert({
      id: proposalId,
      user_id: user.id,
      payload,
      status: 'pending',
      created_at: nowIso(),
    }).select('id'),
  );
  await db.from('chat_messages').insert({
    id: randomId(),
    user_id: user.id,
    role: 'assistant',
    content: reply,
    created_at: nowIso(),
  });
  return { proposal: { id: proposalId, ...payload } };
}

/**
 * "排开点" re-shapes the day the user is looking at. A waiting draft is spread as a
 * fresh draft; otherwise the committed day's movable segments are moved as ordinary
 * reschedule items, so preview, undo and failure compensation all reuse one path.
 */
async function buildSpreadProposal(db, user, state, { activeDate, date, gapMinutes, pending }) {
  const tasksById = new Map(state.tasks.map((task) => [task.id, task]));
  const draftItems = (pending?.payload?.items || []).filter(
    (item) => (item.kind === 'create' || item.kind === 'schedule') && item.placement && (item.date || item.placement.date) === date,
  );
  if (draftItems.length >= 2) {
    const rawItems = draftItems.map((item) => ({
      name: item.name,
      taskId: item.existingTask ? item.taskId : null,
      action: item.existingTask ? 'schedule' : 'create',
      move: Boolean(item.move),
      durationMinutes: item.durationMinutes,
      priority: item.priority,
      category: item.category,
      date: item.date || date,
      // A time the user stated stays an anchor; everything else may move outward.
      start: item.placement?.fromUser ? item.placement.startMinute : null,
    }));
    return buildProposal(db, user, state, {
      activeDate,
      intent: 'plan',
      items: rawItems,
      say: `好，两件事之间留出 ${gapMinutes} 分钟，重新铺一遍。`,
      source: 'local',
      gapMinutes,
    });
  }

  const childParentIds = new Set(
    state.segments.filter((segment) => segment.date === date && segment.nested_under_id).map((segment) => segment.nested_under_id),
  );
  const segments = state.segments
    .filter((segment) => segment.date === date)
    .map((segment) => ({
      id: segment.id,
      taskId: segment.task_id,
      date: segment.date,
      startMinute: Number(segment.start_minute),
      endMinute: Number(segment.end_minute),
      trackId: segment.track_id || 'focus',
      locked: Boolean(segment.locked),
      nestedUnderId: segment.nested_under_id || null,
    }))
    .filter((segment) => {
      const task = tasksById.get(segment.taskId);
      return task && isActiveTask(task) && !segment.locked && !segment.nestedUnderId && !childParentIds.has(segment.id);
    });
  if (!segments.length) {
    return { reply: '这天还没有排好的事，先说要做什么，我再来铺。' };
  }
  if (segments.length < 2) {
    return { reply: '这天只排了一件事，还不用排开。要不要把清单里的其他事也放进来？' };
  }
  const occupied = occupiedForDate(date, { segments: state.segments, fixedBlocks: state.fixedBlocks, tasksById });
  const { moves } = spreadDay(segments, occupied, { gapMinutes });
  if (!moves.length) {
    return { reply: `这天的安排之间已经有 ${gapMinutes} 分钟以上，我没有再挪你的时间。` };
  }
  const items = moves.map((move) => {
    const segment = move.segment;
    const task = tasksById.get(segment.taskId);
    return {
      itemId: randomId(),
      kind: 'schedule',
      action: 'schedule',
      taskId: segment.taskId,
      name: task.name,
      durationMinutes: segment.endMinute - segment.startMinute,
      priority: task.priority,
      category: task.category,
      dueDate: task.due_date ?? null,
      plannedDate: task.planned_date ?? null,
      wantsSchedule: true,
      date: segment.date,
      startMinute: move.startMinute,
      placement: {
        id: randomId(),
        date: segment.date,
        startMinute: move.startMinute,
        endMinute: move.endMinute,
        trackId: segment.trackId,
        nestedUnderId: null,
        nestedUnderTitle: null,
        fromUser: false,
      },
      existingTask: true,
      move: true,
      replaceSegmentIds: [segment.id],
      moveFrom: { date: segment.date, startMinute: segment.startMinute, endMinute: segment.endMinute, count: 1 },
    };
  });
  const payload = {
    intent: 'plan',
    source: 'local',
    reply: `好，中间留出约 ${gapMinutes} 分钟，把排得紧的 ${items.length} 段往后挪一点。`,
    items,
    unplaced: [],
    summary: `${date} 的 ${items.length} 件事重新铺开。`,
    activeDate,
    adjustedPinned: false,
    spread: true,
  };
  const proposalId = randomId();
  await db
    .from('proposals')
    .update({ status: 'superseded', consumed_at: nowIso() })
    .eq('user_id', user.id)
    .eq('status', 'pending');
  rows(await db.from('proposals').insert({ id: proposalId, user_id: user.id, payload, status: 'pending', created_at: nowIso() }).select('id'));
  await db.from('chat_messages').insert({
    id: randomId(),
    user_id: user.id,
    role: 'assistant',
    content: payload.reply,
    created_at: nowIso(),
  });
  return { proposal: { id: proposalId, ...payload } };
}

// The hosting database client exposes only the basics (select/eq/order/limit),// so membership lookups are done one bounded id at a time.
async function selectByIds(db, table, user, ids) {
  const found = [];
  for (const id of [...new Set(ids.filter(Boolean))].slice(0, 60)) {
    const row = rows(await db.from(table).select('*').eq('id', id).eq('user_id', user.id).limit(1))?.[0];
    if (row) found.push(row);
  }
  return found;
}

async function snapshotRows(db, user, taskIds, segmentIds, blockIds = []) {
  return {
    tasks: await selectByIds(db, 'tasks', user, taskIds),
    segments: await selectByIds(db, 'segments', user, segmentIds),
    blocks: await selectByIds(db, 'fixed_blocks', user, blockIds),
  };
}

function taskRowForInsert(user, item) {
  const now = nowIso();
  return {
    id: item.taskId,
    user_id: user.id,
    name: cleanText(item.name, 80) || '新任务',
    note: cleanText(item.note, 500),
    category: normalizeCategory(item.category, 'UNSPECIFIED'),
    priority: normalizePriority(item.priority, 'MEDIUM'),
    duration_minutes: durationOf(item.durationMinutes, 60),
    due_date: item.dueDate || null,
    planned_date: item.plannedDate || (item.wantsSchedule ? item.date : null) || null,
    status: 'NOT_STARTED',
    progress: null,
    actual_minutes: null,
    postpone_count: 0,
    created_at: now,
    updated_at: now,
    completed_at: null,
  };
}

function segmentRowForInsert(user, item, placement) {
  const now = nowIso();
  return {
    id: placement.id || randomId(),
    user_id: user.id,
    task_id: item.taskId,
    on_date: placement.date,
    start_minute: placement.startMinute,
    end_minute: placement.endMinute,
    track_id: placement.trackId || 'focus',
    locked: false,
    from_user: placement.fromUser ?? item.startMinute != null,
    nested_under_id: placement.nestedUnderId || null,
    created_at: now,
    updated_at: now,
  };
}

async function executeProposal(db, user, proposal) {
  const payload = proposal.payload;
  const items = Array.isArray(payload.items) ? payload.items : [];
  // 草案在确认前可能在对话里放了几小时；确认时落点已经过去，写进去就是一条"排在过去"的记录。
  // 整份不写并问清要不要重排——只执行一半，用户会拿到半个承诺。
  const nowMinute = nowMinuteInShanghai();
  const today = todayInShanghai();
  const stale = items.filter(
    (item) =>
      (item.kind === 'create' || item.kind === 'schedule') &&
      item.placement &&
      (item.placement.date < today ||
        (item.placement.date === today && Number(item.placement.startMinute) < nowMinute)),
  );
  if (stale.length) {
    const reply = `草案里的「${stale.map((item) => item.name).join('、')}」时间已经过了，我没有写进去。要我按现在的时间重新排一版吗？`;
    await db
      .from('proposals')
      .update({ status: 'expired', consumed_at: nowIso() })
      .eq('id', proposal.id)
      .eq('user_id', user.id);
    await appendChat(db, user, 'assistant', reply);
    return { operationId: null, reply, expired: true };
  }
  const affectedTaskIds = [...new Set(items.filter((item) => item.kind === 'status' || item.kind === 'update' || item.kind === 'schedule').map((item) => item.taskId))];
  const affectedSegmentIds = [
    ...new Set([
      ...items.filter((item) => item.kind === 'schedule' && item.placement).map((item) => item.placement.id),
      ...items.flatMap((item) => item.replaceSegmentIds || []),
    ]),
  ];
  const removedBlockIds = [
    ...new Set(
      items
        .filter((item) => item.kind === 'routine' && (item.remove || item.replace))
        .flatMap((item) => item.blockIds || []),
    ),
  ];
  const before = await snapshotRows(db, user, affectedTaskIds, affectedSegmentIds, removedBlockIds);
  const expectedTasks = new Map(before.tasks.map((task) => [task.id, task]));
  const createdTasks = [];
  const createdSegments = [];
  const createdBlocks = [];
  const deletedSegments = [];
  const deletedBlocks = [];
  const updatedTaskIds = [];

  try {
    for (const item of items) {
      if (item.kind === 'routine') {
        if (item.remove || item.replace) {
          for (const blockId of item.blockIds || []) {
            await db.from('fixed_blocks').delete().eq('id', blockId).eq('user_id', user.id);
            const removed = before.blocks.find((block) => block.id === blockId);
            if (removed) deletedBlocks.push(removed);
          }
          if (item.remove) continue;
        }
        const weekdays = item.date
          ? [null]
          : Array.isArray(item.insertWeekdays)
            ? item.insertWeekdays
            : item.weekdays || [];
        for (const weekday of weekdays) {
          const row = {
            id: randomId(),
            user_id: user.id,
            title: cleanText(item.name, 40) || '日常安排',
            kind: ['CLASS', 'FIXED', 'REST'].includes(String(item.blockKind || '').toUpperCase()) ? String(item.blockKind).toUpperCase() : 'FIXED',
            weekday: item.date ? null : weekday,
            specific_date: item.date || null,
            start_minute: item.startMinute,
            end_minute: item.endMinute,
            created_at: nowIso(),
          };
          rows(await db.from('fixed_blocks').insert(row).select('id'));
          createdBlocks.push(row);
        }
        continue;
      }
      if (item.kind === 'create') {
        const row = taskRowForInsert(user, item);
        rows(await db.from('tasks').insert(row).select('id'));
        createdTasks.push(row);
        if (item.placement) {
          const segment = segmentRowForInsert(user, item, item.placement);
          rows(await db.from('segments').insert(segment).select('id'));
          createdSegments.push(segment);
        }
        continue;
      }
      if (item.kind === 'schedule') {
        const existing = expectedTasks.get(item.taskId);
        if (!existing) fail('task_not_found', 404);
        const replacing = Array.isArray(item.replaceSegmentIds) ? item.replaceSegmentIds : [];
        for (const segmentId of replacing) {
          await db.from('segments').delete().eq('id', segmentId).eq('user_id', user.id);
          const removed = before.segments.find((segment) => segment.id === segmentId);
          if (removed) deletedSegments.push(removed);
        }
        if (item.placement) {
          const segment = segmentRowForInsert(user, item, item.placement);
          rows(await db.from('segments').insert(segment).select('id'));
          createdSegments.push(segment);
        }
        if (item.date && existing.planned_date !== item.date) {
          rows(
            await db.from('tasks')
              .update({ planned_date: item.date, updated_at: nowIso() })
              .eq('id', item.taskId)
              .eq('user_id', user.id)
              .select('id'),
          );
          updatedTaskIds.push(item.taskId);
        }
        continue;
      }
      if (item.kind === 'status') {
        const existing = expectedTasks.get(item.taskId);
        if (!existing) fail('task_not_found', 404);
        const now = nowIso();
        const patch = { updated_at: now };
        if (item.action === 'complete') {
          patch.status = 'COMPLETED';
          patch.progress = 100;
          patch.completed_at = now;
          if (item.actualMinutes != null) patch.actual_minutes = item.actualMinutes;
        } else if (item.action === 'progress') {
          patch.progress = Math.max(0, Math.min(99, Math.round(Number(item.progress) || 0)));
          if (existing.status === 'NOT_STARTED') patch.status = 'IN_PROGRESS';
        } else if (item.action === 'cancel') {
          patch.status = 'CANCELLED';
        } else if (item.action === 'postpone') {
          patch.status = 'POSTPONED';
          patch.postpone_count = (Number(existing.postpone_count) || 0) + 1;
          patch.planned_date = addDays(item.date || payload.activeDate || todayInShanghai(), 1);
        }
        rows(await db.from('tasks').update(patch).eq('id', item.taskId).eq('user_id', user.id).select('id'));
        updatedTaskIds.push(item.taskId);
        continue;
      }
      if (item.kind === 'update') {
        const existing = expectedTasks.get(item.taskId);
        if (!existing) fail('task_not_found', 404);
        const patch = { ...item.patch, updated_at: nowIso() };
        if (patch.name) patch.name = cleanText(patch.name, 80) || existing.name;
        if (patch.duration_minutes != null) patch.duration_minutes = durationOf(patch.duration_minutes, existing.duration_minutes);
        rows(await db.from('tasks').update(patch).eq('id', item.taskId).eq('user_id', user.id).select('id'));
        updatedTaskIds.push(item.taskId);
      }
    }

    const after = await snapshotRows(
      db,
      user,
      [...new Set([...affectedTaskIds, ...createdTasks.map((task) => task.id)])],
      createdSegments.map((segment) => segment.id),
      [...new Set([...removedBlockIds, ...createdBlocks.map((block) => block.id)])],
    );
    const operationId = randomId();
    rows(
      await db.from('operations').insert({
        id: operationId,
        user_id: user.id,
        kind: 'assistant',
        label: cleanText(payload.summary || payload.reply || '一句话处理', 120),
        before,
        after,
        created_at: nowIso(),
        undone_at: null,
      }).select('id'),
    );
    await db.from('proposals').update({ status: 'done', operation_id: operationId }).eq('id', proposal.id).eq('user_id', user.id);
    const doneText = payload.summary ? `已经安排好：${payload.summary}` : '已经安排好了。';
    await appendChat(db, user, 'assistant', doneText);
    return { operationId, reply: doneText };
  } catch (error) {
    // Best-effort compensation: remove rows created by this attempt and put back
    // the slots a move had already removed.
    for (const row of deletedSegments) {
      try {
        await db.from('segments').insert(row);
      } catch {
        // Compensation is best effort; the original failure is reported below.
      }
    }
    for (const segment of createdSegments) {
      try {
        await db.from('segments').delete().eq('id', segment.id).eq('user_id', user.id);
      } catch {
        // Compensation is best effort; the original failure is reported below.
      }
    }
    for (const task of createdTasks) {
      try {
        await db.from('tasks').delete().eq('id', task.id).eq('user_id', user.id);
      } catch {
        // Compensation is best effort.
      }
    }
    for (const block of createdBlocks) {
      try {
        await db.from('fixed_blocks').delete().eq('id', block.id).eq('user_id', user.id);
      } catch {
        // Compensation is best effort.
      }
    }
    for (const block of deletedBlocks) {
      try {
        await db.from('fixed_blocks').insert(block);
      } catch {
        // Compensation is best effort.
      }
    }
    try {
      await db.from('proposals').update({ status: 'failed' }).eq('id', proposal.id).eq('user_id', user.id);
    } catch {
      // Compensation is best effort.
    }
    throw error;
  }
}

async function undoOperation(db, user) {
  const operation = rows(
    await db
      .from('operations')
      .select('*')
      .eq('user_id', user.id)
      .is('undone_at', null)
      .order('created_at', { ascending: false })
      .limit(1),
  )?.[0];
  if (!operation) fail('nothing_to_undo', 404);
  const claimed = rows(
    await db
      .from('operations')
      .update({ undone_at: nowIso() })
      .eq('id', operation.id)
      .eq('user_id', user.id)
      .is('undone_at', null)
      .select('id'),
  );
  if (!claimed?.length) fail('undo_conflict', 409);

  const beforeTasks = operation.before?.tasks || [];
  const beforeSegments = operation.before?.segments || [];
  const beforeBlocks = operation.before?.blocks || [];
  const afterTasks = operation.after?.tasks || [];
  const afterSegments = operation.after?.segments || [];
  const afterBlocks = operation.after?.blocks || [];
  const beforeTaskMap = new Map(beforeTasks.map((task) => [task.id, task]));
  const beforeSegmentMap = new Map(beforeSegments.map((segment) => [segment.id, segment]));
  const beforeBlockMap = new Map(beforeBlocks.map((block) => [block.id, block]));
  const afterTaskMap = new Map(afterTasks.map((task) => [task.id, task]));
  const afterSegmentMap = new Map(afterSegments.map((segment) => [segment.id, segment]));
  const afterBlockMap = new Map(afterBlocks.map((block) => [block.id, block]));

  const currentTasks = await selectByIds(db, 'tasks', user, afterTasks.map((task) => task.id));
  const currentSegments = await selectByIds(db, 'segments', user, afterSegments.map((segment) => segment.id));
  const currentBlocks = await selectByIds(db, 'fixed_blocks', user, afterBlocks.map((block) => block.id));
  const currentTaskMap = new Map(currentTasks.map((task) => [task.id, task]));
  const currentSegmentMap = new Map(currentSegments.map((segment) => [segment.id, segment]));
  const currentBlockMap = new Map(currentBlocks.map((block) => [block.id, block]));

  for (const task of afterTasks) {
    const current = currentTaskMap.get(task.id);
    if (!current) {
      await db.from('operations').update({ undone_at: null }).eq('id', operation.id).eq('user_id', user.id);
      fail('undo_not_safe', 409);
    }
    if (String(current.updated_at) !== String(task.updated_at)) {
      await db.from('operations').update({ undone_at: null }).eq('id', operation.id).eq('user_id', user.id);
      fail('undo_not_safe', 409);
    }
  }
  for (const segment of afterSegments) {
    const current = currentSegmentMap.get(segment.id);
    if (!current || String(current.updated_at) !== String(segment.updated_at)) {
      await db.from('operations').update({ undone_at: null }).eq('id', operation.id).eq('user_id', user.id);
      fail('undo_not_safe', 409);
    }
  }
  for (const block of afterBlocks) {
    if (!currentBlockMap.has(block.id)) {
      await db.from('operations').update({ undone_at: null }).eq('id', operation.id).eq('user_id', user.id);
      fail('undo_not_safe', 409);
    }
  }

  for (const block of afterBlocks) {
    if (!beforeBlockMap.has(block.id)) {
      await db.from('fixed_blocks').delete().eq('id', block.id).eq('user_id', user.id);
    }
  }
  for (const segment of afterSegments) {
    if (!beforeSegmentMap.has(segment.id)) {
      await db.from('segments').delete().eq('id', segment.id).eq('user_id', user.id);
    }
  }
  for (const task of afterTasks) {
    if (!beforeTaskMap.has(task.id)) {
      await db.from('segments').delete().eq('task_id', task.id).eq('user_id', user.id);
      await db.from('tasks').delete().eq('id', task.id).eq('user_id', user.id);
    }
  }
  for (const task of beforeTasks) {
    if (!afterTaskMap.has(task.id)) {
      await db.from('tasks').insert(task);
    } else {
      const { id, user_id, created_at, ...patch } = task;
      await db.from('tasks').update(patch).eq('id', task.id).eq('user_id', user.id);
    }
  }
  for (const segment of beforeSegments) {
    if (!afterSegmentMap.has(segment.id)) {
      await db.from('segments').insert(segment);
    } else {
      const { id, user_id, created_at, ...patch } = segment;
      await db.from('segments').update(patch).eq('id', segment.id).eq('user_id', user.id);
    }
  }
  for (const block of beforeBlocks) {
    if (!afterBlockMap.has(block.id)) {
      await db.from('fixed_blocks').insert(block);
    }
  }
  await appendChat(db, user, 'assistant', `已撤销上一次操作：${operation.label || '调整'}。`);
  return operation.label;
}

function buildStateReply(state) {
  return jsonResponse(state);
}

function requireTeamCode() {
  const code = env('REPLAND_TEAM_CODE').trim();
  if (!code) fail('team_code_not_configured', 503);
  return code;
}

const TEAM_MEDIA_JSON_LIMIT = 2 * 1024 * 1024;
// The website runs on its own demo document; the bot keeps writing the main one.
const TEAM_KEYS = new Set([TEAM_KEY, 'demo']);

function teamKeyOf(request) {
  const key = String(request.headers.get('x-team-key') || '').trim().toLowerCase();
  return TEAM_KEYS.has(key) ? key : TEAM_KEY;
}

async function loadTeamRow(db, key = TEAM_KEY) {
  return rows(await db.from('team_state').select('*').eq('team_key', key).limit(1))?.[0] || null;
}

async function createTeamRow(db, key = TEAM_KEY) {
  const demo = key === 'demo';
  const doc = createTeamDoc(demo ? { name: '数模赛备赛组（演示）', code: 'demo' } : undefined);
  doc.team.code = demo ? 'demo' : requireTeamCode();
  const row = { id: randomId(), team_key: key, doc, version: 1, updated_at: nowIso() };
  try {
    rows(await db.from('team_state').insert(row).select('id'));
    return row;
  } catch (error) {
    const existing = await loadTeamRow(db, key);
    if (existing) return existing;
    throw error;
  }
}

function assertTeamCode(request, doc, key = TEAM_KEY) {
  if (key === 'demo') return;
  const expected = requireTeamCode();
  const code = String(request.headers.get('x-team-code') || '').trim();
  if (!code) fail('team_code_required', 401);
  if (!safeEqual(code, String(doc?.team?.code || expected))) fail('invalid_team_code', 401);
}

function teamChat(body) {
  if (body?.kind === 'group') {
    const groupOpenid = cleanText(body.groupOpenid, 128);
    if (!groupOpenid) fail('invalid_input', 400);
    return { kind: 'group', groupOpenid, memberOpenid: cleanText(body.memberOpenid, 128) };
  }
  if (body?.kind === 'c2c') {
    const userOpenid = cleanText(body.userOpenid, 128);
    if (!userOpenid) fail('invalid_input', 400);
    return { kind: 'c2c', userOpenid };
  }
  fail('invalid_input', 400);
}

async function withTeamState(db, key, mutate) {
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const row = (await loadTeamRow(db, key)) || (await createTeamRow(db, key));
    const doc = normalizeDoc(structuredClone(row.doc));
    const result = await mutate(doc);
    const updated = rows(
      await db
        .from('team_state')
        .update({ doc, version: row.version + 1, updated_at: nowIso() })
        .eq('id', row.id)
        .eq('version', row.version)
        .select('version'),
    );
    if (updated?.length) return result;
  }
  fail('team_busy', 409);
}

async function storeTeamMedia(db, kind, base64) {
  const id = randomId();
  rows(
    await db
      .from('team_media')
      .insert({ id, team_key: TEAM_KEY, kind, mime: 'image/png', data: base64, created_at: nowIso() })
      .select('id'),
  );
  return id;
}

export function application({ db }) {
  async function route(ctx) {
    const request = ctx.request;
    const url = new URL(request.url);
    const pathname = url.pathname;
    const match = pathname.match(/^\/(?:functions\/v1\/)?(?:app|app-d[0-9a-f]{32})(\/.*)?$/);
    if (!match) fail('not_found', 404);
    const path = match[1] || '/';
    const method = request.method.toUpperCase();

    if (path === '/api/health' && method === 'GET') {
      return jsonResponse({ ok: true, aiConfigured: Boolean(aiKey()), today: todayInShanghai() });
    }

    if (path === '/api/auth/guest' && method === 'POST') {
      const body = await readJson(request);
      const deviceId = typeof body?.deviceId === 'string' ? body.deviceId.trim() : '';
      if (!/^[A-Za-z0-9_-]{8,128}$/.test(deviceId)) fail('invalid_input', 400);
      const deviceHash = await sha256Hex(deviceId);
      let profile = rows(await db.from('profiles').select('*').eq('device_hash', deviceHash).limit(1))?.[0] || null;
      if (!profile) {
        const insert = {
          id: randomId(),
          name: `朋友 ${deviceHash.slice(0, 4)}`,
          name_key: `guest-${deviceHash.slice(0, 24)}`,
          device_hash: deviceHash,
          created_at: nowIso(),
          last_seen_at: nowIso(),
        };
        try {
          rows(await db.from('profiles').insert(insert));
          profile = insert;
        } catch (error) {
          profile = rows(await db.from('profiles').select('*').eq('device_hash', deviceHash).limit(1))?.[0] || null;
          if (!profile) throw error;
        }
      } else {
        await db.from('profiles').update({ last_seen_at: nowIso() }).eq('id', profile.id);
      }
      const token = randomToken();
      const tokenHash = await sha256Hex(token);
      rows(
        await db.from('sessions').insert({
          id: randomId(),
          user_id: profile.id,
          token_hash: tokenHash,
          created_at: nowIso(),
          expires_at: new Date(Date.now() + SESSION_DAYS * 86_400_000).toISOString(),
        }),
      );
      return jsonResponse({ token, profile: { id: profile.id, name: profile.name } });
    }

    if (path === '/api/auth/login' && method === 'POST') {
      const body = await readJson(request);
      const accessCode = typeof body?.code === 'string' ? body.code.trim() : '';
      const name = sanitizeName(body?.name);
      const deviceId = typeof body?.deviceId === 'string' ? body.deviceId.trim() : '';
      if (!accessCode || !name || !/^[A-Za-z0-9_-]{8,128}$/.test(deviceId)) fail('invalid_input', 400);
      const expected = env('REPLAND_ACCESS_CODE').trim();
      if (!expected) fail('service_not_configured', 503);
      const givenCode = normalizeCode(accessCode);
      const expectedCode = normalizeCode(expected);
      if (!givenCode || !expectedCode) fail('invalid_input', 400);
      const [givenHash, expectedHash] = await Promise.all([
        sha256Hex(givenCode),
        sha256Hex(expectedCode),
      ]);
      if (!safeEqual(givenHash, expectedHash)) fail('invalid_code', 401);
      const deviceHash = await sha256Hex(deviceId);
      const nameKey = name.toLowerCase();
      let profile = rows(await db.from('profiles').select('*').eq('name_key', nameKey).limit(1))?.[0] || null;
      if (!profile) {
        const insert = {
          id: randomId(),
          name,
          name_key: nameKey,
          device_hash: deviceHash,
          created_at: nowIso(),
          last_seen_at: nowIso(),
        };
        try {
          rows(await db.from('profiles').insert(insert).select('id,name,created_at'));
          profile = insert;
        } catch (error) {
          profile = rows(await db.from('profiles').select('*').eq('name_key', nameKey).limit(1))?.[0] || null;
          if (!profile) throw error;
        }
      }
      // The nickname is the identity in this friends-only test. Signing in from a
      // new browser rebinds the profile and invalidates the previous device.
      let rebound = false;
      if (!safeEqual(profile.device_hash, deviceHash)) {
        await db.from('profiles').update({ device_hash: deviceHash, last_seen_at: nowIso() }).eq('id', profile.id);
        await db.from('sessions').delete().eq('user_id', profile.id);
        profile.device_hash = deviceHash;
        rebound = true;
      }
      const token = randomToken();
      const tokenHash = await sha256Hex(token);
      rows(
        await db.from('sessions').insert({
          id: randomId(),
          user_id: profile.id,
          token_hash: tokenHash,
          created_at: nowIso(),
          expires_at: new Date(Date.now() + SESSION_DAYS * 86_400_000).toISOString(),
        }).select('id'),
      );
      await db.from('profiles').update({ last_seen_at: nowIso() }).eq('id', profile.id);
      return jsonResponse({ token, rebound, profile: { id: profile.id, name: profile.name } });
    }

    const mediaMatch = path.match(/^\/api\/team\/media\/([0-9a-fA-F-]{36})\.png$/);
    if (mediaMatch && method === 'GET') {
      const row = rows(await db.from('team_media').select('*').eq('id', mediaMatch[1]).limit(1))?.[0];
      if (!row) fail('not_found', 404);
      const binary = atob(row.data);
      const bytes = new Uint8Array(binary.length);
      for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
      return new Response(bytes, {
        headers: {
          'Content-Type': row.mime || 'image/png',
          'Cache-Control': 'public, max-age=604800',
          'Content-Length': String(bytes.length),
        },
      });
    }

    if (path === '/api/team/board' && method === 'GET') {
      const key = teamKeyOf(request);
      const row = (await loadTeamRow(db, key)) || (await createTeamRow(db, key));
      assertTeamCode(request, row.doc, key);
      const doc = normalizeDoc(row.doc);
      return jsonResponse({ ...teamBoard(doc), weekly: weeklyReportData(doc) });
    }

    if (path === '/api/team/command' && method === 'POST') {
      const body = await readJson(request);
      const key = teamKeyOf(request);
      const row = (await loadTeamRow(db, key)) || (await createTeamRow(db, key));
      assertTeamCode(request, row.doc, key);
      const chat = teamChat(body);
      const text = cleanText(body?.text, 500);
      if (!text) fail('invalid_input', 400);
      const result = await withTeamState(db, key, (doc) => handleTeamCommand(doc, chat, text));
      return jsonResponse(result);
    }

    if (path === '/api/team/pending' && method === 'POST') {
      const body = await readJson(request);
      const key = teamKeyOf(request);
      const row = (await loadTeamRow(db, key)) || (await createTeamRow(db, key));
      assertTeamCode(request, row.doc, key);
      const memberId = cleanText(body?.memberId, 64);
      const text = cleanText(body?.text, 1200);
      if (!memberId || !text) fail('invalid_input', 400);
      const priority = body?.priority === 'high' ? 'high' : 'normal';
      const queued = await withTeamState(db, key, (doc) => queuePending(doc, memberId, text, priority));
      return jsonResponse({ ok: Boolean(queued) });
    }

    if (path === '/api/team/media' && method === 'POST') {
      const body = await readJson(request, TEAM_MEDIA_JSON_LIMIT);
      const key = teamKeyOf(request);
      const row = (await loadTeamRow(db, key)) || (await createTeamRow(db, key));
      assertTeamCode(request, row.doc, key);
      const base64 = typeof body?.base64 === 'string' ? body.base64 : '';
      if (!/^[A-Za-z0-9+/]+={0,2}$/.test(base64) || base64.length < 64 || base64.length > TEAM_MEDIA_JSON_LIMIT) {
        fail('invalid_input', 400);
      }
      const id = await storeTeamMedia(db, cleanText(body?.kind, 20) || 'image', base64);
      // request.url carries the internal function origin; callers join this path with the public site instead.
      return jsonResponse({ id, url: `/functions/v1/app/api/team/media/${id}.png` });
    }

    if (path === '/api/team/schedule' && method === 'GET') {
      const key = teamKeyOf(request);
      const row = (await loadTeamRow(db, key)) || (await createTeamRow(db, key));
      assertTeamCode(request, row.doc, key);
      const doc = normalizeDoc(row.doc);
      const url = new URL(request.url);
      const days = Math.min(21, Math.max(1, Number(url.searchParams.get('days')) || 8));
      const duration = Math.min(240, Math.max(30, Number(url.searchParams.get('duration')) || 60));
      const memberIds = (url.searchParams.get('members') || '')
        .split(',')
        .map((id) => id.trim())
        .filter(Boolean);
      return jsonResponse(availabilityView(doc, { memberIds, duration, days }));
    }

    if (path === '/api/team/meetings' && method === 'POST') {
      const body = await readJson(request);
      const key = teamKeyOf(request);
      const row = (await loadTeamRow(db, key)) || (await createTeamRow(db, key));
      assertTeamCode(request, row.doc, key);
      const result = await withTeamState(db, key, (doc) => {
        const planned = planMeeting(doc, body);
        if (planned.error) fail('meeting_conflict', 409, { message: planned.error });
        return { meeting: planned.meeting };
      });
      return jsonResponse(result);
    }

    if (path.startsWith('/api/team/meetings/') && method === 'DELETE') {
      const id = path.slice('/api/team/meetings/'.length);
      const key = teamKeyOf(request);
      const row = await loadTeamRow(db, key);
      if (!row) fail('not_found', 404);
      assertTeamCode(request, row.doc, key);
      const removed = await withTeamState(db, key, (doc) => removeMeeting(doc, id));
      if (!removed) fail('not_found', 404);
      return jsonResponse({ ok: true });
    }

    if (path === '/api/team/timetable' && method === 'POST') {
      const body = await readJson(request, 256 * 1024);
      const key = teamKeyOf(request);
      const row = (await loadTeamRow(db, key)) || (await createTeamRow(db, key));
      assertTeamCode(request, row.doc, key);
      const memberId = cleanText(body?.memberId, 64);
      if (!memberId) fail('invalid_input', 400);
      const result = await withTeamState(db, key, (doc) => {
        const saved = replaceTimetable(doc, memberId, body?.entries);
        if (saved.error) fail('invalid_input', 400, { message: saved.error });
        return { timetable: saved.timetable };
      });
      return jsonResponse(result);
    }

    if (path === '/api/team/import' && method === 'POST') {
      const body = await readJson(request, 512 * 1024);
      const existing = await loadTeamRow(db);
      if (existing) assertTeamCode(request, existing.doc);
      if (existing && body?.overwrite !== true) fail('team_exists', 409);
      const doc = normalizeDoc(body?.doc);
      if (!doc.members.length || !doc.tasks.length) fail('invalid_input', 400);
      doc.team.code = requireTeamCode();
      if (existing) {
        rows(
          await db
            .from('team_state')
            .update({ doc, version: existing.version + 1, updated_at: nowIso() })
            .eq('id', existing.id)
            .eq('version', existing.version)
            .select('id'),
        );
        return jsonResponse({ ok: true, replaced: true, members: doc.members.length, tasks: doc.tasks.length });
      }
      rows(
        await db
          .from('team_state')
          .insert({ id: randomId(), team_key: TEAM_KEY, doc, version: 1, updated_at: nowIso() })
          .select('id'),
      );
      return jsonResponse({ ok: true, members: doc.members.length, tasks: doc.tasks.length });
    }

    const user = await authenticate(request, db);

    if (path === '/api/bootstrap' && method === 'GET') {
      return buildStateReply(await loadState(db, user));
    }

    if (path === '/api/state' && method === 'GET') {
      return buildStateReply(await loadState(db, user));
    }

    if (path === '/api/tasks' && method === 'POST') {
      const body = await readJson(request);
      const name = cleanText(body?.name, 80);
      if (!name) fail('invalid_input', 400);
      const duration = body?.durationMinutes == null ? null : durationOf(body.durationMinutes, null);
      if (body?.durationMinutes != null && duration == null) fail('invalid_input', 400);
      const item = {
        taskId: randomId(),
        name,
        note: cleanText(body?.note, 500),
        durationMinutes: duration,
        priority: normalizePriority(body?.priority, 'MEDIUM'),
        category: normalizeCategory(body?.category, 'UNSPECIFIED'),
        dueDate: normalizeDate(body?.dueDate, null),
        plannedDate: normalizeDate(body?.plannedDate, null),
        date: normalizeDate(body?.date, todayInShanghai()),
        startMinute: normalizeClock(body?.start),
      };
      const row = taskRowForInsert(user, item);
      rows(await db.from('tasks').insert(row).select('id'));
      if (body?.schedule === true && row.duration_minutes) {
        const state = await loadState(db, user, { includeChat: false });
        const tasksById = new Map([...state.tasks, row].map((task) => [task.id, task]));
        const date = item.date;
        // 过去的日子不自动排；任务本身照常留在清单里。
        if (date >= state.today) {
          const occupied = occupiedForDate(date, { segments: state.segments, fixedBlocks: state.fixedBlocks, tasksById });
          const earliestStartMinute = earliestStartOn(date, state);
          const result = planDay(
            [{ name: row.name, durationMinutes: row.duration_minutes, priority: row.priority, explicitStartMinute: item.startMinute, existingTaskId: row.id }],
            occupied,
            earliestStartMinute == null ? undefined : { earliestStartMinute },
          );
          const placement = result.placements[0];
          if (placement) {
            const segment = segmentRowForInsert(user, { ...item, taskId: row.id }, {
              id: randomId(),
              date,
              startMinute: placement.startMinute,
              endMinute: placement.endMinute,
              trackId: placement.trackId,
              nestedUnderId: placement.nestedUnderId,
            });
            rows(await db.from('segments').insert(segment).select('id'));
            if (!row.planned_date) {
              await db.from('tasks').update({ planned_date: date }).eq('id', row.id).eq('user_id', user.id);
            }
          }
        }
      }
      return buildStateReply(await loadState(db, user));
    }

    const taskMatch = path.match(/^\/api\/tasks\/([0-9a-f-]{36})$/);
    if (taskMatch && method === 'PATCH') {
      const body = await readJson(request);
      const task = rows(await db.from('tasks').select('*').eq('id', taskMatch[1]).eq('user_id', user.id).limit(1))?.[0];
      if (!task) fail('not_found', 404);
      const now = nowIso();
      const patch = { updated_at: now };
      if (body?.name != null) {
        const name = cleanText(body.name, 80);
        if (!name) fail('invalid_input', 400);
        patch.name = name;
      }
      if (body?.note != null) patch.note = cleanText(body.note, 500);
      if (body?.durationMinutes != null) {
        const duration = durationOf(body.durationMinutes, null);
        if (duration == null) fail('invalid_input', 400);
        patch.duration_minutes = duration;
      }
      if (body?.priority != null) patch.priority = normalizePriority(body.priority, task.priority);
      if (body?.category != null) patch.category = normalizeCategory(body.category, task.category);
      if (body?.dueDate !== undefined) patch.due_date = normalizeDate(body.dueDate, null);
      if (body?.plannedDate !== undefined) patch.planned_date = normalizeDate(body.plannedDate, null);
      if (body?.progress != null) {
        const progress = Number(body.progress);
        if (!Number.isFinite(progress) || progress < 0 || progress > 100) fail('invalid_input', 400);
        patch.progress = Math.round(progress);
        if (patch.progress >= 100) {
          patch.status = 'COMPLETED';
          patch.completed_at = now;
        } else if (task.status === 'NOT_STARTED') {
          patch.status = 'IN_PROGRESS';
        }
      }
      if (body?.status != null) {
        const status = String(body.status).toUpperCase();
        if (!['NOT_STARTED', 'IN_PROGRESS', 'COMPLETED', 'POSTPONED', 'CANCELLED'].includes(status)) fail('invalid_input', 400);
        patch.status = status;
        if (status === 'COMPLETED') {
          patch.progress = 100;
          patch.completed_at = now;
        } else {
          patch.completed_at = null;
        }
        if (status === 'CANCELLED') patch.progress = task.progress;
      }
      if (body?.actualMinutes != null) {
        const actual = durationOf(body.actualMinutes, null);
        if (actual == null) fail('invalid_input', 400);
        patch.actual_minutes = actual;
      }
      rows(await db.from('tasks').update(patch).eq('id', task.id).eq('user_id', user.id).select('id'));
      return buildStateReply(await loadState(db, user));
    }

    const taskScheduleMatch = path.match(/^\/api\/tasks\/([0-9a-f-]{36})\/schedule$/);
    if (taskScheduleMatch && method === 'POST') {
      const body = await readJson(request);
      const task = rows(
        await db.from('tasks').select('*').eq('id', taskScheduleMatch[1]).eq('user_id', user.id).limit(1),
      )?.[0];
      if (!task) fail('not_found', 404);
      if (!isActiveTask(task)) fail('task_closed', 409);
      if (!Number(task.duration_minutes)) fail('missing_duration', 422, { message: '先给这件事估个时长再排。' });
      const date = normalizeDate(body?.date, todayInShanghai());
      const pinned = body?.start == null || body?.start === '' ? null : normalizeClock(body.start);
      if (body?.start != null && body?.start !== '' && pinned == null) fail('invalid_input', 400);
      const state = await loadState(db, user, { includeChat: false });
      if (date < state.today) {
        return jsonResponse({ error: 'date_in_past', message: '那天已经过去了，换一天再排。' }, 422);
      }
      const tasksById = new Map(state.tasks.map((row) => [row.id, row]));
      const occupied = occupiedForDate(date, { segments: state.segments, fixedBlocks: state.fixedBlocks, tasksById });
      const earliestStartMinute = earliestStartOn(date, state);
      const result = planDay(
        [
          {
            name: task.name,
            durationMinutes: Number(task.duration_minutes),
            priority: task.priority,
            explicitStartMinute: pinned,
            existingTaskId: task.id,
          },
        ],
        occupied,
        earliestStartMinute == null ? undefined : { earliestStartMinute },
      );
      const placement = result.placements[0];
      if (!placement) {
        return jsonResponse(
          { error: 'no_room', message: '这一天没有合适的空档，换一天或先调整已有的安排。' },
          422,
        );
      }
      const segment = segmentRowForInsert(
        user,
        { taskId: task.id, startMinute: pinned },
        { ...placement, id: randomId(), date },
      );
      rows(await db.from('segments').insert(segment).select('id'));
      if (!task.planned_date) {
        rows(
          await db.from('tasks').update({ planned_date: date, updated_at: nowIso() }).eq('id', task.id).eq('user_id', user.id).select('id'),
        );
      }
      return jsonResponse({
        ...(await loadState(db, user)),
        placed: {
          date,
          startMinute: placement.startMinute,
          endMinute: placement.endMinute,
          exact: Boolean(placement.fromUser),
          // 点名时间没排上时，得说清是"已经过了"还是"被占了"——两者都叫"被占"是在撒谎。
          adjusted: placement.fromUser || pinned == null
            ? null
            : earliestStartMinute != null && pinned < earliestStartMinute
              ? 'past'
              : 'occupied',
        },
      });
    }

    const segmentMatch = path.match(/^\/api\/segments\/([0-9a-f-]{36})$/);
    if (segmentMatch && method === 'PATCH') {
      const body = await readJson(request);
      const segment = rows(await db.from('segments').select('*').eq('id', segmentMatch[1]).eq('user_id', user.id).limit(1))?.[0];
      if (!segment) fail('not_found', 404);
      const date = normalizeDate(body?.date, segment.on_date);
      const startMinute = body?.startMinute != null ? normalizeClock(body.startMinute) : Number(segment.start_minute);
      const endMinute = body?.endMinute != null ? normalizeClock(body.endMinute, { allowEnd: true }) : Number(segment.end_minute);
      const state = await loadState(db, user, { includeChat: false });
      const tasksById = new Map(state.tasks.map((task) => [task.id, task]));
      const occupied = occupiedForDate(date, { segments: state.segments, fixedBlocks: state.fixedBlocks, tasksById });
      const rejection = validatePlacement({ date, startMinute, endMinute, occupied, excludeSegmentId: segment.id });
      if (rejection) fail('placement_rejected', 422, { message: rejection });
      rows(
        await db.from('segments').update({
          on_date: date,
          start_minute: startMinute,
          end_minute: endMinute,
          track_id: cleanText(body?.trackId, 20) || segment.track_id,
          from_user: true,
          updated_at: nowIso(),
        }).eq('id', segment.id).eq('user_id', user.id).select('id'),
      );
      return buildStateReply(await loadState(db, user));
    }
    if (segmentMatch && method === 'DELETE') {
      const segment = rows(await db.from('segments').select('id').eq('id', segmentMatch[1]).eq('user_id', user.id).limit(1))?.[0];
      if (!segment) fail('not_found', 404);
      await db.from('segments').delete().eq('id', segment.id).eq('user_id', user.id);
      return buildStateReply(await loadState(db, user));
    }

    if (path === '/api/fixed-blocks' && method === 'POST') {
      const body = await readJson(request);
      const title = cleanText(body?.title, 40);
      if (!title) fail('invalid_input', 400);
      const kind = ['CLASS', 'FIXED', 'REST'].includes(String(body?.kind).toUpperCase()) ? String(body.kind).toUpperCase() : 'FIXED';
      const weekday = body?.weekday == null ? null : Number(body.weekday);
      const specificDate = normalizeDate(body?.date, null);
      if (!specificDate && (!Number.isFinite(weekday) || weekday < 1 || weekday > 7)) fail('invalid_input', 400);
      const startMinute = normalizeClock(body?.startMinute);
      const endMinute = normalizeClock(body?.endMinute, { allowEnd: true });
      if (startMinute == null || endMinute == null || endMinute <= startMinute) fail('invalid_input', 400);
      rows(
        await db.from('fixed_blocks').insert({
          id: randomId(),
          user_id: user.id,
          title,
          kind,
          weekday: specificDate ? null : weekday,
          specific_date: specificDate,
          start_minute: startMinute,
          end_minute: endMinute,
          created_at: nowIso(),
        }).select('id'),
      );
      return buildStateReply(await loadState(db, user));
    }

    const blockMatch = path.match(/^\/api\/fixed-blocks\/([0-9a-f-]{36})$/);
    if (blockMatch && method === 'DELETE') {
      await db.from('fixed_blocks').delete().eq('id', blockMatch[1]).eq('user_id', user.id);
      return buildStateReply(await loadState(db, user));
    }

    const chatMatch = path.match(/^\/api\/chat\/([0-9a-f-]{36})$/);
    if (chatMatch && method === 'DELETE') {
      const message = rows(
        await db.from('chat_messages').select('id').eq('id', chatMatch[1]).eq('user_id', user.id).limit(1),
      )?.[0];
      if (!message) fail('not_found', 404);
      await db.from('chat_messages').delete().eq('id', message.id).eq('user_id', user.id);
      return buildStateReply(await loadState(db, user));
    }

    if (path === '/api/plan' && method === 'POST') {
      const body = await readJson(request);
      const mode = body?.mode === 'replan' ? 'replan' : 'fill';
      const horizonDays = Math.max(1, Math.min(14, Number(body?.horizonDays) || 7));
      const state = await loadState(db, user, { includeChat: false });
      const today = state.today;
      const nowMinute = state.nowMinute;
      const result = autoSchedule({
        tasks: state.tasks,
        segments: state.segments,
        fixedBlocks: state.fixedBlocks,
        fromDate: today,
        nowMinute,
        horizonDays,
        mode,
      });
      if (!result.createdSegments.length && !result.deletedSegmentIds.length) {
        return jsonResponse({ ...(await loadState(db, user)), planResult: { created: 0, deleted: 0, unscheduled: result.unscheduled, message: result.unscheduled.length ? '现有安排已经占满，没有可以新增的空档。' : '没有需要新排的任务。' } });
      }
      const deletedSegments = await selectByIds(db, 'segments', user, result.deletedSegmentIds);
      const createdRows = result.createdSegments.map((segment) => ({
        id: randomId(),
        user_id: user.id,
        task_id: segment.task_id,
        on_date: segment.date,
        start_minute: segment.start_minute,
        end_minute: segment.end_minute,
        track_id: segment.track_id,
        locked: false,
        from_user: false,
        nested_under_id: null,
        created_at: nowIso(),
        updated_at: nowIso(),
      }));
      for (const segment of result.deletedSegmentIds) {
        await db.from('segments').delete().eq('id', segment).eq('user_id', user.id);
      }
      for (const row of createdRows) {
        rows(await db.from('segments').insert(row).select('id'));
      }
      const operationId = randomId();
      rows(
        await db.from('operations').insert({
          id: operationId,
          user_id: user.id,
          kind: mode === 'replan' ? 'replan' : 'auto-plan',
          label: `${mode === 'replan' ? '重新排期' : '排进空档'}：新增 ${createdRows.length} 段`,
          before: { tasks: [], segments: deletedSegments },
          after: { tasks: [], segments: createdRows },
          created_at: nowIso(),
          undone_at: null,
        }).select('id'),
      );
      const refreshed = await loadState(db, user);
      return jsonResponse({
        ...refreshed,
        planResult: {
          created: createdRows.length,
          deleted: deletedSegments.length,
          unscheduled: result.unscheduled,
          message: `已新增 ${createdRows.length} 个时段${result.unscheduled.length ? `，${result.unscheduled.length} 项仍未排入` : ''}。`,
          operationId,
        },
      });
    }

    if (path === '/api/assistant' && method === 'POST') {
      const body = await readJson(request);
      const message = cleanText(body?.message, 1200);
      const activeDate = normalizeDate(body?.date, todayInShanghai());
      if (!message) fail('invalid_input', 400);
      await appendChat(db, user, 'user', message);
      // Chat is part of the model's context: a clarification like "13:00 排哪个任务"
      // must survive into the next turn even when no draft was started.
      const state = await loadState(db, user);
      // loadState hides drafts past their shelf life; this route still needs to see
      // the newest pending row so a late "确认" gets an expiry answer instead of silence.
      const latestPending =
        rows(
          await db
            .from('proposals')
            .select('id,payload,created_at')
            .eq('user_id', user.id)
            .eq('status', 'pending')
            .order('created_at', { ascending: false })
            .limit(1),
        )?.[0] || null;
      let pending = latestPending;
      if (pending && draftExpired(pending)) {
        await db
          .from('proposals')
          .update({ status: 'expired', consumed_at: nowIso() })
          .eq('id', pending.id)
          .eq('user_id', user.id)
          .eq('status', 'pending');
        if (chatConfirmIntent(message) || chatCancelIntent(message)) {
          const reply = '上次的草案已过期，没有写入。需要的话再说一次。';
          await appendChat(db, user, 'assistant', reply);
          return jsonResponse({ reply, source: 'local', proposal: null });
        }
        pending = null;
      }
      if (pending && chatCancelIntent(message)) {
        await db
          .from('proposals')
          .update({ status: 'cancelled', consumed_at: nowIso() })
          .eq('id', pending.id)
          .eq('user_id', user.id)
          .eq('status', 'pending');
        const reply = '已取消，没有写入。';
        await appendChat(db, user, 'assistant', reply);
        return jsonResponse({ reply, source: 'local', proposal: null });
      }
      if (pending && chatConfirmIntent(message)) {
        const claimed = rows(
          await db
            .from('proposals')
            .update({ status: 'executing', consumed_at: nowIso() })
            .eq('id', pending.id)
            .eq('user_id', user.id)
            .eq('status', 'pending')
            .select('id'),
        );
        if (claimed?.length) {
          const executed = await executeProposal(db, user, { id: pending.id, payload: pending.payload });
          return jsonResponse({
            ...(await loadState(db, user)),
            executed,
            reply: executed.reply,
            source: 'local',
            proposal: null,
          });
        }
      }
      const dailyLimit = envInt('REPLAND_AI_DAILY_LIMIT', AI_DAILY_LIMIT_DEFAULT);
      const used = await db
        .from('ai_usage')
        .select('id')
        .eq('user_id', user.id)
        .gte('created_at', new Date(Date.now() - 86_400_000).toISOString())
        .limit(dailyLimit + 1);
      const usedCount = rows(used)?.length ?? dailyLimit + 1;
      const globalLimit = envInt('REPLAND_AI_GLOBAL_DAILY_LIMIT', 0);
      let globalBlocked = false;
      if (globalLimit > 0) {
        const globalUsed = rows(
          await db
            .from('ai_usage')
            .select('id')
            .gte('created_at', new Date(Date.now() - 86_400_000).toISOString())
            .limit(globalLimit + 1),
        )?.length ?? 0;
        globalBlocked = globalUsed >= globalLimit;
      }
      const aiAllowed = Boolean(aiKey()) && usedCount < dailyLimit && !globalBlocked;
      // The model decides what a sentence is. These keyword commands only stand in
      // when there is no model to ask, so a regex can never outrank it again.
      if (!aiAllowed && isSpreadMessage(message)) {
        const spreadDate = localDateWords(message, activeDate) || activeDate;
        const built = await buildSpreadProposal(db, user, state, {
          activeDate,
          date: spreadDate,
          gapMinutes: spreadGapMinutes(message),
          pending,
        });
        if (built.error) {
          await appendChat(db, user, 'assistant', built.error);
          return jsonResponse({ reply: built.error, source: 'clarify', proposal: null });
        }
        if (!built.proposal) {
          await appendChat(db, user, 'assistant', built.reply);
          return jsonResponse({ reply: built.reply, source: 'local', proposal: null });
        }
        return jsonResponse({ reply: built.proposal.reply, source: 'local', proposal: built.proposal });
      }
      if (!aiAllowed && !pending && timeOnlyMessage(message)) {
        const clock = localTimeFromMessage(message);
        const when = localDateWords(message, state.today);
        const dayLabel = when == null || when === state.today ? '今天' : when === addDays(state.today, 1) ? '明天' : `${Number(when.slice(5, 7))}月${Number(when.slice(8, 10))}日`;
        const reply = `好的，${dayLabel} ${minutesToClock(clock)} 开始。要排哪个任务？`;
        await appendChat(db, user, 'assistant', reply);
        return jsonResponse({ reply, source: 'clarify', proposal: null });
      }
      const facts = factBundle(state, activeDate);
      // The model owns language judgment; this reading owns calendar slots and the
      // review/postpone protocol, so a date or duration is never invented.
      const rulePlan = buildRulePlan({ message, today: state.today, tasks: state.tasks });
      let parsed = null;
      let source = 'ai';
      let aiEngaged = false;
      // The user already named the task. If the model answers this with a question
      // instead of the create, the sentence must still become the task it asked for.
      const explicitCreateFallback = () => {
        if (!isExplicitCreateMessage(message)) return null;
        const local = localPlanFromMessage(message, { tasks: state.tasks, segments: state.segments, activeDate });
        if (!local?.items?.length) return null;
        const localItems = normalizeAiItems(local, { tasks: state.tasks, activeDate, message }).items;
        return localItems.length ? { intent: 'plan', items: localItems, say: '' } : null;
      };
      if (aiAllowed) {
        const history = (state.chat || [])
          .slice(0, -1)
          .slice(-12)
          .map((entry) => `${entry.role === 'user' ? '用户' : '小满'}：${cleanText(entry.content, 200)}`)
          .join('\n');
        const userPrompt = [
          `今天是 ${state.today}（${weekdayLabel(state.today)}），用户正在看 ${activeDate}（${weekdayLabel(activeDate)}），当前时间 ${minutesToClock(state.nowMinute)}。`,
          '',
          '当前这一天的事实：',
          facts.briefing,
          '',
          '未来七天（按天列出已排和空档，用于回答“哪天有空/这周还剩多少时间”）：',
          facts.horizonLines.join('\n'),
          facts.horizonTotalLine,
          '',
          '待办任务：',
          facts.taskLines,
          '',
          '已排时段（真实日程；只表示占用的时间，完成与否看任务状态）：',
          facts.scheduleLines,
          '',
          '固定安排（每周重复或单日锁定，排期会自动避开；取消或修改日常安排时看这里）：',
          facts.routineLines,
          '',
          '已完成（近 7 天）：',
          facts.completedLines,
          '',
          '已逾期：',
          facts.overdueLines,
          '',
          '专注记录：',
          facts.focusLine,
          ...(pending ? ['', '待确认草案（用户还没确认，可能正在修改；最新一句说了什么就以它为准）：', draftDigest(pending)] : []),
          ...(history ? ['', '最近的对话（从旧到新，最后一条是上一轮）：', history] : []),
          '',
          `用户这句话：${message}`,
        ].join('\n');
        try {
          await recordAiUsage(db, user);
          let raw = await callAgnes({ system: systemPrompt(), user: userPrompt });
          aiEngaged = true;
          let payload = extractJson(raw);
          if (!payload) {
            // The model answered outside the JSON contract. Its own words still win;
            // otherwise ask it once more before falling back to anything local.
            const prose = cleanText(raw, 800);
            if (prose && !prose.trim().startsWith('{')) {
              await appendChat(db, user, 'assistant', prose);
              return jsonResponse({ reply: prose, source: 'ai', proposal: null });
            }
            raw = await callAgnes({
              system: systemPrompt(),
              user: `${userPrompt}\n\n你上一次的回复不是可解析的 JSON。这一次只输出一个 JSON 对象，不要任何解释或代码围栏。`,
            });
            payload = extractJson(raw);
            if (!payload) {
              const retryProse = cleanText(raw, 800);
              if (retryProse && !retryProse.trim().startsWith('{')) {
                await appendChat(db, user, 'assistant', retryProse);
                return jsonResponse({ reply: retryProse, source: 'ai', proposal: null });
              }
            }
          }
          if (payload) {
            const normalized = normalizeAiItems(payload, { tasks: state.tasks, activeDate, message });
            if (normalized.intent === 'query' || normalized.intent === 'clarify') {
              const fallback = normalized.intent === 'clarify' ? explicitCreateFallback() : null;
              if (fallback) parsed = fallback;
              else if (normalized.intent === 'clarify' && !normalized.blocked && rulePlan.items.length && !rulePlan.mustAsk) {
                // The model hesitated, but the sentence still has a safe deterministic
                // reading. Use it instead of making the user repeat a clear sentence.
                parsed = { intent: rulePlan.intent, items: rulePlan.items, say: '' };
                source = 'local';
              } else {
                const reply = normalized.say || (normalized.intent === 'query' ? '我查了一下，但目前没有可回答的内容。' : rulePlan.mustAsk ? ruleClarifyReply(rulePlan.reasons) : '可以再补充一点吗？');
                await appendChat(db, user, 'assistant', reply);
                return jsonResponse({ reply, source: normalized.intent === 'clarify' ? 'clarify' : 'ai', proposal: null });
              }
            }
            // "排开点" is a shape-of-the-day request, never new work. If the model
            // still answered with fresh creates, treat it as the spread it is.
            const spreadTurn =
              normalized.intent === 'spread' ||
              (normalized.intent === 'plan' && isSpreadMessage(message) && !normalized.items.some((item) => item.taskId || item.move));
            if (spreadTurn) {
              const built = await buildSpreadProposal(db, user, state, {
                activeDate,
                date: localDateWords(message, activeDate) || activeDate,
                gapMinutes: normalized.gapMinutes || spreadGapMinutes(message),
                pending,
              });
              if (built.proposal) {
                return jsonResponse({ reply: built.proposal.reply, source: 'ai', proposal: built.proposal });
              }
              const reply = built.error || built.reply || '这天没有需要排开的安排。';
              await appendChat(db, user, 'assistant', reply);
              return jsonResponse({ reply, source: built.error ? 'clarify' : 'ai', proposal: null });
            }
            if (normalized.items.length) {
              const adjusted = applyAiOverrides(normalized.items, rulePlan, message);
              parsed = {
                intent: normalized.intent,
                items: adjusted.items,
                // A corrected date or a protocol rewrite makes the model's own
                // wording wrong; let the proposal summary speak instead.
                say: adjusted.changed ? '' : normalized.say,
              };
              if (adjusted.protocol) source = 'local';
            }
            if (!parsed) {
              const fallback = explicitCreateFallback();
              if (fallback) parsed = fallback;
              else if (rulePlan.items.length && !rulePlan.mustAsk) {
                parsed = { intent: rulePlan.intent, items: rulePlan.items, say: '' };
                source = 'local';
              } else if (normalized.say) {
                await appendChat(db, user, 'assistant', normalized.say);
                return jsonResponse({ reply: normalized.say, source: 'ai', proposal: null });
              } else {
                const reply = rulePlan.mustAsk ? ruleClarifyReply(rulePlan.reasons) : '这句话我没抓准。你是想安排什么，安排在什么时候？';
                await appendChat(db, user, 'assistant', reply);
                return jsonResponse({ reply, source: 'clarify', proposal: null });
              }
            }
          }
        } catch (error) {
          if (!(error instanceof AppError)) throw error;
          // Fall through to the local parser; a dead model should not swallow a clear sentence.
        }
      }
      if (aiEngaged && !parsed) parsed = explicitCreateFallback();
      if (aiEngaged && !parsed) {
        const reply = '刚才那句我没能整理成可执行的安排。可以再说得具体一点，或者拆成两步告诉我。';
        await appendChat(db, user, 'assistant', reply);
        return jsonResponse({ reply, source: 'clarify', proposal: null });
      }
      // A reply that answers the previous question often omits the time on purpose.
      const carry = carryClarification(state.chat, message);
      const effectiveMessage = carry ? `${message} ${carry.clock}` : message;
      if (!parsed) {
        const revision = draftRevision(message, pending, { activeDate });
        if (revision) {
          if (revision.kind === 'drop') {
            await db
              .from('proposals')
              .update({ status: 'cancelled', consumed_at: nowIso() })
              .eq('id', pending.id)
              .eq('user_id', user.id)
              .eq('status', 'pending');
            const reply = '好，这份先不排了。想改哪里直接说。';
            await appendChat(db, user, 'assistant', reply);
            return jsonResponse({ reply, source: 'local', proposal: null });
          }
          const target = revision.target;
          const revised = await buildProposal(db, user, state, {
            activeDate,
            intent: 'plan',
            items: [
              {
                name: target.name,
                taskId: target.existingTask ? target.taskId : null,
                action: target.existingTask ? 'schedule' : 'create',
                move: Boolean(target.existingTask),
                durationMinutes: revision.durationMinutes,
                priority: target.priority,
                category: target.category,
                date: revision.date,
                start: revision.start,
              },
            ],
            say: '好，按你说的改。',
            source: 'local',
          });
          if (revised.error) {
            await appendChat(db, user, 'assistant', revised.error);
            return jsonResponse({ reply: revised.error, source: 'clarify', proposal: null });
          }
          return jsonResponse({ reply: revised.proposal.reply, source: 'local', proposal: revised.proposal });
        }
        const localQuery = answerLocalQuery(message, state, activeDate);
        if (localQuery) {
          await appendChat(db, user, 'assistant', localQuery);
          return jsonResponse({ reply: localQuery, source: 'local-query', proposal: null });
        }
        // Explicit "create a task named X" wording has its own parser; everything
        // else goes through the deterministic reading first.
        const explicit = isExplicitCreateMessage(effectiveMessage)
          ? localPlanFromMessage(effectiveMessage, { tasks: state.tasks, segments: state.segments, activeDate })
          : null;
        const ruleLocal = effectiveMessage === message
          ? rulePlan
          : buildRulePlan({ message: effectiveMessage, today: state.today, tasks: state.tasks });
        if (explicit) {
          parsed = {
            intent: explicit.intent,
            items: normalizeAiItems(explicit, { tasks: state.tasks, activeDate, message }).items,
            say: '',
          };
          source = 'local';
        } else if (ruleLocal.mustAsk) {
          // No safe reading without the model: ask instead of guessing.
          const reply = ruleClarifyReply(ruleLocal.reasons);
          await appendChat(db, user, 'assistant', reply);
          return jsonResponse({ reply, source: 'clarify', proposal: null });
        } else if (ruleLocal.intent === 'query') {
          const reply = '这句我理解成在问安排。想让我先列一下今天的空档，还是把哪件事挪一挪？';
          await appendChat(db, user, 'assistant', reply);
          return jsonResponse({ reply, source: 'local-query', proposal: null });
        } else if (ruleLocal.items.length) {
          parsed = { intent: ruleLocal.intent, items: ruleLocal.items, say: '' };
          source = 'local';
        } else {
          const local = localPlanFromMessage(effectiveMessage, {
            tasks: state.tasks,
            segments: state.segments,
            activeDate,
          });
          if (local) {
            parsed = {
              intent: local.intent,
              items: normalizeAiItems(local, { tasks: state.tasks, activeDate, message }).items,
              say: '',
            };
            source = 'local';
          }
        }
      }
      if (!parsed || !parsed.items.length) {
        const clock = localTimeFromMessage(effectiveMessage);
        if (clock != null) {
          // The sentence said when, not what. Ask instead of inventing a task.
          const reply = `好的，${minutesToClock(clock)} 开始。要排哪个任务？`;
          await appendChat(db, user, 'assistant', reply);
          return jsonResponse({ reply, source: 'clarify', proposal: null });
        }
        const reason = globalBlocked
          ? '今天的体验额度已经用完了，明天再来试试。也可以先在任务页手动添加。'
          : usedCount >= dailyLimit
            ? '你今天的一句话额度已经用完了，明天再来，或者先在任务页手动添加。'
            : 'AI 暂时没有接通，这句话我也没能用本地规则接住。可以换一种说法，或先手动添加任务。';
        await appendChat(db, user, 'assistant', reason);
        return jsonResponse({ reply: reason, source: 'error', proposal: null });
      }
      const built = await buildProposal(db, user, state, {
        activeDate,
        intent: parsed.intent || 'plan',
        items: parsed.items,
        say: parsed.say,
        source,
        message: effectiveMessage,
      });
      if (built.error) {
        await appendChat(db, user, 'assistant', built.error);
        return jsonResponse({ reply: built.error, source: 'clarify', proposal: null });
      }
      return jsonResponse({ reply: built.proposal.reply, source, proposal: built.proposal });
    }

    if (path === '/api/assistant/confirm' && method === 'POST') {
      const body = await readJson(request);
      const proposalId = typeof body?.proposalId === 'string' && /^[0-9a-f-]{36}$/.test(body.proposalId) ? body.proposalId : null;
      if (!proposalId) fail('invalid_input', 400);
      const proposal = rows(
        await db.from('proposals').select('*').eq('id', proposalId).eq('user_id', user.id).limit(1),
      )?.[0];
      if (!proposal) fail('not_found', 404);
      if (proposal.status === 'done') return buildStateReply(await loadState(db, user));
      if (proposal.status !== 'pending') fail('proposal_unavailable', 409);
      const claimed = rows(
        await db
          .from('proposals')
          .update({ status: 'executing', consumed_at: nowIso() })
          .eq('id', proposal.id)
          .eq('user_id', user.id)
          .eq('status', 'pending')
          .select('id'),
      );
      if (!claimed?.length) fail('proposal_unavailable', 409);
      const result = await executeProposal(db, user, proposal);
      const refreshed = await loadState(db, user);
      return jsonResponse({ ...refreshed, executed: result });
    }

    if (path === '/api/assistant/cancel' && method === 'POST') {
      const body = await readJson(request);
      const proposalId = typeof body?.proposalId === 'string' ? body.proposalId : null;
      if (proposalId) {
        await db.from('proposals').update({ status: 'cancelled', consumed_at: nowIso() }).eq('id', proposalId).eq('user_id', user.id).eq('status', 'pending');
      }
      return buildStateReply(await loadState(db, user));
    }

    if (path === '/api/undo' && method === 'POST') {
      const label = await undoOperation(db, user);
      return jsonResponse({ ...(await loadState(db, user)), undoLabel: label });
    }

    if (path === '/api/focus/start' && method === 'POST') {
      const body = await readJson(request);
      const taskId = typeof body?.taskId === 'string' ? body.taskId : null;
      const task = taskId ? rows(await db.from('tasks').select('id,name').eq('id', taskId).eq('user_id', user.id).limit(1))?.[0] : null;
      if (!task) fail('not_found', 404);
      const active = rows(await db.from('focus_sessions').select('id').eq('user_id', user.id).is('ended_at', null).limit(1))?.[0];
      if (active) fail('focus_running', 409);
      rows(
        await db.from('focus_sessions').insert({
          id: randomId(),
          user_id: user.id,
          task_id: task.id,
          started_at: nowIso(),
          ended_at: null,
          net_minutes: null,
        }).select('id'),
      );
      return buildStateReply(await loadState(db, user));
    }

    if (path === '/api/focus/end' && method === 'POST') {
      const body = await readJson(request);
      const sessionId = typeof body?.sessionId === 'string' ? body.sessionId : null;
      if (!sessionId) fail('invalid_input', 400);
      const session = rows(await db.from('focus_sessions').select('*').eq('id', sessionId).eq('user_id', user.id).limit(1))?.[0];
      if (!session) fail('not_found', 404);
      if (session.ended_at) return buildStateReply(await loadState(db, user));
      const startedAt = new Date(session.started_at).getTime();
      const elapsed = Math.max(1, Math.min(1440, Math.round((Date.now() - startedAt) / 60_000)));
      const provided = Number(body?.minutes);
      const minutes = Number.isFinite(provided) && provided >= 1 && provided <= 1440 ? Math.round(provided) : elapsed;
      rows(
        await db.from('focus_sessions').update({ ended_at: nowIso(), net_minutes: minutes }).eq('id', session.id).eq('user_id', user.id).select('id'),
      );
      const progress = Number(body?.progress);
      const task = rows(await db.from('tasks').select('*').eq('id', session.task_id).eq('user_id', user.id).limit(1))?.[0];
      if (task) {
        const patch = {
          actual_minutes: (Number(task.actual_minutes) || 0) + minutes,
          updated_at: nowIso(),
        };
        if (Number.isFinite(progress) && progress >= 0 && progress <= 100) {
          patch.progress = Math.round(progress);
          if (patch.progress >= 100) {
            patch.status = 'COMPLETED';
            patch.completed_at = nowIso();
          }
        }
        if (!patch.status && task.status === 'NOT_STARTED') patch.status = 'IN_PROGRESS';
        await db.from('tasks').update(patch).eq('id', task.id).eq('user_id', user.id);
      }
      return buildStateReply(await loadState(db, user));
    }

    if (path === '/api/summary' && method === 'GET') {
      const state = await loadState(db, user, { includeChat: false });
      const since = addDays(state.today, -30);
      const tasks = state.tasks;
      const created = tasks.filter((task) => String(task.created_at).slice(0, 10) >= since);
      const completed = tasks.filter((task) => task.status === 'COMPLETED' && String(task.completed_at || '').slice(0, 10) >= since);
      const active = tasks.filter(isActiveTask);
      const overdue = active.filter((task) => task.due_date && task.due_date < state.today);
      const focusMinutes = state.focusSessions
        .filter((session) => String(session.started_at).slice(0, 10) >= since && session.net_minutes)
        .reduce((total, session) => total + Number(session.net_minutes), 0);
      const segments = state.segments.filter((segment) => segment.on_date >= since && segment.on_date <= state.today);
      const byCategory = {};
      for (const task of completed) byCategory[task.category] = (byCategory[task.category] || 0) + 1;
      const categoryText = Object.entries(byCategory)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 2)
        .map(([category, count]) => `${({ COURSE: '课业', EXTRACURRICULAR: '课外', OFFICE: '事务', LEISURE: '生活', UNSPECIFIED: '其他' })[category] || '其他'} ${count} 件`)
        .join('、');
      const parts = [];
      parts.push(`过去 30 天，你记录了 ${created.length} 件事，完成了 ${completed.length} 件${categoryText ? `，其中${categoryText}` : ''}。`);
      parts.push(focusMinutes > 0 ? `专注累计 ${Math.floor(focusMinutes / 60)} 小时 ${focusMinutes % 60} 分钟。` : '还没有专注记录。');
      parts.push(active.length ? `现在还有 ${active.length} 件待办${overdue.length ? `，其中 ${overdue.length} 件已经过了截止日` : ''}。` : '待办已经清空。');
      parts.push(segments.length ? `最近一个月共排进 ${segments.length} 个时段。` : '最近还没有排进日程的时段。');
      const text = parts.join('');
      return jsonResponse({
        text,
        facts: {
          created: created.length,
          completed: completed.length,
          active: active.length,
          overdue: overdue.length,
          focusMinutes,
          segments: segments.length,
          range: { from: since, to: state.today },
        },
      });
    }

    if (path === '/api/data' && method === 'DELETE') {
      const body = await readJson(request);
      if (body?.confirm !== 'DELETE') fail('invalid_input', 400);
      for (const table of ['segments', 'focus_sessions', 'proposals', 'operations', 'chat_messages', 'ai_usage', 'fixed_blocks', 'tasks']) {
        await db.from(table).delete().eq('user_id', user.id);
      }
      return buildStateReply(await loadState(db, user));
    }

    if (path === '/api/logout' && method === 'POST') {
      const token = bearer(request);
      if (token) {
        const hash = await sha256Hex(token);
        await db.from('sessions').delete().eq('token_hash', hash).eq('user_id', user.id);
      }
      return jsonResponse({ ok: true });
    }

    fail('not_found', 404);
  }

  return async (ctx) => {
    try {
      return await route(ctx);
    } catch (error) {
      if (error instanceof AppError) {
        return jsonResponse({ error: error.code, ...error.extra }, error.status);
      }
      const requestId = ctx?.requestId || randomId();
      console.error(`[repland] ${requestId} ${error?.name || 'Error'}: ${error?.message || String(error)}`);
      return jsonResponse({ error: 'internal_error', requestId }, 500);
    }
  };
}
