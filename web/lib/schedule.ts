import type { Category, FixedBlock, Priority, Segment, Task, TaskStatus } from './types';
import { weekdayIndex } from './date';

export const DAY_START = 8 * 60;
export const DAY_END = 22 * 60;

export const CATEGORY_META: Record<Category, { label: string; dot: string; soft: string; token: string }> = {
  COURSE: { label: '课业', dot: 'cat-course-dot', soft: 'cat-course', token: 'course' },
  EXTRACURRICULAR: { label: '课外', dot: 'cat-extra-dot', soft: 'cat-extra', token: 'extra' },
  OFFICE: { label: '事务', dot: 'cat-office-dot', soft: 'cat-office', token: 'office' },
  LEISURE: { label: '生活', dot: 'cat-leisure-dot', soft: 'cat-leisure', token: 'leisure' },
  UNSPECIFIED: { label: '其他', dot: 'cat-other-dot', soft: 'cat-other', token: 'other' },
};

export const PRIORITY_META: Record<Priority, { label: string; tone: string }> = {
  REQUIRED: { label: '必须完成', tone: 'priority-required' },
  HIGH: { label: '重要', tone: 'priority-high' },
  MEDIUM: { label: '一般', tone: 'priority-medium' },
  LOW: { label: '有空再说', tone: 'priority-low' },
};

export const STATUS_META: Record<TaskStatus, string> = {
  NOT_STARTED: '未开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  POSTPONED: '已推迟',
  CANCELLED: '已取消',
};

export const FIXED_META: Record<string, { label: string; className: string }> = {
  CLASS: { label: '课程', className: 'fixed-class' },
  FIXED: { label: '固定', className: 'fixed-fixed' },
  REST: { label: '休息', className: 'fixed-rest' },
};

export function isActive(task: Task): boolean {
  return task.status === 'NOT_STARTED' || task.status === 'IN_PROGRESS' || task.status === 'POSTPONED';
}

export function taskMap(tasks: Task[]): Map<string, Task> {
  return new Map(tasks.map((task) => [task.id, task]));
}

export function segmentsForDate(segments: Segment[], date: string): Segment[] {
  return segments.filter((segment) => segment.on_date === date).sort((a, b) => a.start_minute - b.start_minute);
}

export function fixedForDate(blocks: FixedBlock[], date: string): FixedBlock[] {
  const weekday = weekdayIndex(date);
  return blocks
    .filter((block) => (block.specific_date ? block.specific_date === date : block.weekday === weekday))
    .sort((a, b) => a.start_minute - b.start_minute);
}

export interface TimelineEvent {
  id: string;
  kind: 'task' | 'fixed';
  title: string;
  startMinute: number;
  endMinute: number;
  segment?: Segment;
  block?: FixedBlock;
  task?: Task;
  nested: boolean;
}

export function timelineEvents(
  date: string,
  segments: Segment[],
  fixedBlocks: FixedBlock[],
  tasks: Map<string, Task>,
): TimelineEvent[] {
  const fixed = fixedForDate(fixedBlocks, date).map<TimelineEvent>((block) => ({
    id: block.id,
    kind: 'fixed',
    title: block.title,
    startMinute: block.start_minute,
    endMinute: block.end_minute,
    block,
    nested: false,
  }));
  const scheduled = segmentsForDate(segments, date).map<TimelineEvent>((segment) => {
    const task = tasks.get(segment.task_id);
    return {
      id: segment.id,
      kind: 'task',
      title: task?.name || '已取消的任务',
      startMinute: segment.start_minute,
      endMinute: segment.end_minute,
      segment,
      task,
      nested: Boolean(segment.nested_under_id),
    };
  });
  return [...fixed, ...scheduled].sort((a, b) => a.startMinute - b.startMinute || a.endMinute - b.endMinute);
}

export interface LaidOutEvent extends TimelineEvent {
  column: number;
  columns: number;
}

/** Fixed blocks stay full width; overlapping task segments split into columns. */
export function layoutEvents(events: TimelineEvent[]): LaidOutEvent[] {
  const fixed = events.filter((event) => event.kind === 'fixed');
  const tasks = events.filter((event) => event.kind === 'task').sort((a, b) => a.startMinute - b.startMinute || a.endMinute - b.endMinute);
  const groups: TimelineEvent[][] = [];
  let current: TimelineEvent[] = [];
  let groupEnd = -1;
  for (const event of tasks) {
    if (current.length && event.startMinute >= groupEnd) {
      groups.push(current);
      current = [];
      groupEnd = -1;
    }
    current.push(event);
    groupEnd = Math.max(groupEnd, event.endMinute);
  }
  if (current.length) groups.push(current);

  const laidOut: LaidOutEvent[] = fixed.map((event) => ({ ...event, column: 0, columns: 1 }));
  for (const group of groups) {
    const ends: number[] = [];
    const placed = group.map((event) => {
      let column = ends.findIndex((end) => end <= event.startMinute);
      if (column === -1) {
        column = ends.length;
        ends.push(event.endMinute);
      } else {
        ends[column] = event.endMinute;
      }
      return { event, column };
    });
    const columns = Math.max(1, ends.length);
    for (const entry of placed) laidOut.push({ ...entry.event, column: entry.column, columns });
  }
  return laidOut;
}

export interface FreeWindow {
  startMinute: number;
  endMinute: number;
}

export function freeWindows(
  date: string,
  segments: Segment[],
  fixedBlocks: FixedBlock[],
  fromMinute?: number | null,
): FreeWindow[] {
  // 今天只列此刻之后的空档：把上午列成"可排"，点一下就会排进已经过去的时间。
  const begin = Math.max(DAY_START, Number.isFinite(Number(fromMinute)) ? Number(fromMinute) : DAY_START);
  const busy = [
    ...fixedForDate(fixedBlocks, date).map((block) => ({ start: block.start_minute, end: block.end_minute })),
    ...segmentsForDate(segments, date).map((segment) => ({ start: segment.start_minute, end: segment.end_minute })),
  ]
    .map((entry) => ({ start: Math.max(begin, entry.start), end: Math.min(DAY_END, entry.end) }))
    .filter((entry) => entry.end > entry.start)
    .sort((a, b) => a.start - b.start);
  const windows: FreeWindow[] = [];
  let cursor = begin;
  for (const entry of busy) {
    if (entry.start - cursor >= 30) windows.push({ startMinute: cursor, endMinute: entry.start });
    cursor = Math.max(cursor, entry.end);
  }
  if (DAY_END - cursor >= 30) windows.push({ startMinute: cursor, endMinute: DAY_END });
  return windows;
}

export function freeMinutes(windows: FreeWindow[]): number {
  return windows.reduce((total, window) => total + window.endMinute - window.startMinute, 0);
}

export function dayStats(date: string, segments: Segment[], tasks: Map<string, Task>) {
  const list = segmentsForDate(segments, date);
  const active = list.filter((segment) => {
    const task = tasks.get(segment.task_id);
    return task && isActive(task);
  });
  const completed = list.filter((segment) => tasks.get(segment.task_id)?.status === 'COMPLETED');
  const planned = list.reduce((total, segment) => total + segment.end_minute - segment.start_minute, 0);
  return { count: active.length, completed: completed.length, plannedMinutes: planned };
}

export function nextFreeStart(segments: Segment[], fixedBlocks: FixedBlock[], date: string, nowMinute: number): number {
  const windows = freeWindows(date, segments, fixedBlocks);
  const window = windows.find((entry) => entry.endMinute > nowMinute);
  if (!window) return DAY_START;
  return Math.max(window.startMinute, Math.ceil(nowMinute / 30) * 30);
}

export function taskColor(task: Task | undefined): (typeof CATEGORY_META)[Category] {
  return CATEGORY_META[task?.category || 'UNSPECIFIED'];
}
