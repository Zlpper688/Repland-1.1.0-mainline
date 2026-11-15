// Deterministic planning core ported from Repland's ChatDayPlanner and PlanGenerator.
// The model understands the sentence; this module decides where things actually go.

export const QUANTUM_MINUTES = 30;
export const DAY_START_MINUTE = 8 * 60;
export const DAY_END_MINUTE = 22 * 60;
export const SLEEP_START_MINUTE = 23 * 60;
export const SLEEP_END_MINUTE = 7 * 60;
export const MAX_PARALLEL_TRACKS = 4;
/** 草案从生成到确认之间还要留几分钟，落点不能选中"此刻之前"。 */
export const PLACEMENT_LEAD_MINUTES = 5;

/**
 * 今天最早能开始的时间：此刻 + 一点缓冲，向上取到 5 分钟。
 * 空档、点名时间、嵌套落点都要过这一关——已经过去的小时不叫空档。
 */
export function earliestPlacementMinute(nowMinute) {
  const safe = Number.isFinite(Number(nowMinute)) ? Math.max(0, Number(nowMinute)) : 0;
  return Math.ceil((safe + PLACEMENT_LEAD_MINUTES) / 5) * 5;
}

const PRIORITY_SCORE = { REQUIRED: 100, HIGH: 80, MEDIUM: 50, LOW: 20, UNSPECIFIED: 50 };
const CATEGORY_WEIGHT = { COURSE: 30, EXTRACURRICULAR: 25, OFFICE: 25, LEISURE: 20, UNSPECIFIED: 0 };

export const PRIORITIES = ['REQUIRED', 'HIGH', 'MEDIUM', 'LOW'];
export const CATEGORIES = ['COURSE', 'EXTRACURRICULAR', 'OFFICE', 'LEISURE'];

export function pad2(n) {
  return String(n).padStart(2, '0');
}

export function minutesToClock(minutes) {
  const clamped = Math.max(0, Math.min(24 * 60, Math.round(minutes)));
  return `${pad2(Math.floor(clamped / 60))}:${pad2(clamped % 60)}`;
}

export function clockToMinutes(text) {
  if (typeof text !== 'string') return null;
  const match = text.trim().match(/^(\d{1,2})[:：](\d{2})$/);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return hour * 60 + minute;
}

export function parseDate(text) {
  const match = typeof text === 'string' && text.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!match) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  if (Number.isNaN(date.getTime())) return null;
  if (date.getUTCFullYear() !== Number(match[1]) || date.getUTCMonth() !== Number(match[2]) - 1 || date.getUTCDate() !== Number(match[3])) return null;
  return date;
}

export function formatDate(date) {
  return date.toISOString().slice(0, 10);
}

export function addDays(dateText, days) {
  const date = parseDate(dateText);
  if (!date) return dateText;
  date.setUTCDate(date.getUTCDate() + days);
  return formatDate(date);
}

export function diffDays(a, b) {
  const left = parseDate(a);
  const right = parseDate(b);
  if (!left || !right) return 0;
  return Math.round((left.getTime() - right.getTime()) / 86_400_000);
}

export function weekdayOf(dateText) {
  const date = parseDate(dateText);
  if (!date) return 1;
  const day = date.getUTCDay();
  return day === 0 ? 7 : day;
}

const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

export function weekdayLabel(dateText) {
  return `星期${WEEKDAY_LABELS[weekdayOf(dateText) - 1]}`;
}

export function todayInShanghai(now = new Date()) {
  try {
    return new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(now);
  } catch {
    return formatDate(now);
  }
}

export function nowMinuteInShanghai(now = new Date()) {
  try {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Shanghai',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).formatToParts(now);
    const hour = Number(parts.find((part) => part.type === 'hour')?.value ?? 0);
    const minute = Number(parts.find((part) => part.type === 'minute')?.value ?? 0);
    return hour * 60 + minute;
  } catch {
    return now.getHours() * 60 + now.getMinutes();
  }
}

export function roundUpToQuantum(minute) {
  return Math.ceil(minute / QUANTUM_MINUTES) * QUANTUM_MINUTES;
}

export function ceilDiv(value, divisor) {
  return Math.ceil(value / divisor);
}

export function taskRemainingMinutes(task) {
  const total = Number(task.duration_minutes);
  if (!Number.isFinite(total) || total <= 0) return 0;
  const progress = Number(task.progress);
  if (!Number.isFinite(progress) || progress <= 0 || progress >= 100) return total;
  return Math.max(0, Math.ceil((total * (100 - progress)) / 100));
}

export function isActiveTask(task) {
  return ['NOT_STARTED', 'IN_PROGRESS', 'POSTPONED'].includes(task.status);
}

function priorityOf(task) {
  return PRIORITY_SCORE[task.priority] ?? PRIORITY_SCORE.MEDIUM;
}

function categoryScore(category) {
  const weight = CATEGORY_WEIGHT[category] ?? 0;
  if (!weight) return 50;
  return (weight / Math.max(...Object.values(CATEGORY_WEIGHT))) * 100;
}

function deadlineScore(dateText, today) {
  if (!dateText) return 50;
  const days = diffDays(dateText, today);
  if (days < 0) return 100;
  if (days === 0) return 98;
  if (days === 1) return 92;
  if (days === 2) return 84;
  if (days <= 5) return 74;
  if (days <= 10) return 64;
  return 50;
}

export function rankTasks(tasks, today) {
  return [...tasks]
    .filter(isActiveTask)
    .map((task) => {
      const score = Math.round(
        priorityOf(task) * 0.35 +
          categoryScore(task.category) * 0.1 +
          deadlineScore(task.due_date, today) * 0.25 +
          deadlineScore(task.planned_date, today) * 0.15 +
          Math.min(100, 50 + (Number(task.postpone_count) || 0) * 15) * 0.1,
      );
      return { task, score };
    })
    .sort((a, b) => {
      const requiredDiff = Number(b.task.priority === 'REQUIRED') - Number(a.task.priority === 'REQUIRED');
      if (requiredDiff) return requiredDiff;
      if (b.score !== a.score) return b.score - a.score;
      const priorityDiff = priorityOf(b.task) - priorityOf(a.task);
      if (priorityDiff) return priorityDiff;
      if (a.task.due_date !== b.task.due_date) {
        if (!a.task.due_date) return 1;
        if (!b.task.due_date) return -1;
        return a.task.due_date.localeCompare(b.task.due_date);
      }
      const postponeDiff = (Number(b.task.postpone_count) || 0) - (Number(a.task.postpone_count) || 0);
      if (postponeDiff) return postponeDiff;
      return String(a.task.created_at ?? '').localeCompare(String(b.task.created_at ?? ''));
    })
    .map((entry) => entry.task);
}

export function fixedBlockOnDate(block, dateText) {
  if (block.date) return block.date === dateText;
  if (!Number.isFinite(Number(block.weekday))) return false;
  return Number(block.weekday) === weekdayOf(dateText);
}

export function occupiedForDate(dateText, { segments = [], fixedBlocks = [], tasksById = new Map() } = {}) {
  const entries = [];
  for (const block of fixedBlocks) {
    if (!fixedBlockOnDate(block, dateText)) continue;
    entries.push({
      id: block.id,
      kind: block.kind || 'FIXED',
      title: block.title,
      startMinute: Number(block.start_minute),
      endMinute: Number(block.end_minute),
      locked: true,
      unavoidable: true,
      taskId: null,
      trackId: 'fixed',
      taskClosed: false,
    });
  }
  for (const segment of segments) {
    if (segment.date !== dateText) continue;
    const task = tasksById.get(segment.task_id);
    entries.push({
      id: segment.id,
      kind: 'TASK',
      title: task?.name || '任务',
      startMinute: Number(segment.start_minute),
      endMinute: Number(segment.end_minute),
      locked: Boolean(segment.locked),
      unavoidable: task?.priority === 'REQUIRED',
      taskId: segment.task_id,
      trackId: segment.track_id || 'focus',
      taskClosed: task ? !isActiveTask(task) : false,
    });
  }
  return entries.sort((a, b) => a.startMinute - b.startMinute || a.endMinute - b.endMinute);
}

export function overlapsSleepWindow(startMinute, endMinute) {
  return startMinute < SLEEP_END_MINUTE || endMinute > SLEEP_START_MINUTE;
}

function rootEntries(entries) {
  const ids = new Set(entries.map((entry) => entry.id));
  return entries.filter((entry) => !entry.nestedUnderId || !ids.has(entry.nestedUnderId));
}

function nestedHosts(occupied) {
  return rootEntries(occupied)
    .filter((entry) => entry.kind === 'TASK' && !entry.taskClosed && !entry.locked && !entry.unavoidable)
    .sort((a, b) => a.startMinute - b.startMinute);
}

function laneFor(start, end, preferredTrack, hardBusy, occupiedByTrack, placements, allowHardBusy = false) {
  if (!allowHardBusy && hardBusy.some((entry) => start < entry.endMinute && end > entry.startMinute)) return null;
  const lanes = [...new Set([preferredTrack, 'focus', ...Array.from({ length: MAX_PARALLEL_TRACKS - 1 }, (_, index) => `parallel-${index + 2}`)].filter(Boolean))];
  return (
    lanes.find((lane) => {
      const occupied = occupiedByTrack[lane] || [];
      return (
        !occupied.some((entry) => start < entry.endMinute && end > entry.startMinute) &&
        !placements.some((placement) => placement.trackId === lane && start < placement.endMinute && end > placement.startMinute)
      );
    }) || null
  );
}

function placeAfterCursor(index, cursor, duration, preferredTrack, hardBusy, occupiedByTrack, placements, dayEndMinute, minDistance = 0) {
  const latestStart = dayEndMinute - duration;
  if (latestStart < cursor) return null;
  let start = cursor;
  while (start <= latestStart) {
    // A new item sitting one minute after an existing block reads as one long,
    // packed stretch; prefer a real breathing gap, but never at the cost of capacity.
    const clear = !minDistance || hardBusy.every(
      (entry) => start >= entry.endMinute + minDistance || start + duration <= entry.startMinute - minDistance,
    );
    if (clear) {
      const track = laneFor(start, start + duration, preferredTrack, hardBusy, occupiedByTrack, placements);
      if (track) return { index, startMinute: start, endMinute: start + duration, trackId: track, fromUser: false };
    }
    start += 5;
  }
  return null;
}

function placeNested(index, duration, preferredTrack, hosts, occupied, placements, startFloor = 0) {
  for (const host of hosts) {
    if (overlapsSleepWindow(host.startMinute, host.endMinute)) continue;
    const siblings = occupied.filter((entry) => entry.id !== host.id && entry.startMinute < host.endMinute && entry.endMinute > host.startMinute);
    let start = Math.max(host.startMinute, startFloor);
    while (start + duration <= host.endMinute) {
      const end = start + duration;
      const inside = start > host.startMinute || end < host.endMinute;
      if (
        inside &&
        !siblings.some((entry) => start < entry.endMinute && end > entry.startMinute) &&
        !placements.some((placement) => placement.nestedUnderId === host.id && start < placement.endMinute && end > placement.startMinute)
      ) {
        return {
          index,
          startMinute: start,
          endMinute: end,
          trackId: preferredTrack || host.trackId || 'focus',
          fromUser: false,
          nestedUnderId: host.id,
          nestedUnderTitle: host.title,
        };
      }
      start += 5;
    }
  }
  return null;
}

function freePlacement(index, duration, preferredTrack, cursor, hardBusy, occupiedByTrack, placements, hosts, occupied, dayEndMinute, bufferMinutes = 0, startFloor = 0) {
  return (
    placeAfterCursor(index, cursor, duration, preferredTrack, hardBusy, occupiedByTrack, placements, dayEndMinute, bufferMinutes) ||
    placeAfterCursor(index, cursor, duration, preferredTrack, hardBusy, occupiedByTrack, placements, dayEndMinute, 0) ||
    placeNested(index, duration, preferredTrack, hosts, occupied, placements, startFloor)
  );
}

function placePinned(index, start, duration, preferredTrack, hardBusy, occupiedByTrack, placements, excludeTaskId, dayEndMinute, startFloor = 0) {
  if (start < startFloor) return null;
  const end = start + duration;
  if (end > dayEndMinute) return null;
  const protectedHit = hardBusy.some(
    (entry) =>
      (!excludeTaskId || entry.taskId !== excludeTaskId) &&
      (entry.locked || entry.unavoidable) &&
      start < entry.endMinute &&
      end > entry.startMinute,
  );
  if (protectedHit) return null;
  const track = laneFor(start, end, preferredTrack, hardBusy, occupiedByTrack, placements, true);
  if (!track) return null;
  return { index, startMinute: start, endMinute: end, trackId: track, fromUser: true };
}

/**
 * Place a batch of candidates into one real day.
 * candidates: [{ name, durationMinutes, priority, category, preferredTrackId, explicitStartMinute, existingTaskId }]
 * occupied: TimelineEntry[] (fixed blocks + task segments)
 */
export function planDay(candidates, occupied, { gapMinutes = 15, bufferMinutes = 10, dayStartMinute = DAY_START_MINUTE, dayEndMinute = DAY_END_MINUTE, earliestStartMinute = null } = {}) {
  const hardBusy = occupied;
  const occupiedByTrack = {};
  for (const entry of occupied) {
    if (entry.kind !== 'TASK') continue;
    (occupiedByTrack[entry.trackId] ||= []).push(entry);
  }
  const placements = [];
  const unplaced = [];
  const hosts = nestedHosts(occupied);
  // 今天：已经过去的钟点不是空档。点名时间同样要过这一关，否则卡上会写一个写不进去的过去。
  const startFloor = Number.isFinite(Number(earliestStartMinute))
    ? Math.max(dayStartMinute, Number(earliestStartMinute))
    : dayStartMinute;
  let cursor = startFloor;

  const order = candidates
    .map((candidate, index) => ({ candidate, index }))
    .sort((a, b) => {
      const pinnedDiff = Number(b.candidate.explicitStartMinute != null) - Number(a.candidate.explicitStartMinute != null);
      if (pinnedDiff) return pinnedDiff;
      const priorityDiff = priorityOf({ priority: b.candidate.priority || 'MEDIUM' }) - priorityOf({ priority: a.candidate.priority || 'MEDIUM' });
      if (priorityDiff) return priorityDiff;
      const durationDiff = (b.candidate.durationMinutes || 0) - (a.candidate.durationMinutes || 0);
      if (durationDiff) return durationDiff;
      return a.index - b.index;
    });

  for (const { candidate, index } of order) {
    const duration = Number(candidate.durationMinutes);
    if (!Number.isFinite(duration) || duration <= 0) {
      unplaced.push({ index, reason: 'MISSING_DURATION' });
      continue;
    }
    const pinned = candidate.explicitStartMinute;
    let placement = null;
    if (Number.isFinite(pinned)) {
      placement = placePinned(
        index,
        Math.max(0, Math.min(pinned, 24 * 60 - duration)),
        duration,
        candidate.preferredTrackId,
        hardBusy,
        occupiedByTrack,
        placements,
        candidate.existingTaskId,
        24 * 60,
        startFloor,
      );
      if (!placement) {
        placement = freePlacement(
          index,
          duration,
          candidate.preferredTrackId,
          cursor,
          hardBusy,
          occupiedByTrack,
          placements,
          hosts,
          occupied,
          dayEndMinute,
          bufferMinutes,
          startFloor,
        );
      }
    } else {
      placement = freePlacement(
        index,
        duration,
        candidate.preferredTrackId,
        cursor,
        hardBusy,
        occupiedByTrack,
        placements,
        hosts,
        occupied,
        dayEndMinute,
        bufferMinutes,
        startFloor,
      );
    }
    if (!placement) {
      unplaced.push({ index, reason: 'NO_ROOM' });
    } else {
      placements.push(placement);
      if (!placement.nestedUnderId) cursor = Math.max(cursor, placement.endMinute + gapMinutes);
    }
  }

  return {
    placements: placements.sort((a, b) => a.startMinute - b.startMinute),
    unplaced: unplaced.sort((a, b) => a.index - b.index),
  };
}

/**
 * Re-lay one day's movable task segments with a wider gap ("排开点").
 *
 * Only pushes segments later, anchored on the earliest one, so the request never
 * silently pulls work into a different part of the day. Fixed blocks, locked and
 * REQUIRED segments and nested children stay put; a segment that cannot fit with
 * the requested gap keeps its current slot instead of being dropped.
 */
export function spreadDay(segments, occupied, { gapMinutes = 30, dayStartMinute = DAY_START_MINUTE, dayEndMinute = DAY_END_MINUTE } = {}) {
  const movable = segments
    .filter((segment) => !segment.locked && !segment.nestedUnderId && Number(segment.endMinute) > Number(segment.startMinute))
    .sort((a, b) => a.startMinute - b.startMinute || a.endMinute - b.endMinute);
  if (movable.length < 2) return { moves: [] };
  const movableIds = new Set(movable.map((segment) => segment.id));
  const fixed = occupied.filter((entry) => !movableIds.has(entry.id));
  const placed = [];
  const moves = [];
  let prevEnd = null;

  const fits = (start, end) =>
    start >= dayStartMinute &&
    end <= dayEndMinute &&
    !fixed.some((entry) => start < entry.endMinute && end > entry.startMinute) &&
    !placed.some((entry) => start < entry.endMinute && end > entry.startMinute);

  // Fixed blocks also take room in the day: a movable right after a class keeps its gap too.
  const chain = [
    ...fixed.map((entry) => ({ fixed: true, startMinute: entry.startMinute, endMinute: entry.endMinute })),
    ...movable.map((segment) => ({ fixed: false, segment, startMinute: segment.startMinute, endMinute: segment.endMinute })),
  ].sort((a, b) => a.startMinute - b.startMinute || a.endMinute - b.endMinute);

  for (const item of chain) {
    if (item.fixed) {
      prevEnd = prevEnd == null ? item.endMinute : Math.max(prevEnd, item.endMinute);
      continue;
    }
    const segment = item.segment;
    const duration = segment.endMinute - segment.startMinute;
    let start = null;
    for (const gap of [gapMinutes, Math.max(15, Math.round(gapMinutes / 2)), 5]) {
      const from = prevEnd == null
        ? Math.max(dayStartMinute, segment.startMinute)
        : Math.max(segment.startMinute, prevEnd + gap);
      for (let candidate = from; candidate + duration <= dayEndMinute; candidate += 5) {
        if (fits(candidate, candidate + duration)) {
          start = candidate;
          break;
        }
      }
      if (start != null) break;
    }
    if (start == null) start = segment.startMinute;
    placed.push({ startMinute: start, endMinute: start + duration });
    prevEnd = start + duration;
    if (start !== segment.startMinute) {
      moves.push({ segment, startMinute: start, endMinute: start + duration });
    }
  }
  return { moves };
}

export function freeWindows(dateText, occupied, { minMinutes = 30, dayStartMinute = DAY_START_MINUTE, dayEndMinute = DAY_END_MINUTE, fromMinute = null } = {}) {
  // 今天只算此刻之后的空档：把已经过去的小时算进"还剩多少"，用户照着安排就撞墙。
  const begin = Number.isFinite(Number(fromMinute))
    ? Math.max(dayStartMinute, Number(fromMinute))
    : dayStartMinute;
  const busy = occupied
    .filter((entry) => entry.kind !== 'TASK' || entry.locked || entry.unavoidable || entry.taskClosed)
    .map((entry) => ({ start: Math.max(begin, entry.startMinute), end: Math.min(dayEndMinute, entry.endMinute) }))
    .filter((entry) => entry.end > entry.start)
    .sort((a, b) => a.start - b.start);
  const windows = [];
  let cursor = begin;
  for (const entry of busy) {
    if (entry.start - cursor >= minMinutes) windows.push({ startMinute: cursor, endMinute: entry.start });
    cursor = Math.max(cursor, entry.end);
  }
  if (dayEndMinute - cursor >= minMinutes) windows.push({ startMinute: cursor, endMinute: dayEndMinute });
  return windows;
}

export function freeMinutes(dateText, occupied, options = {}) {
  return freeWindows(dateText, occupied, options).reduce((total, window) => total + window.endMinute - window.startMinute, 0);
}

function slotsForDate(dateText, { dayStartMinute, dayEndMinute, startMinute, occupied }) {
  const slots = [];
  const begin = Math.max(dayStartMinute, startMinute);
  for (let minute = begin; minute + QUANTUM_MINUTES <= dayEndMinute; minute += QUANTUM_MINUTES) {
    const end = minute + QUANTUM_MINUTES;
    if (occupied.some((entry) => minute < entry.endMinute && end > entry.startMinute)) continue;
    slots.push({ date: dateText, startMinute: minute, endMinute: end });
  }
  return slots;
}

/**
 * Schedule remaining work across a rolling window.
 * mode 'fill': keep every existing segment and only schedule the uncovered remainder.
 * mode 'replan': keep protected segments (locked / user-pinned / in-progress now) and rebuild the rest.
 */
export function autoSchedule({
  tasks,
  segments,
  fixedBlocks,
  fromDate,
  nowMinute,
  horizonDays = 7,
  mode = 'fill',
  dayStartMinute = DAY_START_MINUTE,
  dayEndMinute = DAY_END_MINUTE,
}) {
  const activeTasks = tasks.filter((task) => isActiveTask(task) && Number(task.duration_minutes) > 0);
  const tasksById = new Map(tasks.map((task) => [task.id, task]));
  const ranked = rankTasks(activeTasks, fromDate);

  const keptSegments = [];
  const deletedSegmentIds = [];
  for (const segment of segments) {
    const task = tasksById.get(segment.task_id);
    if (!task || !isActiveTask(task)) {
      keptSegments.push(segment);
      continue;
    }
    const protectedSegment =
      Boolean(segment.locked) ||
      Boolean(segment.from_user) ||
      (segment.date === fromDate && Number(segment.start_minute) <= nowMinute && Number(segment.end_minute) > nowMinute);
    if (mode === 'replan' && !protectedSegment) {
      deletedSegmentIds.push(segment.id);
    } else {
      keptSegments.push(segment);
    }
  }

  const keptMinutesByTask = new Map();
  for (const segment of keptSegments) {
    if (segment.date < fromDate) continue;
    const minutes = Number(segment.end_minute) - Number(segment.start_minute);
    keptMinutesByTask.set(segment.task_id, (keptMinutesByTask.get(segment.task_id) || 0) + minutes);
  }

  const created = [];
  const unscheduled = [];
  const assigned = [...keptSegments];
  const slotsByDate = new Map();

  for (const task of ranked) {
    const totalRemaining = taskRemainingMinutes(task);
    const alreadyScheduled = keptMinutesByTask.get(task.id) || 0;
    let remaining = Math.max(0, totalRemaining - alreadyScheduled);
    if (remaining <= 0) continue;
    const requiredSlots = ceilDiv(remaining, QUANTUM_MINUTES);
    const dueLimit = task.due_date && task.due_date < addDays(fromDate, horizonDays - 1) ? task.due_date : addDays(fromDate, horizonDays - 1);
    const selected = [];
    for (let offset = 0; offset < horizonDays && selected.length < requiredSlots; offset += 1) {
      const dateText = addDays(fromDate, offset);
      if (dateText > dueLimit) break;
      if (task.planned_date && dateText < task.planned_date) continue;
      let slots = slotsByDate.get(dateText);
      if (!slots) {
        const occupied = occupiedForDate(dateText, { segments: assigned, fixedBlocks, tasksById });
        slots = slotsForDate(dateText, {
          dayStartMinute,
          dayEndMinute,
          startMinute: offset === 0 ? Math.max(roundUpToQuantum(nowMinute), dayStartMinute) : dayStartMinute,
          occupied,
        });
        slotsByDate.set(dateText, slots);
      }
      for (const slot of slots) {
        if (selected.length >= requiredSlots) break;
        if (selected.some((picked) => picked.date === slot.date && picked.startMinute === slot.startMinute)) continue;
        if (assigned.some((segment) => segment.date === slot.date && slot.startMinute < Number(segment.end_minute) && slot.endMinute > Number(segment.start_minute))) continue;
        selected.push(slot);
      }
    }

    const merged = [];
    let current = null;
    for (const slot of selected.sort((a, b) => a.date.localeCompare(b.date) || a.startMinute - b.startMinute)) {
      if (current && slot.date === current.date && slot.startMinute === current.endMinute) {
        current.endMinute = slot.endMinute;
      } else {
        if (current) merged.push(current);
        current = { ...slot };
      }
    }
    if (current) merged.push(current);

    for (const piece of merged) {
      created.push({
        task_id: task.id,
        date: piece.date,
        start_minute: piece.startMinute,
        end_minute: piece.endMinute,
        track_id: 'focus',
        locked: false,
        from_user: false,
      });
      assigned.push({
        date: piece.date,
        start_minute: piece.startMinute,
        end_minute: piece.endMinute,
        task_id: task.id,
        track_id: 'focus',
        locked: false,
      });
      keptMinutesByTask.set(task.id, (keptMinutesByTask.get(task.id) || 0) + (piece.endMinute - piece.startMinute));
    }

    remaining -= merged.reduce((total, piece) => total + piece.endMinute - piece.startMinute, 0);
    if (remaining > 0) {
      const reason = task.due_date
        ? diffDays(task.due_date, fromDate) < horizonDays
          ? 'CAPACITY_BEFORE_DUE_DATE'
          : 'CAPACITY_IN_ROLLING_WINDOW'
        : 'CAPACITY_IN_ROLLING_WINDOW';
      unscheduled.push({ taskId: task.id, taskName: task.name, remainingMinutes: remaining, reason });
    }
  }

  return { createdSegments: created, deletedSegmentIds, unscheduled, keptSegments };
}

export function validatePlacement({
  date,
  startMinute,
  endMinute,
  occupied,
  excludeSegmentId,
}) {
  if (!parseDate(date)) return '日期格式不正确。';
  if (!Number.isFinite(startMinute) || !Number.isFinite(endMinute)) return '时间格式不正确。';
  if (startMinute < 0 || endMinute > 24 * 60 || endMinute <= startMinute) return '结束时间要晚于开始时间，并在当天之内。';
  for (const entry of occupied) {
    if (excludeSegmentId && entry.id === excludeSegmentId) continue;
    const overlap = startMinute < entry.endMinute && endMinute > entry.startMinute;
    if (!overlap) continue;
    const frames = (entry.startMinute <= startMinute && endMinute <= entry.endMinute) || (startMinute <= entry.startMinute && entry.endMinute <= endMinute);
    if (entry.kind !== 'TASK') return `「${entry.title}」是课程或固定安排，这段时间不能被占用。`;
    if (entry.locked || entry.unavoidable) return `「${entry.title}」已锁定或标为必须完成，不能压上去。`;
    if (!frames) return `跟「${entry.title}」只重叠一部分；整段包住、被包住，或完全错开。`;
  }
  return null;
}

export function dayBriefing({ dateText, occupied, tasks, freeWindowList }) {
  const lines = [`日期：${dateText}（${weekdayLabel(dateText)}）。`];
  const fixed = occupied.filter((entry) => entry.kind !== 'TASK');
  lines.push(fixed.length ? `固定安排：${fixed.slice(0, 8).map((entry) => `${entry.title} ${minutesToClock(entry.startMinute)}-${minutesToClock(entry.endMinute)}`).join('；')}` : '固定安排：无。');
  const scheduled = occupied.filter((entry) => entry.kind === 'TASK');
  lines.push(scheduled.length ? `已排任务：${scheduled.slice(0, 12).map((entry) => `${entry.title} ${minutesToClock(entry.startMinute)}-${minutesToClock(entry.endMinute)}`).join('；')}` : '已排任务：无。');
  lines.push(freeWindowList.length ? `30 分钟以上空档：${freeWindowList.slice(0, 6).map((window) => `${minutesToClock(window.startMinute)}-${minutesToClock(window.endMinute)}`).join('、')}` : '30 分钟以上空档：无。');
  const active = tasks.filter(isActiveTask);
  lines.push(active.length ? `待办任务：${active.slice(0, 20).map((task) => `${task.name}（${task.duration_minutes || '?'} 分钟，${task.priority || 'MEDIUM'}，${task.status === 'IN_PROGRESS' ? '进行中' : '未开始'}${task.progress ? `，已完成 ${task.progress}%` : ''}）`).join('；')}` : '待办任务：无。');
  return lines.join('\n');
}
