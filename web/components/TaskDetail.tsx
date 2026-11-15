import { useEffect, useState } from 'react';
import {
  CalendarClock,
  CheckCircle2,
  CircleSlash,
  Clock3,
  Loader2,
  PencilLine,
  Play,
  RotateCcw,
  Trash2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Progress } from '@/components/ui/progress';
import { Sheet, SheetContent } from '@/components/ui/sheet';
import { Slider } from '@/components/ui/slider';
import { api } from '@/lib/api';
import { durationLabel, relativeDay, spanLabel } from '@/lib/date';
import { CATEGORY_META, PRIORITY_META, STATUS_META, isActive } from '@/lib/schedule';
import type { AppState, Perform, Task } from '@/lib/types';
import { toast } from 'sonner';

interface Props {
  state: AppState;
  task?: Task;
  segmentId?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  perform: Perform;
  onEdit: (task: Task) => void;
  onFocus: (task: Task) => void;
}

export function TaskDetail({ state, task, segmentId, open, onOpenChange, perform, onEdit, onFocus }: Props) {
  const [progress, setProgress] = useState(0);
  const [date, setDate] = useState(state.today);
  const [start, setStart] = useState('');
  const [scheduling, setScheduling] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open || !task) return;
    setProgress(task.progress ?? (task.status === 'COMPLETED' ? 100 : 0));
    setDate(task.planned_date || state.today);
    setStart('');
    setConfirmCancel(false);
    setScheduling(false);
  }, [open, task, state.today]);

  if (!task) return null;
  const category = CATEGORY_META[task.category] || CATEGORY_META.UNSPECIFIED;
  const priority = PRIORITY_META[task.priority] || PRIORITY_META.MEDIUM;
  const segments = state.segments
    .filter((segment) => segment.task_id === task.id)
    .sort((a, b) => a.on_date.localeCompare(b.on_date) || a.start_minute - b.start_minute);
  const active = isActive(task);

  async function update(patch: Parameters<typeof api.updateTask>[1], message: string) {
    setBusy(true);
    const result = await perform(() => api.updateTask(task!.id, patch));
    setBusy(false);
    if (result) toast.success(message);
  }

  async function scheduleTask() {
    setScheduling(true);
    const result = await perform(() => api.scheduleTask(task!.id, date, start || null));
    setScheduling(false);
    if (result?.placed) {
      const placed = result.placed;
      const when = `${relativeDay(placed.date, state.today)} ${spanLabel(placed.startMinute, placed.endMinute)}`;
      toast.success(
        placed.adjusted === 'past'
          ? `那个时间已经过了，已排到 ${when}`
          : placed.adjusted === 'occupied'
            ? `那个时间被占了，已排到 ${when}`
            : `已排到 ${when}`,
      );
      onOpenChange(false);
    }
  }

  async function removeSegment(id: string) {
    setBusy(true);
    const result = await perform(() => api.deleteSegment(id));
    setBusy(false);
    if (result) toast.success('时段已移出日程。');
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="bottom"
        showCloseButton={false}
        className="mx-auto max-h-[92dvh] w-full max-w-[540px] gap-0 rounded-t-[30px] border-0 bg-[var(--site-surface)] p-0"
      >
        <div className="mx-auto mt-2.5 h-1.5 w-10 rounded-full bg-[var(--site-border)]" />
        <div className="scrollbar-thin overflow-y-auto px-5 pt-4 pb-6">
          <div className="flex items-start gap-3">
            <span className={`mt-1 size-2.5 shrink-0 rounded-full ${category.dot}`} />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${category.soft}`}>{category.label}</span>
                <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${priority.tone}`}>{priority.label}</span>
                <span className="rounded-full bg-[var(--paper-deep)] px-2 py-0.5 text-[10px] text-muted-foreground">
                  {STATUS_META[task.status]}
                </span>
              </div>
              <h2 className="mt-2 font-serif text-[1.35rem] leading-snug font-semibold break-words">{task.name}</h2>
              {task.note ? <p className="mt-1.5 text-sm leading-6 text-muted-foreground whitespace-pre-wrap">{task.note}</p> : null}
            </div>
          </div>

          <div className="mt-4 grid grid-cols-3 gap-2">
            <div className="rounded-2xl bg-[var(--site-bg)]/60 px-3 py-2.5">
              <p className="text-[10px] text-muted-foreground">预计用时</p>
              <p className="mt-0.5 text-xs font-medium">{durationLabel(task.duration_minutes)}</p>
            </div>
            <div className="rounded-2xl bg-[var(--site-bg)]/60 px-3 py-2.5">
              <p className="text-[10px] text-muted-foreground">已专注</p>
              <p className="mt-0.5 text-xs font-medium">{task.actual_minutes ? `${task.actual_minutes} 分钟` : '0 分钟'}</p>
            </div>
            <div className="rounded-2xl bg-[var(--site-bg)]/60 px-3 py-2.5">
              <p className="text-[10px] text-muted-foreground">截止</p>
              <p className="mt-0.5 text-xs font-medium">{task.due_date ? relativeDay(task.due_date, state.today) : '未设'}</p>
            </div>
          </div>

          {active ? (
            <div className="mt-4 rounded-2xl border border-[var(--site-border)] p-3.5">
              <div className="flex items-center justify-between">
                <p className="text-xs font-medium">做到哪了</p>
                <span className="text-xs tabular-nums text-muted-foreground">{progress}%</span>
              </div>
              <Slider
                className="mt-3"
                value={[progress]}
                onValueChange={(values) => setProgress(values[0] ?? 0)}
                step={5}
                aria-label="任务进度"
              />
              <div className="mt-3 flex items-center gap-2">
                <Progress value={progress} className="h-1.5 flex-1" />
                <Button
                  size="sm"
                  variant="secondary"
                  className="h-7 rounded-full px-3 text-[11px]"
                  disabled={busy || progress === (task.progress ?? 0)}
                  onClick={() =>
                    update(
                      progress >= 100 ? { status: 'COMPLETED', progress: 100 } : { progress },
                      progress >= 100 ? '这件事完成了。' : '进度记下了。',
                    )
                  }
                >
                  记一下
                </Button>
              </div>
            </div>
          ) : null}

          <div className="mt-4">
            <div className="flex items-center justify-between">
              <p className="flex items-center gap-1.5 text-xs font-medium">
                <Clock3 className="size-3.5 text-primary" /> 已排时段
              </p>
              <span className="text-[11px] text-muted-foreground">{segments.length ? `${segments.length} 段` : '还没排'}</span>
            </div>
            {segments.length ? (
              <div className="mt-2 space-y-1.5">
                {segments.map((segment) => {
                  const isActiveSegment = segment.id === segmentId;
                  return (
                    <div
                      key={segment.id}
                      className={`flex items-center justify-between gap-2 rounded-xl px-3 py-2 text-xs ${
                        isActiveSegment ? 'bg-[var(--paper-deep)]' : 'bg-[var(--site-bg)]/50'
                      }`}
                    >
                      <span className="tabular-nums">
                        {relativeDay(segment.on_date, state.today)} · {spanLabel(segment.start_minute, segment.end_minute)}
                        {segment.from_user ? <span className="ml-1.5 text-[10px] text-muted-foreground">手动</span> : null}
                      </span>
                      <button
                        type="button"
                        aria-label="移出这个时段"
                        className="tap rounded-full p-1.5 text-muted-foreground hover:bg-[var(--site-surface)] hover:text-destructive"
                        onClick={() => removeSegment(segment.id)}
                      >
                        <Trash2 className="size-3.5" />
                      </button>
                    </div>
                  );
                })}
              </div>
            ) : (
              <p className="mt-1.5 text-[11px] text-muted-foreground">未排时间</p>
            )}
          </div>

          {active ? (
            <div className="mt-4 rounded-2xl border border-[var(--site-border)] p-3.5">
              <p className="flex items-center gap-1.5 text-xs font-medium">
                <CalendarClock className="size-3.5 text-primary" /> 排进日程
              </p>
              <div className="mt-2 grid grid-cols-[1.2fr_1fr] gap-2">
                <Input
                  type="date"
                  value={date}
                  onChange={(event) => setDate(event.target.value)}
                  className="h-9 rounded-xl bg-[var(--site-bg)]/60 text-xs"
                  aria-label="排期日期"
                />
                <div className="flex gap-2">
                  <Input
                    type="time"
                    value={start}
                    onChange={(event) => setStart(event.target.value)}
                    className="h-9 min-w-0 rounded-xl bg-[var(--site-bg)]/60 text-xs"
                    aria-label="开始时间"
                  />
                  <Button size="sm" className="h-9 shrink-0 rounded-xl px-3 text-xs" disabled={scheduling} onClick={scheduleTask}>
                    {scheduling ? <Loader2 className="size-3.5 animate-spin" /> : '排进日程'}
                  </Button>
                </div>
              </div>
            </div>
          ) : null}

          <div className="mt-5 grid grid-cols-2 gap-2">
            {active ? (
              <Button
                className="h-10 rounded-2xl"
                disabled={!task.duration_minutes}
                onClick={() => {
                  onOpenChange(false);
                  onFocus(task);
                }}
              >
                <Play className="size-4" /> 专注这件事
              </Button>
            ) : null}
            <Button variant="secondary" className="h-10 rounded-2xl" onClick={() => onEdit(task)}>
              <PencilLine className="size-4" /> 编辑
            </Button>
            {active ? (
              <Button
                variant="outline"
                className="h-10 rounded-2xl"
                disabled={busy}
                onClick={() => update({ status: 'COMPLETED', progress: 100 }, '这件事完成了。')}
              >
                <CheckCircle2 className="size-4" /> 标记完成
              </Button>
            ) : (
              <Button
                variant="outline"
                className="h-10 rounded-2xl"
                disabled={busy}
                onClick={() => update({ status: 'NOT_STARTED' }, '已经放回待办。')}
              >
                <RotateCcw className="size-4" /> 放回待办
              </Button>
            )}
            {active ? (
              confirmCancel ? (
                <Button
                  variant="destructive"
                  className="h-10 rounded-2xl"
                  disabled={busy}
                  onClick={() => update({ status: 'CANCELLED' }, '这件事已经取消。')}
                >
                  确认取消
                </Button>
              ) : (
                <Button variant="ghost" className="h-10 rounded-2xl text-muted-foreground" onClick={() => setConfirmCancel(true)}>
                  <CircleSlash className="size-4" /> 不做了
                </Button>
              )
            ) : null}
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
}
