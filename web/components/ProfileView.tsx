import { useEffect, useRef, useState } from 'react';
import {
  CalendarRange,
  ChartColumn,
  CircleCheck,
  Loader2,
  Plus,
  Sparkles,
  Trash2,
  TriangleAlert,
  Undo2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { api } from '@/lib/api';
import { clockToMinutes, monthDay, spanLabel, weekdayIndex, weekdayOptions } from '@/lib/date';
import { FIXED_META } from '@/lib/schedule';
import type { AppState, FixedKind, Perform, Summary } from '@/lib/types';
import { toast } from 'sonner';
import { MountainWash } from './MountainWash';

interface Props {
  state: AppState;
  perform: Perform;
  busy: boolean;
}

const KINDS: FixedKind[] = ['CLASS', 'FIXED', 'REST'];

export function ProfileView({ state, perform, busy }: Props) {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [adding, setAdding] = useState(false);
  const [title, setTitle] = useState('');
  const [kind, setKind] = useState<FixedKind>('CLASS');
  const [repeat, setRepeat] = useState(true);
  const [weekday, setWeekday] = useState(String(weekdayIndex(state.today)));
  const [date, setDate] = useState(state.today);
  const [start, setStart] = useState('08:00');
  const [end, setEnd] = useState('09:30');
  const [saving, setSaving] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const resetTimer = useRef<number | null>(null);

  useEffect(() => {
    let alive = true;
    api
      .summary()
      .then((result) => {
        if (alive) setSummary(result);
      })
      .catch(() => {});
    return () => {
      alive = false;
      if (resetTimer.current) window.clearTimeout(resetTimer.current);
    };
  }, [state.tasks.length, state.segments.length, state.focusSessions.length, state.today]);

  const blocks = [...state.fixedBlocks].sort((a, b) => {
    if (a.specific_date || b.specific_date) return String(a.specific_date || '9999').localeCompare(String(b.specific_date || '9999'));
    return (a.weekday || 0) - (b.weekday || 0) || a.start_minute - b.start_minute;
  });

  const quotaPercent = state.ai.dailyLimit ? Math.min(100, Math.round((state.ai.usedToday / state.ai.dailyLimit) * 100)) : 0;

  async function addBlock(event: React.FormEvent) {
    event.preventDefault();
    if (saving) return;
    const startMinute = clockToMinutes(start);
    const endMinute = clockToMinutes(end);
    if (!title.trim()) {
      toast.error('给这段固定安排起个名字。');
      return;
    }
    if (startMinute == null || endMinute == null || endMinute <= startMinute) {
      toast.error('结束时间要晚于开始时间。');
      return;
    }
    setSaving(true);
    const result = await perform(() =>
      api.addFixedBlock({
        title: title.trim(),
        kind,
        weekday: repeat ? Number(weekday) : null,
        date: repeat ? null : date,
        startMinute,
        endMinute,
      }),
    );
    setSaving(false);
    if (result) {
      toast.success('固定安排已加好，排期时会自动避开。');
      setTitle('');
      setAdding(false);
    }
  }

  async function removeBlock(id: string) {
    const result = await perform(() => api.deleteFixedBlock(id));
    if (result) toast.success('这段固定安排已移除。');
  }

  async function undo() {
    const result = await perform(() => api.undo());
    if (result?.undoLabel) toast.success(`已撤销：${result.undoLabel}`);
  }

  async function resetData() {
    if (!confirmReset) {
      setConfirmReset(true);
      if (resetTimer.current) window.clearTimeout(resetTimer.current);
      resetTimer.current = window.setTimeout(() => setConfirmReset(false), 5000);
      return;
    }
    if (resetTimer.current) window.clearTimeout(resetTimer.current);
    const result = await perform(() => api.resetData());
    if (result) {
      setConfirmReset(false);
      setSummary(null);
      toast.success('数据已经清空，可以重新开始。');
    }
  }

  return (
    <div className="page-gutter space-y-4 pt-4 pb-4">
      <section className="hero-wash relative overflow-hidden rounded-[30px] px-5 pt-5 pb-6">
        <MountainWash className="pointer-events-none absolute -right-9 -bottom-7 h-[135px] w-[108%] opacity-75" />
        <div className="relative flex items-center gap-3">
          <span className="flex size-14 items-center justify-center rounded-full bg-[var(--site-surface)]/80 font-serif text-xl font-semibold text-primary shadow-sm backdrop-blur">
            {state.profile.name.slice(0, 1)}
          </span>
          <div className="min-w-0">
            <p className="text-[11px] tracking-[0.16em] text-[var(--ink-soft)] uppercase">我的</p>
            <h1 className="mt-0.5 truncate font-serif text-[1.55rem] leading-tight font-semibold text-[var(--hero-ink)]">
              {state.profile.name}
            </h1>
            <p className="mt-1 text-[11px] text-[var(--ink-soft)]">Repland 体验版 · 数据和日程只存在这个站点</p>
          </div>
        </div>
      </section>

      <section className="card-paper rounded-[26px] px-4 py-4">
        <div className="flex items-center gap-2">
          <ChartColumn className="size-4 text-primary" />
          <h2 className="text-sm font-medium">这一个月</h2>
          <span className="ml-auto text-[10px] text-muted-foreground">{summary ? `${summary.facts.range.from} 起` : '正在整理'}</span>
        </div>
        {summary ? (
          <p className="mt-2.5 text-xs leading-6 text-muted-foreground">{summary.text}</p>
        ) : (
          <p className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" /> 正在汇总…
          </p>
        )}
      </section>

      <section className="card-paper rounded-[26px] px-4 py-4">
        <div className="flex items-center gap-2">
          <CalendarRange className="size-4 text-primary" />
          <h2 className="text-sm font-medium">固定安排</h2>
          <Button
            size="sm"
            variant="ghost"
            className="ml-auto h-7 rounded-full px-2.5 text-[11px]"
            onClick={() => setAdding((value) => !value)}
          >
            <Plus className="size-3.5" /> {adding ? '收起' : '添加'}
          </Button>
        </div>

        {blocks.length ? (
          <div className="mt-2.5 space-y-1.5">
            {blocks.map((block) => {
              const meta = FIXED_META[block.kind] || FIXED_META.FIXED;
              const when = block.specific_date
                ? monthDay(block.specific_date)
                : `每周${['一', '二', '三', '四', '五', '六', '日'][Number(block.weekday) - 1] || ''}`;
              return (
                <div key={block.id} className="flex items-center gap-2 rounded-xl bg-[var(--site-bg)]/50 px-3 py-2">
                  <span className={`shrink-0 rounded-full px-1.5 py-0.5 text-[10px] ${meta.className}`}>{meta.label}</span>
                  <span className="min-w-0 flex-1 truncate text-xs">{block.title}</span>
                  <span className="shrink-0 text-[10px] text-muted-foreground tabular-nums">
                    {when} {spanLabel(block.start_minute, block.end_minute)}
                  </span>
                  <button
                    type="button"
                    onClick={() => removeBlock(block.id)}
                    className="tap shrink-0 rounded-full p-1 text-muted-foreground transition hover:bg-[var(--site-surface)] hover:text-destructive"
                    aria-label={`删除${block.title}`}
                  >
                    <Trash2 className="size-3.5" />
                  </button>
                </div>
              );
            })}
          </div>
        ) : (
          <p className="mt-2 text-[11px] leading-5 text-muted-foreground">
            还没有固定安排
          </p>
        )}

        {adding ? (
          <form onSubmit={addBlock} className="mt-3 space-y-2.5 rounded-2xl border border-[var(--site-border)] bg-[var(--site-bg)]/40 p-3">
            <Input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="例如：高等数学课"
              maxLength={40}
              className="h-9 rounded-xl bg-[var(--site-surface)] text-xs"
              autoFocus
            />
            <div className="flex flex-wrap gap-1.5">
              {KINDS.map((value) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => setKind(value)}
                  className={`tap rounded-full px-2.5 py-1 text-[11px] transition ${
                    kind === value ? 'bg-primary text-primary-foreground' : `${FIXED_META[value].className} opacity-80`
                  }`}
                >
                  {FIXED_META[value].label}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setRepeat((value) => !value)}
                className="tap ml-auto rounded-full border border-[var(--site-border)] px-2.5 py-1 text-[11px] text-muted-foreground"
              >
                {repeat ? '每周重复' : '只这一次'}
              </button>
            </div>
            <div className="grid grid-cols-2 gap-2">
              {repeat ? (
                <Select value={weekday} onValueChange={setWeekday}>
                  <SelectTrigger className="h-9 w-full rounded-xl bg-[var(--site-surface)] text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {weekdayOptions().map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : (
                <Input
                  type="date"
                  value={date}
                  onChange={(event) => setDate(event.target.value)}
                  className="h-9 rounded-xl bg-[var(--site-surface)] text-xs"
                  aria-label="固定安排日期"
                />
              )}
              <div className="flex gap-1.5">
                <Input
                  type="time"
                  value={start}
                  onChange={(event) => setStart(event.target.value)}
                  className="h-9 min-w-0 rounded-xl bg-[var(--site-surface)] text-xs"
                  aria-label="开始时间"
                />
                <Input
                  type="time"
                  value={end}
                  onChange={(event) => setEnd(event.target.value)}
                  className="h-9 min-w-0 rounded-xl bg-[var(--site-surface)] text-xs"
                  aria-label="结束时间"
                />
              </div>
            </div>
            <Button type="submit" size="sm" className="h-9 w-full rounded-xl text-xs" disabled={saving || busy}>
              {saving ? <Loader2 className="size-3.5 animate-spin" /> : <Plus className="size-3.5" />} 加进固定安排
            </Button>
          </form>
        ) : null}
      </section>

      <section className="card-paper rounded-[26px] px-4 py-4">
        <div className="flex items-center gap-2">
          <Sparkles className="size-4 text-primary" />
          <h2 className="text-sm font-medium">AI 额度</h2>
          <span className="ml-auto text-[10px] text-muted-foreground">
            {state.ai.configured ? '可用' : '未开通'}
          </span>
        </div>
        <p className="mt-2 text-[11px] leading-5 text-muted-foreground">
          {state.ai.configured
            ? `今日 ${state.ai.usedToday} / ${state.ai.dailyLimit} 次`
            : 'AI 未开通 · 本地规则可用'}
        </p>
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-[var(--paper-deep)]">
          <div className="h-full rounded-full bg-primary transition-all" style={{ width: `${quotaPercent}%` }} />
        </div>
      </section>

      <section className="card-paper rounded-[26px] px-4 py-4">
        <div className="flex items-center gap-2">
          <CircleCheck className="size-4 text-primary" />
          <h2 className="text-sm font-medium">数据与账号</h2>
        </div>
        <div className="mt-3 grid grid-cols-2 gap-2">
          <Button variant="secondary" className="h-9 rounded-xl text-xs" disabled={busy || !state.lastOperation} onClick={undo}>
            <Undo2 className="size-3.5" /> 撤销上一步
          </Button>
          <Button
            variant={confirmReset ? 'destructive' : 'outline'}
            className="h-9 rounded-xl text-xs"
            disabled={busy}
            onClick={resetData}
          >
            {confirmReset ? <TriangleAlert className="size-3.5" /> : <Trash2 className="size-3.5" />}
            {confirmReset ? '再点一次清空' : '清空全部'}
          </Button>
        </div>
        <p className="mt-2 text-[10px] leading-5 text-muted-foreground">
          清空会删除任务、日程和对话，无法恢复。数据按浏览器保存，换设备会从空白开始。
        </p>
      </section>
    </div>
  );
}
