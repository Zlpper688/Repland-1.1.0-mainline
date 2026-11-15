import { useCallback, useEffect, useMemo, useState } from 'react';
import { CalendarClock, ChevronRight, Loader2, RefreshCw, Trash2, UsersRound } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { MeetingDialog } from '@/components/MeetingDialog';
import { TimetableDialog } from '@/components/TimetableDialog';
import { minutesToClock, relativeDay } from '@/lib/date';
import type { TeamMemberInfo, TeamSchedule } from '@/lib/types';
import { teamApi } from '@/lib/team';
import { toast } from 'sonner';

type Status = 'loading' | 'ready' | 'error';

export function TeamView() {
  const [status, setStatus] = useState<Status>('loading');
  const [data, setData] = useState<TeamSchedule | null>(null);
  const [meetingOpen, setMeetingOpen] = useState(false);
  const [initialSlot, setInitialSlot] = useState<{ date: string; startMinute: number } | null>(null);
  const [timetableMember, setTimetableMember] = useState<TeamMemberInfo | null>(null);
  const [confirmId, setConfirmId] = useState('');

  const load = useCallback(async (withSpinner = true) => {
    if (withSpinner) setStatus('loading');
    const attempt = () => teamApi.schedule({ days: 10, duration: 60 });
    try {
      let next: TeamSchedule;
      try {
        next = await attempt();
      } catch {
        // A cold Function start right after a deployment can fail once; retry before showing the error card.
        await new Promise((resolve) => window.setTimeout(resolve, 800));
        next = await attempt();
      }
      setData(next);
      setStatus('ready');
    } catch (error) {
      setStatus('error');
      const raw = error instanceof Error ? error.message : '';
      const friendly = raw && !/^[a-z_]+$/.test(raw) ? raw : '服务刚重启，点重新加载就好。';
      toast.error(friendly);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!confirmId) return;
    const timer = window.setTimeout(() => setConfirmId(''), 4000);
    return () => window.clearTimeout(timer);
  }, [confirmId]);

  const meetings = data?.meetings || [];
  const totalClassHours = useMemo(
    () => (data?.members || []).reduce((sum, member) => sum + (Number(member.classHours) || 0), 0),
    [data],
  );

  function enter() {
    void load();
  }

  async function removeMeeting(id: string) {
    if (confirmId !== id) {
      setConfirmId(id);
      return;
    }
    setConfirmId('');
    try {
      await teamApi.deleteMeeting(id);
      toast.success('会议已取消');
      void load(false);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '没有删掉');
    }
  }

  if (status === 'loading' && !data) {
    return (
      <div className="page-gutter flex min-h-[60dvh] flex-col items-center justify-center gap-2 pt-4">
        <Loader2 className="size-5 animate-spin text-primary" />
        <p className="text-xs text-muted-foreground">正在对照大家的课表…</p>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="page-gutter space-y-4 pt-6 pb-4">
        <section className="hero-wash relative overflow-hidden rounded-[30px] px-5 pt-6 pb-7">
          <div className="flex items-center gap-2 text-[11px] tracking-[0.18em] text-muted-foreground">
            <UsersRound className="size-3.5" />
            REPLAND · 团队
          </div>
          <h1 className="mt-2 font-serif text-2xl">演示队伍还没读出来</h1>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">网络中断，请重试</p>
        </section>
        <Button className="h-11 w-full rounded-2xl" onClick={enter}>
          <RefreshCw className="size-4" />
          重新加载
        </Button>
      </div>
    );
  }

  return (
    <div className="page-gutter space-y-4 pt-4 pb-4">
      <section className="hero-wash relative overflow-hidden rounded-[30px] px-5 pt-5 pb-6">
        <div className="flex items-start justify-between">
          <div>
            <p className="flex items-center gap-1.5 text-[11px] tracking-[0.18em] text-muted-foreground">
              <UsersRound className="size-3.5" />
              REPLAND · 团队
            </p>
            <h1 className="mt-1.5 font-serif text-2xl">{data.teamName}</h1>
            <p className="mt-1 text-xs text-muted-foreground">
              {data.members.length} 人 · 每周约 {Math.round(totalClassHours)} 节课 · 今天 {data.today.slice(5)} · 演示数据
            </p>
          </div>
          <button
            type="button"
            aria-label="刷新"
            onClick={() => void load(false)}
            className="tap rounded-full bg-[var(--site-surface)]/80 p-2 text-muted-foreground"
          >
            <RefreshCw className="size-4" />
          </button>
        </div>

        <div className="mt-4 flex items-center justify-between gap-3">
          <div className="flex -space-x-2">
            {data.members.map((member) => (
              <span
                key={member.id}
                title={`${member.name}${member.role ? ` · ${member.role}` : ''}`}
                className="flex size-8 items-center justify-center rounded-full border-2 border-[var(--hero-to)] bg-[var(--site-surface)] text-[11px] font-medium text-primary"
              >
                {member.name.slice(0, 1)}
              </span>
            ))}
          </div>
          <Button
            className="h-10 rounded-full px-4"
            onClick={() => {
              setInitialSlot(null);
              setMeetingOpen(true);
            }}
          >
            <CalendarClock className="size-4" />
            约个会
          </Button>
        </div>
      </section>

      <section className="space-y-3 rounded-[24px] border border-[var(--site-border)] bg-[var(--site-surface)] p-4">
        <div className="flex items-baseline justify-between">
          <h2 className="text-sm font-medium">都有空的时间</h2>
        </div>
        {data.suggestions.length ? (
          <div className="space-y-1.5">
            {data.suggestions.slice(0, 3).map((slot) => (
              <button
                key={`${slot.date}-${slot.startMinute}`}
                type="button"
                onClick={() => {
                  setInitialSlot({ date: slot.date, startMinute: slot.startMinute });
                  setMeetingOpen(true);
                }}
                className="tap flex w-full items-center justify-between rounded-2xl bg-[var(--site-bg)]/55 px-3.5 py-2.5 text-left transition hover:bg-[var(--paper-deep)]/60"
              >
                <span>
                  <span className="text-sm font-medium">{relativeDay(slot.date, data.today)}</span>
                  <span className="ml-2 text-[11px] text-muted-foreground tabular-nums">{slot.date.slice(5)}</span>
                </span>
                <span className="flex items-center gap-1 text-sm">
                  {minutesToClock(slot.startMinute)}–{minutesToClock(slot.endMinute)}
                  <ChevronRight className="size-3.5 text-muted-foreground" />
                </span>
              </button>
            ))}
          </div>
        ) : (
          <p className="rounded-2xl bg-[var(--paper-deep)]/40 px-3.5 py-3 text-xs text-muted-foreground">
            十天里没有共同空档
          </p>
        )}
      </section>

      <section className="space-y-2">
        <div className="flex items-baseline justify-between px-1">
          <h2 className="text-sm font-medium">成员课表</h2>
        </div>
        <div className="overflow-hidden rounded-[24px] border border-[var(--site-border)] bg-[var(--site-surface)]">
          {data.members.map((member, index) => (
            <button
              key={member.id}
              type="button"
              onClick={() => setTimetableMember(member)}
              className={`tap flex w-full items-center gap-3 px-4 py-3 text-left transition hover:bg-[var(--paper-deep)]/40 ${
                index ? 'border-t border-[var(--site-border)]' : ''
              }`}
            >
              <span className="flex size-9 items-center justify-center rounded-full bg-[var(--paper-deep)] text-sm font-medium text-primary">
                {member.name.slice(0, 1)}
              </span>
              <span className="flex-1">
                <span className="flex items-center gap-1.5 text-sm">
                  {member.name}
                  {member.role ? <span className="text-[10px] text-muted-foreground">{member.role}</span> : null}
                </span>
                <span className="text-[11px] text-muted-foreground">
                  每周 {member.timetable.length} 节课 · 约 {Math.round(member.classHours * 10) / 10} 小时
                </span>
              </span>
              <ChevronRight className="size-4 text-muted-foreground" />
            </button>
          ))}
        </div>
      </section>

      <section className="space-y-2">
        <div className="flex items-baseline justify-between px-1">
          <h2 className="text-sm font-medium">即将开会</h2>
          {meetings.length ? (
            <span className="text-[10px] text-muted-foreground">{meetings.length} 场</span>
          ) : null}
        </div>
        {meetings.length ? (
          <div className="space-y-1.5">
            {meetings.map((meeting) => (
              <div
                key={meeting.id}
                className="flex items-center gap-3 rounded-2xl border border-[var(--site-border)] bg-[var(--site-surface)] px-4 py-3"
              >
                <div className="w-12 shrink-0 text-center">
                  <p className="font-serif text-base leading-tight">{meeting.date.slice(8)}</p>
                  <p className="text-[10px] text-muted-foreground">{relativeDay(meeting.date, data.today)}</p>
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{meeting.title}</p>
                  <p className="truncate text-[11px] text-muted-foreground">
                    {minutesToClock(meeting.startMinute)}–{minutesToClock(meeting.endMinute)} · {meeting.memberNames.join('、')}
                  </p>
                </div>
                <button
                  type="button"
                  aria-label="取消会议"
                  onClick={() => void removeMeeting(meeting.id)}
                  className={`tap rounded-full px-2 py-1 text-[10px] transition ${
                    confirmId === meeting.id ? 'bg-destructive/10 text-destructive' : 'text-muted-foreground hover:text-destructive'
                  }`}
                >
                  {confirmId === meeting.id ? '确认取消' : <Trash2 className="size-3.5" />}
                </button>
              </div>
            ))}
          </div>
        ) : (
          <p className="rounded-2xl border border-dashed border-[var(--site-border)] px-4 py-4 text-xs text-muted-foreground">
            还没有会议
          </p>
        )}
      </section>

      <MeetingDialog
        open={meetingOpen}
        onOpenChange={setMeetingOpen}
        members={data.members}
        today={data.today}
        initial={initialSlot}
        onCreated={() => void load(false)}
      />
      <TimetableDialog
        open={Boolean(timetableMember)}
        onOpenChange={(open) => {
          if (!open) setTimetableMember(null);
        }}
        member={timetableMember}
        onSaved={() => void load(false)}
      />
    </div>
  );
}
