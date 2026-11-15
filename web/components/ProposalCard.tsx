import { CalendarCheck2, CalendarClock, Check, CircleSlash, Clock3, Loader2, PencilLine, TriangleAlert } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { minutesToClock, relativeDay } from '@/lib/date';
import type { ProposalBody, ProposalItem } from '@/lib/types';

interface Props {
  proposal: ProposalBody;
  today: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  onEdit: () => void;
}

const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

function weekdayRangeText(weekdays: number[]): string {
  const days = [...new Set(weekdays)].sort((a, b) => a - b);
  if (days.length === 7) return '每天';
  if (days.length === 5 && days.every((day, index) => day === index + 1)) return '工作日';
  if (days.length === 2 && days[0] === 6 && days[1] === 7) return '周末';
  return `每周${days.map((day) => WEEKDAY_LABELS[day - 1] ?? '?').join('、')}`;
}

function routineText(item: ProposalItem, today: string): string {
  const range = item.weekdays?.length ? weekdayRangeText(item.weekdays) : relativeDay(item.date || today, today);
  const clock = item.startMinute != null && item.endMinute != null
    ? ` ${minutesToClock(item.startMinute)}–${minutesToClock(item.endMinute)}`
    : '';
  if (item.remove) {
    return `取消「${item.name}」${item.weekdays?.length ? `${range}的` : '的'}固定安排`;
  }
  return `把「${item.name}」设为${range}${clock} 的固定安排${item.autoPicked ? '（按空档挑的）' : ''}`;
}

function itemText(item: ProposalItem, today: string): { icon: React.ReactNode; text: string } {
  if (item.action === 'routine') return { icon: <CalendarClock className="size-3.5" />, text: routineText(item, today) };
  if (item.action === 'complete') return { icon: <Check className="size-3.5" />, text: `把「${item.name}」记为完成` };
  if (item.action === 'cancel') return { icon: <CircleSlash className="size-3.5" />, text: `取消「${item.name}」` };
  if (item.action === 'progress')
    return { icon: <Clock3 className="size-3.5" />, text: `把「${item.name}」更新为 ${item.progress ?? 0}%` };
  if (item.action === 'postpone') return { icon: <CalendarClock className="size-3.5" />, text: `把「${item.name}」往后挪一天` };
  if (item.action === 'update') return { icon: <CalendarCheck2 className="size-3.5" />, text: `更新「${item.name}」的信息` };
  if (item.placement) {
    const when = `${relativeDay(item.placement.date, today)} ${minutesToClock(item.placement.startMinute)}–${minutesToClock(item.placement.endMinute)}`;
    if (item.move) {
      if (!item.moveFrom) {
        return {
          icon: <CalendarClock className="size-3.5" />,
          text: `把「${item.name}」排到 ${when}${item.placement.nestedUnderTitle ? `（挂进「${item.placement.nestedUnderTitle}」）` : ''}`,
        };
      }
      const from = `${relativeDay(item.moveFrom.date, today)} ${minutesToClock(item.moveFrom.startMinute)}–${minutesToClock(item.moveFrom.endMinute)}`;
      return {
        icon: <CalendarClock className="size-3.5" />,
        text: `把「${item.name}」从 ${from} 改到 ${when}${item.placement.nestedUnderTitle ? `（挂进「${item.placement.nestedUnderTitle}」）` : ''}`,
      };
    }
    const verb = item.kind === 'create' ? '记下并安排' : '把';
    const suffix = item.kind === 'create' ? `「${item.name}」` : `「${item.name}」放进`;
    return {
      icon: <CalendarCheck2 className="size-3.5" />,
      text: `${verb}${suffix} ${when}${item.placement.nestedUnderTitle ? `（挂进「${item.placement.nestedUnderTitle}」）` : ''}`,
    };
  }
  return { icon: <CalendarClock className="size-3.5" />, text: `先记下「${item.name}」，暂不占时间` };
}

export function ProposalCard({ proposal, today, busy, onConfirm, onCancel, onEdit }: Props) {
  const items = proposal.items || [];
  return (
    <div className="card-paper overflow-hidden rounded-[24px]">
      <div className="flex items-center justify-between gap-2 border-b border-[var(--site-border)] bg-[var(--site-bg)]/45 px-4 py-3">
        <div className="flex items-center gap-2">
          <span className="flex size-6 items-center justify-center rounded-full bg-primary text-[10px] font-semibold text-primary-foreground">
            满
          </span>
          <p className="text-xs font-medium">待确认</p>
        </div>
        <span className="rounded-full bg-[var(--paper-deep)] px-2 py-0.5 text-[10px] text-muted-foreground">
          {proposal.source === 'ai' ? '模型理解' : '本地规则'}
        </span>
      </div>
      <div className="px-4 py-3.5">
        {proposal.reply ? <p className="text-sm leading-6">{proposal.reply}</p> : null}
        <ul className="mt-2.5 space-y-2">
          {items.map((item) => {
            const view = itemText(item, today);
            return (
              <li key={item.itemId} className="flex items-start gap-2 text-xs leading-5">
                <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-[var(--paper-deep)] text-primary">
                  {view.icon}
                </span>
                <span>{view.text}</span>
              </li>
            );
          })}
        </ul>
        {proposal.unplaced?.length ? (
          <div className="mt-3 flex items-start gap-2 rounded-xl bg-[var(--priority-high-bg)] px-3 py-2 text-[11px] leading-5 text-[var(--priority-high-fg)]">
            <TriangleAlert className="mt-0.5 size-3.5 shrink-0" />
            <span>{proposal.unplaced.map((entry) => entry.name).join('、')} 放不进空档 · 留在清单</span>
          </div>
        ) : null}
        {proposal.adjustedPinned ? (
          <p className="mt-2 text-[11px] leading-5 text-muted-foreground">
            {proposal.pinnedPast ? '那个时间已经过了 · 已排到最近空档' : '时段被占 · 已退到最近空档'}
          </p>
        ) : null}
        <div className="mt-4 flex gap-2">
          <Button variant="ghost" className="h-9 rounded-xl px-3 text-xs" disabled={busy} onClick={onCancel}>
            取消
          </Button>
          <Button variant="secondary" className="h-9 flex-1 rounded-xl text-xs" disabled={busy} onClick={onEdit}>
            <PencilLine className="size-3.5" /> 改一下
          </Button>
          <Button className="h-9 flex-[1.4] rounded-xl text-xs" disabled={busy} onClick={onConfirm}>
            {busy ? <Loader2 className="size-3.5 animate-spin" /> : <Check className="size-3.5" />}
            确认执行
          </Button>
        </div>
      </div>
    </div>
  );
}
