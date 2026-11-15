import { useMemo, useState } from 'react';
import { CheckCircle2, Circle, Loader2, Plus, Search, Sparkles, Timer } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { api } from '@/lib/api';
import { addDays, durationLabel, relativeDay, spanLabel } from '@/lib/date';
import { CATEGORY_META, PRIORITY_META, STATUS_META, isActive } from '@/lib/schedule';
import type { AppState, Perform, Segment, Task } from '@/lib/types';
import { toast } from 'sonner';
import { MountainWash } from './MountainWash';

interface Props {
  state: AppState;
  perform: Perform;
  busy: boolean;
  onOpenTask: (taskId: string) => void;
  onCreateTask: () => void;
  onFocus: (task: Task) => void;
  onGoAssistant: () => void;
}

type Filter = 'active' | 'done' | 'all';

const FILTERS: Array<{ value: Filter; label: string }> = [
  { value: 'active', label: '待办' },
  { value: 'done', label: '已完成' },
  { value: 'all', label: '全部' },
];

function sortTasks(a: Task, b: Task): number {
  const left = a.due_date || a.planned_date || '9999-12-31';
  const right = b.due_date || b.planned_date || '9999-12-31';
  if (left !== right) return left.localeCompare(right);
  return String(a.created_at).localeCompare(String(b.created_at));
}

export function TasksView({ state, perform, busy, onOpenTask, onCreateTask, onFocus, onGoAssistant }: Props) {
  const [filter, setFilter] = useState<Filter>('active');
  const [query, setQuery] = useState('');
  const [workingId, setWorkingId] = useState<string | null>(null);

  const nextSegments = useMemo(() => {
    const map = new Map<string, Segment>();
    for (const segment of state.segments) {
      if (segment.on_date < state.today) continue;
      const current = map.get(segment.task_id);
      if (
        !current ||
        segment.on_date < current.on_date ||
        (segment.on_date === current.on_date && segment.start_minute < current.start_minute)
      ) {
        map.set(segment.task_id, segment);
      }
    }
    return map;
  }, [state.segments, state.today]);

  const activeCount = state.tasks.filter(isActive).length;
  const weekDone = state.tasks.filter(
    (task) => task.status === 'COMPLETED' && String(task.completed_at || '').slice(0, 10) >= addDays(state.today, -6),
  ).length;
  const todaySegments = state.segments.filter((segment) => segment.on_date === state.today).length;

  const groups = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    const matches = (task: Task) =>
      !keyword || task.name.toLowerCase().includes(keyword) || String(task.note || '').toLowerCase().includes(keyword);
    const filtered = state.tasks
      .filter((task) => {
        if (filter === 'active') return isActive(task);
        if (filter === 'done') return task.status === 'COMPLETED' || task.status === 'CANCELLED';
        return true;
      })
      .filter(matches)
      .sort((a, b) => (filter === 'done' ? String(b.completed_at || '').localeCompare(String(a.completed_at || '')) : sortTasks(a, b)));
    if (filter !== 'active') return [{ title: filter === 'done' ? '已经结束' : '全部任务', tasks: filtered }];

    const weekEnd = addDays(state.today, 7);
    const buckets: Array<{ title: string; tasks: Task[] }> = [
      { title: '今天和更早', tasks: [] },
      { title: '接下来七天', tasks: [] },
      { title: '排到更远', tasks: [] },
      { title: '还没定日子', tasks: [] },
    ];
    for (const task of filtered) {
      const anchor = task.due_date || task.planned_date;
      if (!anchor) buckets[3].tasks.push(task);
      else if (anchor <= state.today) buckets[0].tasks.push(task);
      else if (anchor <= weekEnd) buckets[1].tasks.push(task);
      else buckets[2].tasks.push(task);
    }
    return buckets.filter((bucket) => bucket.tasks.length);
  }, [state.tasks, state.today, filter, query]);

  async function toggle(task: Task) {
    if (workingId || busy) return;
    setWorkingId(task.id);
    const done = task.status === 'COMPLETED';
    const result = await perform(() =>
      api.updateTask(task.id, done ? { status: 'NOT_STARTED' } : { status: 'COMPLETED', progress: 100 }),
    );
    setWorkingId(null);
    if (result) toast.success(done ? '已经放回待办。' : `「${task.name}」完成了。`);
  }

  const showSamples = !state.tasks.length && !query;

  return (
    <div className="page-gutter space-y-4 pt-4 pb-4">
      <section className="hero-wash relative overflow-hidden rounded-[30px] px-5 pt-5 pb-6">
        <MountainWash className="pointer-events-none absolute -right-10 -bottom-8 h-[130px] w-[108%] opacity-70" />
        <div className="relative flex items-start justify-between gap-3">
          <div>
            <p className="text-[11px] tracking-[0.16em] text-[var(--ink-soft)] uppercase">任务清单</p>
            <h1 className="mt-1.5 font-serif text-[1.65rem] leading-tight font-semibold text-[var(--hero-ink)]">
              {activeCount ? `还有 ${activeCount} 件事在等你` : '清单已经空了'}
            </h1>
            <p className="mt-2 text-xs leading-5 text-[var(--ink-soft)]">
              今天排了 {todaySegments} 段 · 这周完成 {weekDone} 件
            </p>
          </div>
          <button
            type="button"
            onClick={onCreateTask}
            className="tap flex size-11 shrink-0 items-center justify-center rounded-full border border-white/50 bg-[var(--site-surface)]/70 text-[var(--hero-ink)] shadow-sm backdrop-blur transition hover:scale-105"
            aria-label="记一件事"
          >
            <Plus className="size-5" />
          </button>
        </div>
      </section>

      <div className="relative">
        <Search className="pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜一下要做的事"
          className="h-11 rounded-2xl border-[var(--site-border)] bg-[var(--site-surface)] pr-3 pl-10 text-sm"
          aria-label="搜索任务"
        />
      </div>

      <div className="flex gap-1.5">
        {FILTERS.map((entry) => (
          <button
            key={entry.value}
            type="button"
            onClick={() => setFilter(entry.value)}
            className={`tap rounded-full px-3.5 py-1.5 text-xs transition ${
              filter === entry.value
                ? 'bg-primary text-primary-foreground shadow-sm'
                : 'border border-[var(--site-border)] bg-[var(--site-surface)] text-muted-foreground hover:border-primary/40'
            }`}
          >
            {entry.label}
          </button>
        ))}
        <button
          type="button"
          onClick={onGoAssistant}
          className="tap ml-auto flex items-center gap-1 rounded-full px-2.5 py-1.5 text-[11px] text-primary"
        >
          <Sparkles className="size-3.5" /> 和小满说
        </button>
      </div>

      {groups.length ? (
        <div className="space-y-5">
          {groups.map((group) => (
            <section key={group.title} className="space-y-2">
              <div className="flex items-center gap-2 px-1">
                <h2 className="text-[11px] font-medium tracking-[0.08em] text-muted-foreground">{group.title}</h2>
                <span className="text-[10px] text-muted-foreground/70">{group.tasks.length}</span>
                <span className="h-px flex-1 bg-[var(--site-border)]/70" />
              </div>
              {group.tasks.map((task) => {
                const category = CATEGORY_META[task.category] || CATEGORY_META.UNSPECIFIED;
                const priority = PRIORITY_META[task.priority] || PRIORITY_META.MEDIUM;
                const segment = nextSegments.get(task.id);
                const done = task.status === 'COMPLETED';
                const inactive = task.status === 'CANCELLED';
                return (
                  <article key={task.id} className={`card-paper rounded-[22px] px-3 py-3 ${done || inactive ? 'opacity-70' : ''}`}>
                    <div className="flex items-start gap-2">
                      <button
                        type="button"
                        onClick={() => toggle(task)}
                        disabled={Boolean(workingId) || inactive}
                        className="tap mt-0.5 shrink-0 rounded-full p-0.5 text-muted-foreground transition hover:text-primary"
                        aria-label={done ? '放回待办' : '标记完成'}
                        title={done ? '放回待办' : '标记完成'}
                      >
                        {workingId === task.id ? (
                          <Loader2 className="size-5 animate-spin text-primary" />
                        ) : done ? (
                          <CheckCircle2 className="size-5 text-primary" />
                        ) : (
                          <Circle className="size-5" />
                        )}
                      </button>

                      <button type="button" onClick={() => onOpenTask(task.id)} className="min-w-0 flex-1 text-left">
                        <p className={`truncate text-sm font-medium ${done ? 'line-through decoration-primary/40' : ''}`}>
                          {task.name}
                        </p>
                        {task.note ? <p className="mt-0.5 truncate text-[11px] text-muted-foreground">{task.note}</p> : null}
                        <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-[10px]">
                          <span className={`inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 ${category.soft}`}>
                            <span className={`size-1.5 rounded-full ${category.dot}`} />
                            {category.label}
                          </span>
                          {task.priority === 'REQUIRED' || task.priority === 'HIGH' ? (
                            <span className={`rounded-full px-1.5 py-0.5 ${priority.tone}`}>{priority.label}</span>
                          ) : null}
                          <span className="text-muted-foreground">{durationLabel(task.duration_minutes)}</span>
                          {segment && isActive(task) ? (
                            <span className="text-muted-foreground">
                              · {relativeDay(segment.on_date, state.today)} {spanLabel(segment.start_minute, segment.end_minute)}
                            </span>
                          ) : task.due_date ? (
                            <span className={task.due_date < state.today && isActive(task) ? 'font-medium text-destructive' : 'text-muted-foreground'}>
                              · 截止 {relativeDay(task.due_date, state.today)}
                            </span>
                          ) : !done ? (
                            <span className="text-muted-foreground">· {STATUS_META[task.status]}</span>
                          ) : null}
                        </div>
                      </button>

                      {isActive(task) && task.duration_minutes ? (
                        <button
                          type="button"
                          onClick={() => onFocus(task)}
                          className="tap mt-0.5 shrink-0 rounded-full p-1.5 text-muted-foreground transition hover:bg-[var(--paper-deep)] hover:text-primary"
                          aria-label={`专注${task.name}`}
                          title="开始专注"
                        >
                          <Timer className="size-4" />
                        </button>
                      ) : null}
                    </div>
                  </article>
                );
              })}
            </section>
          ))}
        </div>
      ) : (
        <section className="card-paper rounded-[26px] px-5 py-8 text-center">
          <p className="font-serif text-lg font-semibold">{query ? '没有搜到' : filter === 'done' ? '还没有完成的事' : '清单是空的'}</p>
          {showSamples || filter !== 'done' ? (
            <Button size="sm" className="mt-4 h-9 rounded-full px-4 text-xs" onClick={onCreateTask}>
              <Plus className="size-3.5" /> 记第一件事
            </Button>
          ) : null}
        </section>
      )}
    </div>
  );
}
