import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowRight,
  CalendarPlus,
  ChevronLeft,
  ChevronRight,
  Loader2,
  Plus,
  RefreshCcw,
  Sparkles,
  Timer,
  Undo2,
  Wand2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api';
import { addDays, dayNumber, greeting, monthDay, minutesToClock, relativeDay, spanLabel, weekdayFull, weekdayShort } from '@/lib/date';
import {
  CATEGORY_META,
  DAY_END,
  DAY_START,
  dayStats,
  freeMinutes,
  freeWindows,
  isActive,
  layoutEvents,
  taskMap,
  timelineEvents,
} from '@/lib/schedule';
import type { AppState, Perform, Task, TaskInput } from '@/lib/types';
import { toast } from 'sonner';
import { MountainWash } from './MountainWash';

interface Props {
  state: AppState;
  perform: Perform;
  busy: boolean;
  onOpenTask: (taskId: string, segmentId?: string) => void;
  onCreateTask: (preset: { date: string; start?: string | null }) => void;
  onGoAssistant: () => void;
  onOpenFocus: () => void;
}

const HOUR_HEIGHT = 56;
const TOTAL_HEIGHT = ((DAY_END - DAY_START) / 60) * HOUR_HEIGHT;
const HOURS = Array.from({ length: (DAY_END - DAY_START) / 60 }, (_, index) => index + 8);

function topFor(minute: number): number {
  return ((Math.max(DAY_START, Math.min(DAY_END, minute)) - DAY_START) / 60) * HOUR_HEIGHT;
}

function hoursOf(minutes: number): string {
  const hours = minutes / 60;
  return Number.isInteger(hours) ? String(hours) : hours.toFixed(1);
}

export function ScheduleView({ state, perform, busy, onOpenTask, onCreateTask, onGoAssistant, onOpenFocus }: Props) {
  const [selectedDate, setSelectedDate] = useState(state.today);
  const [weekStart, setWeekStart] = useState(state.today);
  const timelineRef = useRef<HTMLDivElement | null>(null);
  const alignedRef = useRef<string>('');

  const tasks = useMemo(() => taskMap(state.tasks), [state.tasks]);
  const events = useMemo(
    () => timelineEvents(selectedDate, state.segments, state.fixedBlocks, tasks),
    [selectedDate, state.segments, state.fixedBlocks, tasks],
  );
  const laidOut = useMemo(() => layoutEvents(events), [events]);
  const windows = useMemo(
    () => freeWindows(selectedDate, state.segments, state.fixedBlocks, selectedDate === state.today ? state.nowMinute : null),
    [selectedDate, state.segments, state.fixedBlocks, state.nowMinute, state.today],
  );
  const stats = useMemo(() => dayStats(selectedDate, state.segments, tasks), [selectedDate, state.segments, tasks]);
  const free = freeMinutes(windows);
  const activeFocus = state.focusSessions.find((session) => !session.ended_at);
  const focusTask = activeFocus ? tasks.get(activeFocus.task_id) : undefined;
  const unscheduled = state.tasks.filter(
    (task) => isActive(task) && !state.segments.some((segment) => segment.task_id === task.id && segment.on_date >= state.today),
  );
  const days = Array.from({ length: 7 }, (_, index) => addDays(weekStart, index));
  const isToday = selectedDate === state.today;
  const nowTop = topFor(state.nowMinute);

  useEffect(() => {
    if (selectedDate !== state.today || alignedRef.current === state.today) return;
    alignedRef.current = state.today;
    const node = timelineRef.current;
    if (!node) return;
    const target = Math.max(0, nowTop - 120);
    window.setTimeout(() => node.scrollTo({ top: target, behavior: 'smooth' }), 260);
  }, [selectedDate, state.today, nowTop]);

  function shiftWeek(delta: number) {
    const next = addDays(weekStart, delta * 7);
    setWeekStart(next);
    const last = addDays(next, 6);
    if (selectedDate < next || selectedDate > last) setSelectedDate(next);
  }

  async function runPlan(mode: 'fill' | 'replan') {
    const result = await perform(() => api.plan(mode, 7));
    if (result?.planResult) {
      if (result.planResult.unscheduled.length) toast.warning(result.planResult.message);
      else toast.success(result.planResult.message);
      if (result.planResult.created) setSelectedDate(state.today);
    }
  }

  async function undo() {
    const result = await perform(() => api.undo());
    if (result?.undoLabel) toast.success(`已撤销：${result.undoLabel}`);
  }

  async function loadSamples() {
    const samples: TaskInput[] = [
      { name: '复习高数第三章', durationMinutes: 90, category: 'COURSE', priority: 'HIGH', date: addDays(state.today, 1), start: '15:00' },
      { name: '写课程报告的开头', durationMinutes: 60, category: 'COURSE', priority: 'MEDIUM', date: addDays(state.today, 1) },
      { name: '整理书桌和资料', durationMinutes: 30, category: 'LEISURE', priority: 'LOW', date: addDays(state.today, 2) },
    ];
    const result = await perform(async () => {
      let latest: AppState | null = null;
      for (const sample of samples) {
        latest = await api.createTask({ ...sample, schedule: true });
      }
      return latest;
    });
    if (result) {
      toast.success('示例已经放进日程，随时可以改或删。');
      setSelectedDate(addDays(state.today, 1));
      setWeekStart(state.today);
    }
  }

  function handleEmptyClick(event: React.MouseEvent<HTMLDivElement>) {
    const rect = event.currentTarget.getBoundingClientRect();
    const y = event.clientY - rect.top;
    const raw = DAY_START + (y / HOUR_HEIGHT) * 60;
    const minute = Math.max(DAY_START, Math.min(DAY_END - 30, Math.round(raw / 30) * 30));
    // 点在已经过去的格子上，预填的钟点要落在现在之后，不能把新任务直接放回上午。
    const floor = selectedDate === state.today ? Math.ceil((state.nowMinute + 5) / 5) * 5 : DAY_START;
    onCreateTask({ date: selectedDate, start: minutesToClock(Math.max(minute, floor)) });
  }

  return (
    <div className="page-gutter space-y-4 pt-4 pb-4">
      <section className="hero-wash relative overflow-hidden rounded-[30px] px-5 pt-5 pb-6">
        <MountainWash className="pointer-events-none absolute -right-8 -bottom-6 h-[145px] w-[112%] opacity-80" />
        <div className="relative flex items-start justify-between gap-3">
          <div>
            <p className="text-[11px] tracking-[0.16em] text-[var(--ink-soft)] uppercase">
              {monthDay(selectedDate)} · {weekdayFull(selectedDate)}
            </p>
            <h1 className="mt-1.5 font-serif text-[1.7rem] leading-tight font-semibold text-[var(--hero-ink)]">
              {greeting(state.nowMinute)}，{state.profile.name}
            </h1>
            <p className="mt-2 max-w-[19rem] text-xs leading-5 text-[var(--ink-soft)]">
              {stats.count ? `${isToday ? '今天' : '这一天'}有 ${stats.count} 段安排。` : isToday ? '今天还很空。' : '这一天还很空。'}
            </p>
          </div>
          <button
            type="button"
            onClick={onGoAssistant}
            className="tap flex size-11 shrink-0 items-center justify-center rounded-full border border-white/50 bg-[var(--site-surface)]/70 text-[var(--hero-ink)] shadow-sm backdrop-blur transition hover:scale-105"
            aria-label="和小满说一句"
          >
            <Sparkles className="size-5" />
          </button>
        </div>

        <div className="relative mt-4 flex flex-wrap items-center gap-2 text-[11px]">
          <span className="rounded-full bg-[var(--site-surface)]/70 px-2.5 py-1 text-[var(--hero-ink)] backdrop-blur">
            {stats.count} 件事
          </span>
          <span className="rounded-full bg-[var(--site-surface)]/70 px-2.5 py-1 text-[var(--hero-ink)] backdrop-blur">
            已排 {hoursOf(stats.plannedMinutes)} 小时
          </span>
          <span className="rounded-full bg-[var(--site-surface)]/70 px-2.5 py-1 text-[var(--hero-ink)] backdrop-blur">
            空档 {hoursOf(free)} 小时
          </span>
          {activeFocus && focusTask ? (
            <button
              type="button"
              onClick={onOpenFocus}
              className="tap ml-auto flex items-center gap-1 rounded-full bg-[var(--site-fg)] px-2.5 py-1 text-[var(--site-bg)]"
            >
              <Timer className="size-3" /> 专注中 · {focusTask.name}
            </button>
          ) : null}
        </div>
      </section>

      <section className="card-paper rounded-[26px] px-3 py-3">
        <div className="flex items-center justify-between px-1">
          <button type="button" className="tap rounded-full p-1.5 text-muted-foreground hover:bg-[var(--paper-deep)]" onClick={() => shiftWeek(-1)} aria-label="上一周">
            <ChevronLeft className="size-4" />
          </button>
          <p className="text-[11px] text-muted-foreground">
            {monthDay(weekStart)} – {monthDay(addDays(weekStart, 6))}
          </p>
          <button type="button" className="tap rounded-full p-1.5 text-muted-foreground hover:bg-[var(--paper-deep)]" onClick={() => shiftWeek(1)} aria-label="下一周">
            <ChevronRight className="size-4" />
          </button>
        </div>
        <div className="mt-1 grid grid-cols-7 gap-1">
          {days.map((date) => {
            const active = date === selectedDate;
            const count = state.segments.filter((segment) => segment.on_date === date).length;
            return (
              <button
                key={date}
                type="button"
                onClick={() => setSelectedDate(date)}
                className={`tap flex flex-col items-center gap-1 rounded-2xl py-2 transition ${
                  active ? 'bg-primary text-primary-foreground shadow-sm' : 'text-muted-foreground hover:bg-[var(--paper-deep)]'
                }`}
              >
                <span className="text-[10px]">{date === state.today ? '今天' : `周${weekdayShort(date)}`}</span>
                <span className={`text-sm font-semibold tabular-nums ${active ? '' : 'text-foreground'}`}>{dayNumber(date)}</span>
                <span className={`h-1 w-1 rounded-full ${count ? (active ? 'bg-primary-foreground/80' : 'bg-primary/70') : 'bg-transparent'}`} />
              </button>
            );
          })}
        </div>
      </section>

      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" className="h-8 rounded-full px-3 text-[11px]" disabled={busy} onClick={() => runPlan('fill')}>
          {busy ? <Loader2 className="size-3.5 animate-spin" /> : <Wand2 className="size-3.5" />} 排进空档
        </Button>
        <Button size="sm" variant="secondary" className="h-8 rounded-full px-3 text-[11px]" disabled={busy} onClick={() => runPlan('replan')}>
          <RefreshCcw className="size-3.5" /> 重新排期
        </Button>
        <Button
          size="sm"
          variant="ghost"
          className="h-8 rounded-full px-3 text-[11px] text-muted-foreground"
          disabled={busy || !state.lastOperation}
          onClick={undo}
        >
          <Undo2 className="size-3.5" /> 撤销
        </Button>
        <Button size="sm" variant="ghost" className="ml-auto h-8 rounded-full px-3 text-[11px]" onClick={() => onCreateTask({ date: selectedDate })}>
          <Plus className="size-3.5" /> 记一件事
        </Button>
      </div>

      <section className="card-paper overflow-hidden rounded-[26px]">
        <div className="flex items-center justify-between px-4 pt-4 pb-2">
          <div>
            <h2 className="font-serif text-base font-semibold">一天的时间</h2>
            <p className="mt-0.5 text-[11px] text-muted-foreground">08:00 – 22:00</p>
          </div>
          {isToday ? (
            <button
              type="button"
              className="tap rounded-full border border-[var(--site-border)] px-2.5 py-1 text-[10px] text-muted-foreground"
              onClick={() => timelineRef.current?.scrollTo({ top: Math.max(0, nowTop - 140), behavior: 'smooth' })}
            >
              回到现在
            </button>
          ) : (
            <span className="rounded-full bg-[var(--paper-deep)] px-2.5 py-1 text-[10px] text-muted-foreground">
              {relativeDay(selectedDate, state.today)}
            </span>
          )}
        </div>

        <div ref={timelineRef} className="scrollbar-none relative max-h-[430px] overflow-y-auto px-2 pb-4">
          <div className="relative ml-1" style={{ height: TOTAL_HEIGHT }}>
            <div className="absolute inset-y-0 left-11 right-1 cursor-copy rounded-xl" onClick={handleEmptyClick} />
            {HOURS.map((hour) => (
              <div key={hour} className="pointer-events-none absolute inset-x-0" style={{ top: topFor(hour * 60) }}>
                <div className="ml-11 flex items-center gap-1">
                  <span className="w-9 shrink-0 -translate-y-1/2 text-right text-[9px] tabular-nums text-muted-foreground">
                    {String(hour).padStart(2, '0')}:00
                  </span>
                  <span className="h-px flex-1 bg-[var(--site-border)]/60" />
                </div>
              </div>
            ))}
            <div className="pointer-events-none absolute top-0 bottom-0 left-12 w-px bg-[var(--site-border)]/70" />

            {laidOut.map((event) => {
              const height = Math.max(22, topFor(event.endMinute) - topFor(event.startMinute) - 2);
              const top = topFor(event.startMinute);
              const areaLeft = 48;
              const areaWidth = '100% - 52px';
              const left = `calc(${areaLeft}px + (${areaWidth}) * ${event.column / event.columns} + 2px)`;
              const width = `calc((${areaWidth}) / ${event.columns} - 4px)`;
              if (event.kind === 'fixed') {
                const meta = event.block ? { CLASS: 'fixed-class', FIXED: 'fixed-fixed', REST: 'fixed-rest' }[event.block.kind] || 'fixed-fixed' : 'fixed-fixed';
                return (
                  <div
                    key={event.id}
                    className={`pointer-events-none absolute z-0 overflow-hidden rounded-xl px-2 py-1 ${meta}`}
                    style={{ top, height, left: 'calc(48px + 2px)', width: 'calc(100% - 54px)' }}
                  >
                    <p className="truncate text-[11px] font-medium">{event.title}</p>
                    <p className="text-[10px] opacity-75">{spanLabel(event.startMinute, event.endMinute)}</p>
                  </div>
                );
              }
              const category = CATEGORY_META[event.task?.category || 'UNSPECIFIED'];
              return (
                <button
                  key={event.id}
                  type="button"
                  onClick={(click) => {
                    click.stopPropagation();
                    if (event.segment) onOpenTask(event.segment.task_id, event.segment.id);
                  }}
                  className={`tap absolute z-10 overflow-hidden rounded-xl bg-[var(--site-surface)] px-2 py-1 text-left shadow-sm transition hover:-translate-y-px hover:shadow-md ${category.soft}`}
                  style={{ top, height, left, width, borderLeft: `3px solid var(--cat-${category.token}-dot)` }}
                >
                  <p className="flex items-center gap-1 truncate text-[11px] leading-4 font-medium">
                    <span className={`size-1.5 shrink-0 rounded-full ${category.dot}`} />
                    <span className="min-w-0 truncate">{event.title}</span>
                    {height <= 38 ? (
                      <span className="ml-auto shrink-0 text-[9px] font-normal tabular-nums opacity-75">
                        {spanLabel(event.startMinute, event.endMinute)}
                      </span>
                    ) : null}
                  </p>
                  {height > 38 ? (
                    <p className="mt-0.5 truncate text-[10px] tabular-nums opacity-75">
                      {spanLabel(event.startMinute, event.endMinute)}
                      {event.nested ? ' · 嵌套' : ''}
                    </p>
                  ) : null}
                </button>
              );
            })}

            {isToday && state.nowMinute >= DAY_START && state.nowMinute <= DAY_END ? (
              <div className="pointer-events-none absolute left-12 right-1 z-20 h-px timeline-now" style={{ top: nowTop }}>
                <span className="absolute -top-2 right-0 rounded-full bg-[var(--site-accent)] px-1.5 py-0.5 text-[9px] text-[var(--site-on-accent)]">
                  现在 {minutesToClock(state.nowMinute)}
                </span>
              </div>
            ) : null}
          </div>
        </div>
      </section>

      {!events.length ? (
        <section className="card-paper rounded-[26px] px-5 py-5 text-center">
          <p className="font-serif text-lg font-semibold">{isToday ? '今天还很空' : '这一天还没有安排'}</p>
          <div className="mt-3 flex justify-center gap-2">
            <Button size="sm" className="h-9 rounded-full px-4 text-xs" onClick={onGoAssistant}>
              <Sparkles className="size-3.5" /> 和小满说一句
            </Button>
            <Button size="sm" variant="outline" className="h-9 rounded-full px-4 text-xs" onClick={() => onCreateTask({ date: selectedDate })}>
              <CalendarPlus className="size-3.5" /> 记一件事
            </Button>
          </div>
          {!state.tasks.length && !state.fixedBlocks.length ? (
            <button type="button" onClick={loadSamples} disabled={busy} className="tap mt-3 text-[11px] text-primary underline-offset-4 hover:underline">
              先放三个示例进来看看
            </button>
          ) : null}
        </section>
      ) : null}

      {windows.length ? (
        <section className="card-paper rounded-[24px] px-4 py-3.5">
          <div className="flex items-center justify-between">
            <p className="text-xs font-medium">这天的空档</p>
          </div>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {windows.map((window) => (
              <button
                key={`${window.startMinute}-${window.endMinute}`}
                type="button"
                onClick={() => onCreateTask({ date: selectedDate, start: minutesToClock(window.startMinute) })}
                aria-label={`在 ${spanLabel(window.startMinute, window.endMinute)} 记一件事`}
                className="tap inline-flex items-center gap-1 rounded-full border border-[var(--site-border)] bg-[var(--site-surface)] px-3 py-1.5 text-[11px] tabular-nums transition hover:border-primary/60 hover:bg-primary/10 hover:text-primary"
              >
                <Plus className="size-3" />
                {spanLabel(window.startMinute, window.endMinute)}
              </button>
            ))}
          </div>
        </section>
      ) : null}

      {unscheduled.length ? (
        <section className="flex items-center gap-3 rounded-[22px] border border-dashed border-[var(--site-border)] px-4 py-3">
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium">还有 {unscheduled.length} 件事没排进时间</p>
            <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
              {unscheduled.slice(0, 3).map((task) => task.name).join('、')}
              {unscheduled.length > 3 ? ' 等' : ''}
            </p>
          </div>
          <Button size="sm" variant="secondary" className="h-8 shrink-0 rounded-full text-[11px]" onClick={() => runPlan('fill')}>
            排进空档 <ArrowRight className="size-3" />
          </Button>
        </section>
      ) : null}
    </div>
  );
}
