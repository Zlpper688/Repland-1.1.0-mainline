import { useEffect, useState } from 'react';
import { Loader2, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { clockToMinutes, minutesToClock } from '@/lib/date';
import type { TeamMemberInfo, TimetableEntry } from '@/lib/types';
import { teamApi } from '@/lib/team';
import { toast } from 'sonner';

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  member: TeamMemberInfo | null;
  onSaved: () => void;
}

export function TimetableDialog({ open, onOpenChange, member, onSaved }: Props) {
  const [entries, setEntries] = useState<TimetableEntry[]>([]);
  const [title, setTitle] = useState('');
  const [weekday, setWeekday] = useState('1');
  const [start, setStart] = useState('09:00');
  const [end, setEnd] = useState('11:00');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open || !member) return;
    setEntries([...(member.timetable || [])]);
    setTitle('');
    setWeekday('1');
    setStart('09:00');
    setEnd('11:00');
  }, [open, member]);

  function addEntry() {
    const startMinute = clockToMinutes(start);
    const endMinute = clockToMinutes(end);
    if (!title.trim()) {
      toast.error('给这节课起个名字。');
      return;
    }
    if (startMinute == null || endMinute == null || endMinute <= startMinute) {
      toast.error('结束时间要晚于开始时间。');
      return;
    }
    setEntries((current) =>
      [...current, { title: title.trim(), weekday: Number(weekday), startMinute, endMinute, kind: 'CLASS' as const }].sort(
        (a, b) => a.weekday - b.weekday || a.startMinute - b.startMinute,
      ),
    );
    setTitle('');
  }

  async function save() {
    if (!member || saving) return;
    setSaving(true);
    try {
      await teamApi.saveTimetable(member.id, entries);
      toast.success(`${member.name} 的课表已保存`);
      onSaved();
      onOpenChange(false);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '没有保存成功');
    } finally {
      setSaving(false);
    }
  }

  const hours = entries.reduce((sum, entry) => sum + (entry.endMinute - entry.startMinute), 0) / 60;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[88dvh] max-w-[520px] overflow-y-auto rounded-[26px] border-[var(--site-border)] bg-[var(--site-surface)] p-5">
        <DialogHeader className="gap-1">
          <DialogTitle className="font-serif text-xl">{member?.name} 的课表</DialogTitle>
          <DialogDescription className="sr-only">{member?.name} 的课表</DialogDescription>
        </DialogHeader>

        <div className="flex items-center justify-between rounded-2xl bg-[var(--paper-deep)]/50 px-3.5 py-2 text-xs text-muted-foreground">
          <span>{entries.length} 段固定安排</span>
          <span>每周约 {Math.round(hours * 10) / 10} 小时</span>
        </div>

        <div className="space-y-1.5">
          {entries.length ? (
            entries.map((entry, index) => (
              <div
                key={`${entry.weekday}-${entry.startMinute}-${index}`}
                className="flex items-center gap-2 rounded-xl border border-[var(--site-border)] bg-[var(--site-bg)]/40 px-3 py-2"
              >
                <span className="w-9 shrink-0 text-xs text-muted-foreground">{WEEKDAYS[entry.weekday]}</span>
                <span className="flex-1 truncate text-sm">{entry.title}</span>
                <span className="text-xs text-muted-foreground">
                  {minutesToClock(entry.startMinute)}–{minutesToClock(entry.endMinute)}
                </span>
                <button
                  type="button"
                  aria-label={`删除 ${entry.title}`}
                  onClick={() => setEntries((current) => current.filter((_, at) => at !== index))}
                  className="tap rounded-full p-1 text-muted-foreground hover:text-destructive"
                >
                  <Trash2 className="size-3.5" />
                </button>
              </div>
            ))
          ) : (
            <p className="rounded-xl bg-[var(--paper-deep)]/40 px-3 py-3 text-xs text-muted-foreground">还没有课</p>
          )}
        </div>

        <div className="space-y-2 rounded-2xl border border-dashed border-[var(--site-border)] p-3">
          <p className="text-xs text-muted-foreground">加一段</p>
          <div className="grid grid-cols-[1fr_92px] gap-2">
            <Input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="课程名，比如 高数"
              maxLength={30}
              className="h-9 rounded-xl bg-[var(--site-bg)]/60"
            />
            <Select value={weekday} onValueChange={setWeekday}>
              <SelectTrigger className="h-9 w-full rounded-xl bg-[var(--site-bg)]/60">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {WEEKDAYS.map((label, index) => (
                  <SelectItem key={label} value={String(index)}>
                    {label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center gap-2">
            <div className="flex-1 space-y-1">
              <Label className="text-[10px] text-muted-foreground">开始</Label>
              <Input type="time" value={start} onChange={(event) => setStart(event.target.value)} className="h-9 rounded-xl bg-[var(--site-bg)]/60" />
            </div>
            <div className="flex-1 space-y-1">
              <Label className="text-[10px] text-muted-foreground">结束</Label>
              <Input type="time" value={end} onChange={(event) => setEnd(event.target.value)} className="h-9 rounded-xl bg-[var(--site-bg)]/60" />
            </div>
            <Button type="button" variant="secondary" className="mt-4 h-9 rounded-xl" onClick={addEntry}>
              <Plus className="size-3.5" /> 加
            </Button>
          </div>
        </div>

        <div className="flex gap-2">
          <Button variant="ghost" className="h-11 flex-1 rounded-2xl" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button className="h-11 flex-1 rounded-2xl" disabled={saving} onClick={() => void save()}>
            {saving ? <Loader2 className="size-4 animate-spin" /> : null}
            保存课表
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
