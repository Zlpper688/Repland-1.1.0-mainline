import { useCallback, useEffect, useState } from 'react';
import { CalendarDays, ListTodo, Loader2, Sparkles, UserRound, UsersRound } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Toaster } from '@/components/ui/sonner';
import { AssistantView } from '@/components/AssistantView';
import { FocusPanel } from '@/components/FocusPanel';
import { ProfileView } from '@/components/ProfileView';
import { ScheduleView } from '@/components/ScheduleView';
import { TaskDetail } from '@/components/TaskDetail';
import { TaskEditor } from '@/components/TaskEditor';
import { TasksView } from '@/components/TasksView';
import { TeamView } from '@/components/TeamView';
import { api, ApiError, clearToken, getToken, setToken } from '@/lib/api';
import type { AppState, Perform, Tab, Task } from '@/lib/types';
import { toast } from 'sonner';

const NAV = [
  { id: 'schedule', label: '日程', icon: CalendarDays },
  { id: 'tasks', label: '清单', icon: ListTodo },
  { id: 'team', label: '团队', icon: UsersRound },
  { id: 'assistant', label: '小满', icon: Sparkles },
  { id: 'profile', label: '我的', icon: UserRound },
] as const;

const ERROR_MESSAGES: Record<string, string> = {
  invalid_input: '填写的内容还需要调整一下。',
  no_room: '那个时段排不下了，换个时间或日期试试。',
  focus_running: '已经有一个专注在计时了。',
  proposal_unavailable: '这个提议已经处理过了。',
  undo_not_safe: '后面又有新改动，这一步撤销不回去了。',
  not_found: '没有找到这条记录。',
  rate_limited: '今天的额度用完了，明天再来试试。',
};

function Loading() {
  return (
    <div className="hero-wash flex min-h-dvh flex-col items-center justify-center gap-3">
      <Loader2 className="size-5 animate-spin text-primary" />
      <p className="text-xs text-muted-foreground">正在翻开你的日程…</p>
    </div>
  );
}

export default function App() {
  const [state, setState] = useState<AppState | null>(null);
  const [checking, setChecking] = useState(true);
  const [busy, setBusy] = useState(false);
  const [tab, setTab] = useState<Tab>('schedule');
  const [editor, setEditor] = useState<{ task?: Task; preset?: { date: string; start?: string | null } } | null>(null);
  const [detail, setDetail] = useState<{ taskId: string; segmentId?: string } | null>(null);
  const [focusOpen, setFocusOpen] = useState(false);

  useEffect(() => {
    let alive = true;
    async function load() {
      try {
        if (!getToken()) {
          const session = await api.guest();
          setToken(session.token);
        }
        let next: AppState;
        try {
          next = await api.bootstrap();
        } catch (error) {
          if (!(error instanceof ApiError) || error.status !== 401) throw error;
          clearToken();
          const session = await api.guest();
          setToken(session.token);
          next = await api.bootstrap();
        }
        if (alive) setState(next);
      } catch {
        // The retry screen below handles an unreachable backend.
      } finally {
        if (alive) setChecking(false);
      }
    }
    void load();
    return () => {
      alive = false;
    };
  }, []);

  const perform = useCallback<Perform>(async (fn, options) => {
    setBusy(true);
    try {
      const result = await fn();
      if (options?.apply !== false && result && typeof result === 'object' && 'tasks' in result) {
        setState(result as unknown as AppState);
      }
      return result;
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 401) {
          clearToken();
          try {
            const session = await api.guest();
            setToken(session.token);
            setState(await api.bootstrap());
            toast.info('连接已刷新，再操作一次就好。');
          } catch {
            setState(null);
          }
        } else {
          toast.error(ERROR_MESSAGES[error.code] || error.message || '没有成功，稍后再试。');
        }
      } else {
        toast.error('没有成功，稍后再试。');
      }
      return null;
    } finally {
      setBusy(false);
    }
  }, []);

  const startFocus = useCallback(
    async (taskId: string) => {
      if (!state) return;
      const active = state.focusSessions.find((session) => !session.ended_at);
      if (active) {
        setFocusOpen(true);
        return;
      }
      const result = await perform(() => api.focusStart(taskId));
      if (result) setFocusOpen(true);
    },
    [state, perform],
  );

  if (checking) return <Loading />;
  if (!state) {
    return (
      <main className="flex min-h-dvh flex-col items-center justify-center gap-4 px-6 text-center">
        <p className="text-sm leading-6 text-muted-foreground">没有连上日程服务，检查一下网络再试一次。</p>
        <Button onClick={() => window.location.reload()}>重新连接</Button>
        <Toaster position="top-center" />
      </main>
    );
  }

  const detailTask = detail ? state.tasks.find((task) => task.id === detail.taskId) : undefined;

  return (
    <div className="min-h-dvh">
      <main className="relative mx-auto w-full max-w-[600px] pb-[76px]">
        {tab === 'schedule' ? (
          <ScheduleView
            state={state}
            perform={perform}
            busy={busy}
            onOpenTask={(taskId, segmentId) => setDetail({ taskId, segmentId })}
            onCreateTask={(preset) => setEditor({ preset })}
            onGoAssistant={() => setTab('assistant')}
            onOpenFocus={() => setFocusOpen(true)}
          />
        ) : null}

        {tab === 'tasks' ? (
          <TasksView
            state={state}
            perform={perform}
            busy={busy}
            onOpenTask={(taskId) => setDetail({ taskId })}
            onCreateTask={() => setEditor({})}
            onFocus={(task) => void startFocus(task.id)}
            onGoAssistant={() => setTab('assistant')}
          />
        ) : null}

        {tab === 'team' ? <TeamView /> : null}

        {tab === 'assistant' ? <AssistantView state={state} perform={perform} /> : null}

        {tab === 'profile' ? <ProfileView state={state} perform={perform} busy={busy} /> : null}
      </main>

      <nav className="fixed inset-x-0 bottom-0 z-40">
        <div className="mx-auto max-w-[600px] border-t border-[var(--site-border)] bg-[var(--site-surface)]/92 backdrop-blur-md">
          <div className="grid grid-cols-5 px-3 pt-1.5 pb-[max(0.4rem,env(safe-area-inset-bottom))]">
            {NAV.map((entry) => {
              const active = tab === entry.id;
              const Icon = entry.icon;
              return (
                <button
                  key={entry.id}
                  type="button"
                  onClick={() => setTab(entry.id)}
                  aria-label={entry.label}
                  aria-current={active ? 'page' : undefined}
                  className={`tap flex flex-col items-center gap-0.5 rounded-2xl pt-1.5 text-[10px] transition ${
                    active ? 'text-primary' : 'text-muted-foreground'
                  }`}
                >
                  {entry.id === 'assistant' ? (
                    <span
                      className={`-mt-5 flex size-12 items-center justify-center rounded-full shadow-md transition ${
                        active
                          ? 'bg-primary text-primary-foreground'
                          : 'border border-[var(--site-border)] bg-[var(--site-surface)] text-primary'
                      }`}
                    >
                      <Icon className="size-5" />
                    </span>
                  ) : (
                    <Icon className="size-5" strokeWidth={active ? 2.3 : 1.8} />
                  )}
                  <span className={entry.id === 'assistant' ? '-mt-1' : ''}>{entry.label}</span>
                </button>
              );
            })}
          </div>
        </div>
      </nav>

      <TaskEditor
        open={editor !== null}
        onOpenChange={(open) => {
          if (!open) setEditor(null);
        }}
        task={editor?.task}
        preset={editor?.preset}
        today={state.today}
        perform={perform}
      />

      <TaskDetail
        state={state}
        task={detailTask}
        segmentId={detail?.segmentId}
        open={Boolean(detail)}
        onOpenChange={(open) => {
          if (!open) setDetail(null);
        }}
        perform={perform}
        onEdit={(task) => {
          setDetail(null);
          setEditor({ task });
        }}
        onFocus={(task) => void startFocus(task.id)}
      />

      <FocusPanel state={state} open={focusOpen} onOpenChange={setFocusOpen} perform={perform} onStartFocus={startFocus} />

      <Toaster position="top-center" />
    </div>
  );
}
