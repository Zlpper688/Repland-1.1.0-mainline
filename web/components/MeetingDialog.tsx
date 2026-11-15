import { useEffect, useMemo, useState } from 'react';
import { CalendarCheck2, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { minutesToClock, relativeDay } from '@/lib/date';
import type { BusyEntry, MeetingSuggestion, TeamMemberInfo, TeamSchedule } from '@/lib/types';
import { teamApi } from '@/lib/team';
import { toast } from 'sonner';

const DAY_START = 8 * 60;
const DAY_END = 22 * 60;
const DURATIONS = [30, 60, 90, 120];

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  members: TeamMemberInfo[];
  today: string;
  initial?: { date: string; startMinute: number } | null;
  onCreated: () => void;
}

function busyLabel(entry: BusyEntry) {
  return entry.title;
}

export function MeetingDialog({ open, onOpenChange, members, today, initial, onCreated }: Props) {
  const [title, setTitle] = useState('团队会议');
  const [duration, setDuration] = useState('60');
  const [days, setDays] = useState('10');
  const [memberIds, setMemberIds] = useState<string[]>([]);
  const [data, setData] = useState<TeamSchedule | null>(null);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<MeetingSuggestion | null>(null);
  const [viewMember, setViewMember] = useState('');
  const [viewDate, setViewDate] = useState('');
  const [saving, setSaving] = useState(false);

  const memberKey = memberIds.join(',');

  useEffect(() => {
    if (!open) return;
    const all = members.map((member) => member.id);
    setMemberIds(all);
    setTitle('团队会议');
    setDuration('60');
    setDays('10');
    setSelected(null);
    setViewMember(all[0] || '');
    setViewDate(initial?.date || today);
  }, [open, members, initial?.date, today]);

  useEffect(() => {
    if (!open || !memberIds.length) return;
    let alive = true;
    setLoading(true);
    teamApi
      .schedule({ days: Number(days), duration: Number(duration), members: memberIds })
      .then((next) => {
        if (!alive) return;
        setData(next);
        if (initial) {
          const match = next.suggestions.find((slot) => slot.date === initial.date) || null;
          setSelected(match);
          if (match) setViewDate(match.date);
        } else {
          setSelected((current) => {
            const keep = current && next.suggestions.find((slot) => slot.date === current.date && slot.startMinute === current.startMinute);
            return keep || next.suggestions[0] || null;
          });
        }
      })
      .catch((error) => toast.error(error.message || '读取课表失败'))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, days, duration, memberKey]);

  const day = useMemo(() => data?.days.find((entry) => entry.date === viewDate) || data?.days[0], [data, viewDate]);
  const activeMember = data?.members.find((member) => member.id === viewMember);
  const busy: BusyEntry[] = day?.members.find((entry) => entry.id === viewMember)?.busy || [];
  const proposed = selected && selected.date === day?.date ? selected : null;

  const conflicts = useMemo(() => {
    if (!proposed || !day) return [];
    return memberIds
      .filter((id) => {
        const entry = day.members.find((item) => item.id === id);
        return entry?.busy.some((item) => item.startMinute < proposed.endMinute && item.endMinute > proposed.startMinute);
      })
      .map((id) => data?.members.find((member) => member.id === id)?.name || id);
  }, [proposed, day, memberIds, data]);

  function toggleMember(id: string) {
    setMemberIds((current) => {
      if (current.includes(id)) {
        return current.length === 1 ? current : current.filter((item) => item !== id);
      }
      return [...current, id];
    });
  }

  async function confirm() {
    if (!selected || saving) return;
    setSaving(true);
    try {
      await teamApi.createMeeting({
        title: title.trim() || '团队会议',
        date: selected.date,
        startMinute: selected.startMinute,
        endMinute: selected.endMinute,
        memberIds,
      });
      toast.success(`定好了：${relativeDay(selected.date, today)} ${minutesToClock(selected.startMinute)}`);
      onCreated();
      onOpenChange(false);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '没有定成功');
    } finally {
      setSaving(false);
    }
  }

  const span = (start: number, end: number) => `${((start - DAY_START) / (DAY_END - DAY_START)) * 100}%`;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[88dvh] max-w-[560px] overflow-y-auto rounded-[26px] border-[var(--site-border)] bg-[var(--site-surface)] p-5">
        <DialogHeader className="gap-1">
          <DialogTitle className="font-serif text-xl">约个会</DialogTitle>
          <DialogDescription className="sr-only">约会议</DialogDescription>
        </DialogHeader>

        <div className="space-y-1.5">
          <Label htmlFor="meeting-title" className="text-xs text-muted-foreground">会议名称</Label>
          <Input
            id="meeting-title"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            maxLength={40}
            className="h-10 rounded-2xl bg-[var(--site-bg)]/60"
          />
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-1.5">
            <Label className="text-xs text-muted-foreground">开多久</Label>
            <Select value={duration} onValueChange={setDuration}>
              <SelectTrigger className="h-10 w-full rounded-2xl bg-[var(--site-bg)]/60">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {DURATIONS.map((value) => (
                  <SelectItem key={value} value={String(value)}>
                    {value >= 60 ? `${value / 60} 小时` : `${value} 分钟`}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label className="text-xs text-muted-foreground">看几天内</Label>
            <Select value={days} onValueChange={setDays}>
              <SelectTrigger className="h-10 w-full rounded-2xl bg-[var(--site-bg)]/60">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="7">一周内</SelectItem>
                <SelectItem value="10">十天</SelectItem>
                <SelectItem value="14">两周内</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="space-y-2">
          <p className="text-xs text-muted-foreground">谁来</p>
          <div className="flex flex-wrap gap-1.5">
            {members.map((member) => {
              const on = memberIds.includes(member.id);
              return (
                <button
                  key={member.id}
                  type="button"
                  onClick={() => toggleMember(member.id)}
                  className={`tap flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs transition ${
                    on
                      ? 'border-primary/40 bg-primary/12 text-primary'
                      : 'border-[var(--site-border)] bg-[var(--site-surface)] text-muted-foreground'
                  }`}
                >
                  <span className="flex size-5 items-center justify-center rounded-full bg-[var(--paper-deep)] text-[10px] font-medium text-primary">
                    {member.name.slice(0, 1)}
                  </span>
                  {member.name}
                </button>
              );
            })}
          </div>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">都有空的时间</p>
            {loading ? <Loader2 className="size-3.5 animate-spin text-muted-foreground" /> : null}
          </div>
          {data?.suggestions.length ? (
            <div className="space-y-1.5">
              {data.suggestions.slice(0, 4).map((slot) => {
                const active = selected?.date === slot.date && selected?.startMinute === slot.startMinute;
                return (
                  <button
                    key={`${slot.date}-${slot.startMinute}`}
                    type="button"
                    onClick={() => {
                      setSelected(slot);
                      setViewDate(slot.date);
                    }}
                    className={`tap flex w-full items-center justify-between gap-3 rounded-2xl border px-3.5 py-2.5 text-left transition ${
                      active
                        ? 'border-primary/45 bg-primary/10'
                        : 'border-[var(--site-border)] bg-[var(--site-surface)] hover:bg-[var(--paper-deep)]/40'
                    }`}
                  >
                    <div>
                      <p className="text-sm">
                        <span className="font-medium">{relativeDay(slot.date, today)}</span>
                        <span className="ml-1.5 text-xs text-muted-foreground tabular-nums">{slot.date.slice(5)}</span>
                      </p>
                      <p className="text-xs text-muted-foreground">共同空档 {Math.round((slot.freeMinutes / 60) * 10) / 10}h</p>
                    </div>
                    <div className="text-right">
                      <p className="font-serif text-base">{minutesToClock(slot.startMinute)}–{minutesToClock(slot.endMinute)}</p>
                    </div>
                  </button>
                );
              })}
            </div>
          ) : (
            <p className="rounded-2xl bg-[var(--paper-deep)]/50 px-3.5 py-3 text-xs text-muted-foreground">
              {loading ? '正在对照课表…' : '没有共同空档'}
            </p>
          )}
        </div>

        {day ? (
          <div className="space-y-2 rounded-2xl border border-[var(--site-border)] bg-[var(--site-bg)]/40 p-3">
            <div className="flex items-center justify-between">
              <p className="text-xs text-muted-foreground">逐人看</p>
              {proposed ? (
                <span className="rounded-full bg-primary/12 px-2 py-0.5 text-[10px] text-primary">提议 {minutesToClock(proposed.startMinute)}</span>
              ) : null}
            </div>
            <div className="flex flex-wrap gap-1">
              {(data?.members || []).map((member) => (
                <button
                  key={member.id}
                  type="button"
                  onClick={() => setViewMember(member.id)}
                  className={`tap rounded-full px-2.5 py-1 text-[11px] transition ${
                    member.id === viewMember ? 'bg-primary text-primary-foreground' : 'bg-[var(--site-surface)] text-muted-foreground'
                  }`}
                >
                  {member.name}
                </button>
              ))}
            </div>

            <div className="relative mt-1 h-14 rounded-xl bg-[var(--site-surface)]">
              {[...Array(7)].map((_, index) => (
                <span
                  key={index}
                  className="absolute top-0 bottom-0 w-px bg-[var(--site-border)]"
                  style={{ left: `${((9 + index * 2) * 60 - DAY_START) / (DAY_END - DAY_START) * 100}%` }}
                />
              ))}
              {busy.map((entry) => (
                <div
                  key={`${entry.kind}-${entry.startMinute}-${entry.title}`}
                  className={`absolute top-1.5 bottom-1.5 overflow-hidden rounded-lg px-1.5 py-1 text-[10px] leading-tight ${
                    entry.kind === 'meeting'
                      ? 'bg-[var(--cat-leisure-soft)] text-[var(--cat-leisure-text)]'
                      : 'bg-[var(--cat-course-soft)] text-[var(--cat-course-text)]'
                  }`}
                  style={{ left: span(entry.startMinute, entry.endMinute), width: `${((entry.endMinute - entry.startMinute) / (DAY_END - DAY_START)) * 100}%` }}
                  title={`${busyLabel(entry)} ${minutesToClock(entry.startMinute)}–${minutesToClock(entry.endMinute)}`}
                >
                  <span className="line-clamp-2">{entry.title}</span>
                </div>
              ))}
              {proposed ? (
                <div
                  className="absolute top-1 bottom-1 rounded-lg border border-dashed border-primary bg-primary/15"
                  style={{
                    left: span(proposed.startMinute, proposed.endMinute),
                    width: `${((proposed.endMinute - proposed.startMinute) / (DAY_END - DAY_START)) * 100}%`,
                  }}
                />
              ) : null}
            </div>
            <div className="flex justify-between text-[10px] text-muted-foreground">
              <span>08:00</span>
              <span>12:00</span>
              <span>16:00</span>
              <span>20:00</span>
            </div>
            <p className="text-[11px] text-muted-foreground">
              {activeMember ? (
                busy.length ? (
                  `${activeMember.name} 当天 ${busy.length} 段安排 · 绿=课 · 琥珀=会议`
                ) : (
                  `${activeMember.name} 当天没有安排`
                )
              ) : null}
            </p>
            {conflicts.length ? <p className="text-[11px] text-destructive">{conflicts.join('、')} 有冲突</p> : null}
          </div>
        ) : null}

        <div className="flex items-center gap-2">
          <Button variant="ghost" className="h-11 flex-1 rounded-2xl" onClick={() => onOpenChange(false)}>
            再想想
          </Button>
          <Button
            className="h-11 flex-[1.4] rounded-2xl"
            disabled={!selected || saving || loading}
            onClick={() => void confirm()}
          >
            {saving ? <Loader2 className="size-4 animate-spin" /> : <CalendarCheck2 className="size-4" />}
            {selected ? `就定 ${relativeDay(selected.date, today)} ${minutesToClock(selected.startMinute)}` : '选个时间'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
