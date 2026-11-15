import { useEffect, useMemo, useState } from 'react';
import { Loader2, Pause, Play, Sparkles, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Sheet, SheetContent } from '@/components/ui/sheet';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { api } from '@/lib/api';
import { durationLabel } from '@/lib/date';
import { isActive } from '@/lib/schedule';
import type { AppState, Perform, Task } from '@/lib/types';
import { toast } from 'sonner';

interface Props {
  state: AppState;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  perform: Perform;
  onStartFocus: (taskId: string) => Promise<void>;
}

function clockText(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (value: number) => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
}

export function FocusPanel({ state, open, onOpenChange, perform, onStartFocus }: Props) {
  const activeSession = state.focusSessions.find((session) => !session.ended_at);
  const task = activeSession ? state.tasks.find((item) => item.id === activeSession.task_id) : undefined;
  const [now, setNow] = useState(Date.now());
  const [progress, setProgress] = useState(0);
  const [updateProgress, setUpdateProgress] = useState(false);
  const [busy, setBusy] = useState(false);
  const [starting, setStarting] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !activeSession) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [open, activeSession]);

  useEffect(() => {
    if (open) {
      setProgress(task?.progress ?? 0);
      setUpdateProgress(false);
      setNow(Date.now());
    }
  }, [open, task?.id, task?.progress]);

  const elapsedSeconds = useMemo(() => {
    if (!activeSession) return 0;
    return Math.max(0, Math.floor((now - new Date(activeSession.started_at).getTime()) / 1000));
  }, [activeSession, now]);

  const candidates = state.tasks.filter((item) => isActive(item) && item.duration_minutes);

  async function endFocus() {
    if (!activeSession || busy) return;
    setBusy(true);
    const minutes = Math.max(1, Math.round(elapsedSeconds / 60));
    const result = await perform(() =>
      api.focusEnd(activeSession.id, minutes, updateProgress ? progress : undefined),
    );
    setBusy(false);
    if (result) {
      toast.success(`专注 ${minutes} 分钟已记下${updateProgress ? `，进度更新为 ${progress}%` : ''}。`);
      onOpenChange(false);
    }
  }

  async function start(taskId: string) {
    setStarting(taskId);
    await onStartFocus(taskId);
    setStarting(null);
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="bottom"
        showCloseButton={false}
        className="relative mx-auto max-h-[92dvh] w-full max-w-[480px] gap-0 rounded-t-[30px] border-0 bg-[var(--site-surface)] p-0"
      >
        <div className="mx-auto mt-2.5 h-1.5 w-10 rounded-full bg-[var(--site-border)]" />
        <button
          type="button"
          aria-label="收起专注面板"
          onClick={() => onOpenChange(false)}
          className="tap absolute top-3 right-3 rounded-full p-1.5 text-muted-foreground hover:bg-[var(--paper-deep)]"
        >
          <X className="size-4" />
        </button>
        <div className="scrollbar-thin overflow-y-auto px-6 pt-5 pb-7">
          {activeSession && task ? (
            <>
              <p className="text-center text-[11px] tracking-[0.18em] text-muted-foreground uppercase">专注中</p>
              <h2 className="mt-2 text-center font-serif text-xl font-semibold">{task.name}</h2>
              {task.duration_minutes ? (
                <p className="mt-1 text-center text-xs text-muted-foreground">预计 {durationLabel(task.duration_minutes)}</p>
              ) : null}

              <div className="relative mx-auto mt-7 flex size-44 items-center justify-center">
                <div className="absolute inset-0 rounded-full border border-[var(--site-border)]" />
                <div className="absolute inset-3 rounded-full bg-[var(--site-bg)]/60" />
                <div className="relative text-center">
                  <p className="font-serif text-4xl tabular-nums tracking-tight">{clockText(elapsedSeconds)}</p>
                </div>
              </div>

              <div className="mt-6 rounded-2xl border border-[var(--site-border)] p-3.5">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="text-xs font-medium">结束时更新进度</p>
                  </div>
                  <Switch checked={updateProgress} onCheckedChange={setUpdateProgress} aria-label="结束时更新进度" />
                </div>
                {updateProgress ? (
                  <div className="mt-3">
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-muted-foreground">这件事做到哪了</span>
                      <span className="tabular-nums">{progress}%</span>
                    </div>
                    <Slider
                      className="mt-2"
                      value={[progress]}
                      onValueChange={(values) => setProgress(values[0] ?? 0)}
                      step={5}
                      aria-label="专注结束时的进度"
                    />
                  </div>
                ) : null}
              </div>

              <Button className="mt-5 h-11 w-full rounded-2xl" disabled={busy} onClick={endFocus}>
                {busy ? <Loader2 className="size-4 animate-spin" /> : <Pause className="size-4" />}
                结束并记录 {Math.max(1, Math.round(elapsedSeconds / 60))} 分钟
              </Button>
            </>
          ) : (
            <>
              <div className="flex items-center gap-2">
                <Sparkles className="size-4 text-primary" />
                <h2 className="font-serif text-xl font-semibold">挑一件事开始</h2>
              </div>
              <div className="mt-4 space-y-2">
                {candidates.length ? (
                  candidates.slice(0, 8).map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      disabled={starting != null}
                      onClick={() => start(item.id)}
                      className="tap flex w-full items-center justify-between gap-3 rounded-2xl border border-[var(--site-border)] px-3.5 py-3 text-left transition hover:border-primary/40 hover:bg-[var(--site-bg)]/50"
                    >
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-medium">{item.name}</span>
                        <span className="mt-0.5 block text-[11px] text-muted-foreground">
                          {durationLabel(item.duration_minutes)} · {item.progress ? `已完成 ${item.progress}%` : '还没开始'}
                        </span>
                      </span>
                      {starting === item.id ? (
                        <Loader2 className="size-4 animate-spin text-primary" />
                      ) : (
                        <Play className="size-4 text-primary" />
                      )}
                    </button>
                  ))
                ) : (
                  <p className="rounded-2xl bg-[var(--site-bg)]/60 px-4 py-6 text-center text-xs text-muted-foreground">
                    还没有带时长的待办
                  </p>
                )}
              </div>
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}
