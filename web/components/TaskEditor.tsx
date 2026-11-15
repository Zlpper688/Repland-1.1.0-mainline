import { useEffect, useState } from 'react';
import { CalendarPlus, Loader2, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { api } from '@/lib/api';
import { addDays } from '@/lib/date';
import { CATEGORY_META, PRIORITY_META } from '@/lib/schedule';
import type { Category, Perform, Priority, Task } from '@/lib/types';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  task?: Task;
  preset?: { date: string; start?: string | null };
  today: string;
  perform: Perform;
  onSaved?: () => void;
}

const DURATIONS = [30, 45, 60, 90, 120];
const CATEGORIES: Category[] = ['COURSE', 'EXTRACURRICULAR', 'OFFICE', 'LEISURE', 'UNSPECIFIED'];
const PRIORITIES: Priority[] = ['REQUIRED', 'HIGH', 'MEDIUM', 'LOW'];

export function TaskEditor({ open, onOpenChange, task, preset, today, perform, onSaved }: Props) {
  const [name, setName] = useState('');
  const [note, setNote] = useState('');
  const [duration, setDuration] = useState('');
  const [priority, setPriority] = useState<Priority>('MEDIUM');
  const [category, setCategory] = useState<Category>('UNSPECIFIED');
  const [dueDate, setDueDate] = useState('');
  const [plannedDate, setPlannedDate] = useState('');
  const [schedule, setSchedule] = useState(false);
  const [scheduleDate, setScheduleDate] = useState(today);
  const [start, setStart] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setName(task?.name || '');
    setNote(task?.note || '');
    setDuration(task?.duration_minutes ? String(task.duration_minutes) : '60');
    setPriority(task?.priority || 'MEDIUM');
    setCategory(task?.category || 'UNSPECIFIED');
    setDueDate(task?.due_date || '');
    setPlannedDate(task?.planned_date || '');
    setSchedule(Boolean(preset));
    setScheduleDate(preset?.date || task?.planned_date || today);
    setStart(preset?.start || '');
    setError('');
  }, [open, task, preset, today]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setError('');
    if (!name.trim()) {
      setError('先给这件事起个名字。');
      return;
    }
    const minutes = duration.trim() ? Number(duration) : null;
    if (minutes != null && (!Number.isFinite(minutes) || minutes < 5 || minutes > 720)) {
      setError('时长填 5 到 720 分钟之间。');
      return;
    }
    if (!task && schedule && !scheduleDate) {
      setError('选了排进日程，就要给一个日期。');
      return;
    }
    setBusy(true);
    const base = {
      name: name.trim(),
      note: note.trim(),
      durationMinutes: minutes,
      priority,
      category,
      dueDate: dueDate || null,
      plannedDate: plannedDate || null,
    };
    const result = task
      ? await perform(() => api.updateTask(task.id, base))
      : await perform(() =>
          api.createTask({
            ...base,
            date: schedule ? scheduleDate : undefined,
            start: schedule && start ? start : null,
            schedule,
          }),
        );
    setBusy(false);
    if (result) {
      onOpenChange(false);
      onSaved?.();
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90dvh] w-[calc(100vw-24px)] max-w-[460px] overflow-hidden rounded-[28px] border-[var(--site-border)] bg-[var(--site-surface)] p-0">
        <form onSubmit={submit} className="flex max-h-[90dvh] flex-col">
          <DialogHeader className="px-5 pt-5 pb-1">
            <DialogTitle className="flex items-center gap-2 font-serif text-xl">
              {task ? '改一件事' : '记一件事'}
              {task ? null : <Sparkles className="size-4 text-primary" />}
            </DialogTitle>
            <DialogDescription className="sr-only">
              {task ? '编辑任务' : '新任务'}
            </DialogDescription>
          </DialogHeader>

          <div className="scrollbar-thin flex-1 space-y-4 overflow-y-auto px-5 py-4">
            <div className="space-y-1.5">
              <Label htmlFor="task-name" className="text-xs text-muted-foreground">做什么</Label>
              <Input
                id="task-name"
                autoFocus
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="例如：复习高数第三章"
                maxLength={80}
                className="h-11 rounded-2xl bg-[var(--site-bg)]/60 text-[15px]"
              />
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">大概要多久</Label>
              <div className="flex flex-wrap items-center gap-2">
                {DURATIONS.map((minutes) => (
                  <button
                    key={minutes}
                    type="button"
                    onClick={() => setDuration(String(minutes))}
                    className={`tap rounded-full border px-3 py-1.5 text-xs transition ${
                      duration === String(minutes)
                        ? 'border-primary bg-primary text-primary-foreground'
                        : 'border-[var(--site-border)] bg-[var(--site-surface)] text-muted-foreground hover:border-primary/40'
                    }`}
                  >
                    {minutes < 60 ? `${minutes} 分钟` : `${minutes / 60} 小时`}
                  </button>
                ))}
                <div className="flex items-center gap-1.5">
                  <Input
                    value={duration}
                    onChange={(event) => setDuration(event.target.value.replace(/[^\d]/g, ''))}
                    inputMode="numeric"
                    className="h-8 w-16 rounded-full text-center text-xs"
                    aria-label="自定义分钟数"
                  />
                  <span className="text-xs text-muted-foreground">分钟</span>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label className="text-xs text-muted-foreground">优先级</Label>
                <Select value={priority} onValueChange={(value) => setPriority(value as Priority)}>
                  <SelectTrigger className="h-10 w-full rounded-2xl bg-[var(--site-bg)]/60">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {PRIORITIES.map((value) => (
                      <SelectItem key={value} value={value}>
                        {PRIORITY_META[value].label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs text-muted-foreground">分类</Label>
                <Select value={category} onValueChange={(value) => setCategory(value as Category)}>
                  <SelectTrigger className="h-10 w-full rounded-2xl bg-[var(--site-bg)]/60">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {CATEGORIES.map((value) => (
                      <SelectItem key={value} value={value}>
                        {CATEGORY_META[value].label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="task-planned" className="text-xs text-muted-foreground">想在哪天做</Label>
                <Input
                  id="task-planned"
                  type="date"
                  value={plannedDate}
                  onChange={(event) => setPlannedDate(event.target.value)}
                  className="h-10 rounded-2xl bg-[var(--site-bg)]/60"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="task-due" className="text-xs text-muted-foreground">截止日</Label>
                <Input
                  id="task-due"
                  type="date"
                  value={dueDate}
                  onChange={(event) => setDueDate(event.target.value)}
                  className="h-10 rounded-2xl bg-[var(--site-bg)]/60"
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="task-note" className="text-xs text-muted-foreground">备注（可选）</Label>
              <Textarea
                id="task-note"
                value={note}
                onChange={(event) => setNote(event.target.value)}
                placeholder="要带的东西、章节范围、和谁一起…"
                maxLength={500}
                rows={2}
                className="rounded-2xl bg-[var(--site-bg)]/60"
              />
            </div>

            {task ? null : (
              <div className="rounded-2xl border border-[var(--site-border)] bg-[var(--site-bg)]/40 p-3.5">
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-2 text-sm font-medium">
                    <CalendarPlus className="size-4 text-primary" />
                    顺便排进日程
                  </div>
                  <Switch checked={schedule} onCheckedChange={setSchedule} aria-label="顺便排进日程" />
                </div>
                {schedule ? (
                  <div className="mt-3 space-y-2">
                    <div className="flex flex-wrap gap-2">
                      {[today, addDays(today, 1), addDays(today, 2)].map((date) => (
                        <button
                          key={date}
                          type="button"
                          onClick={() => setScheduleDate(date)}
                          className={`tap rounded-full border px-3 py-1 text-[11px] ${
                            scheduleDate === date
                              ? 'border-primary bg-primary text-primary-foreground'
                              : 'border-[var(--site-border)] text-muted-foreground'
                          }`}
                        >
                          {date === today ? '今天' : date === addDays(today, 1) ? '明天' : '后天'}
                        </button>
                      ))}
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <Input
                        type="date"
                        value={scheduleDate}
                        onChange={(event) => setScheduleDate(event.target.value)}
                        className="h-9 rounded-xl bg-[var(--site-surface)] text-xs"
                        aria-label="排期日期"
                      />
                      <Input
                        type="time"
                        value={start}
                        onChange={(event) => setStart(event.target.value)}
                        className="h-9 rounded-xl bg-[var(--site-surface)] text-xs"
                        aria-label="开始时间，留空自动找空档"
                      />
                    </div>
                  </div>
                ) : null}
              </div>
            )}

            {error ? (
              <p role="alert" className="rounded-2xl bg-[var(--priority-required-bg)] px-3 py-2 text-xs text-[var(--priority-required-fg)]">
                {error}
              </p>
            ) : null}
          </div>

          <div className="flex gap-2 border-t border-[var(--site-border)] px-5 py-3.5">
            <Button type="button" variant="ghost" className="h-10 flex-1 rounded-2xl" onClick={() => onOpenChange(false)}>
              再想想
            </Button>
            <Button type="submit" disabled={busy} className="h-10 flex-[1.4] rounded-2xl">
              {busy ? <Loader2 className="size-4 animate-spin" /> : null}
              {task ? '保存修改' : '记下来'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
