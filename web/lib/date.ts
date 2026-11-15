const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];
const WEEKDAY_FULL = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'];

export function parseDateText(text: string | null | undefined): Date | null {
  if (!text || !/^\d{4}-\d{2}-\d{2}$/.test(text)) return null;
  const date = new Date(`${text}T00:00:00Z`);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function addDays(text: string, days: number): string {
  const date = parseDateText(text);
  if (!date) return text;
  date.setUTCDate(date.getUTCDate() + days);
  return formatDate(date);
}

export function diffDays(target: string, from: string): number {
  const left = parseDateText(target);
  const right = parseDateText(from);
  if (!left || !right) return 0;
  return Math.round((left.getTime() - right.getTime()) / 86_400_000);
}

export function weekdayIndex(text: string): number {
  const date = parseDateText(text);
  if (!date) return 1;
  const day = date.getUTCDay();
  return day === 0 ? 7 : day;
}

export function weekdayShort(text: string): string {
  return WEEKDAYS[weekdayIndex(text) - 1];
}

export function weekdayFull(text: string): string {
  return WEEKDAY_FULL[weekdayIndex(text) - 1];
}

export function monthDay(text: string): string {
  const date = parseDateText(text);
  if (!date) return text;
  return `${date.getUTCMonth() + 1}月${date.getUTCDate()}日`;
}

export function dayNumber(text: string): string {
  return String(parseDateText(text)?.getUTCDate() ?? '');
}

export function relativeDay(text: string, today: string): string {
  const days = diffDays(text, today);
  if (days === 0) return '今天';
  if (days === 1) return '明天';
  if (days === 2) return '后天';
  if (days === -1) return '昨天';
  if (days > 2 && days < 7) return `周${weekdayShort(text)}`;
  return monthDay(text);
}

export function minutesToClock(minutes: number | null | undefined): string {
  if (minutes == null || !Number.isFinite(Number(minutes))) return '--:--';
  const value = Math.max(0, Math.min(24 * 60, Math.round(Number(minutes))));
  return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
}

export function clockToMinutes(text: string | null | undefined): number | null {
  if (!text) return null;
  const match = text.trim().match(/^(\d{1,2}):(\d{2})$/);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return hour * 60 + minute;
}

export function spanLabel(startMinute: number, endMinute: number): string {
  return `${minutesToClock(startMinute)}–${minutesToClock(endMinute)}`;
}

export function durationLabel(minutes: number | null | undefined): string {
  if (minutes == null) return '未估时';
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest ? `${hours} 小时 ${rest} 分钟` : `${hours} 小时`;
}

export function weekdayOptions(): Array<{ value: string; label: string }> {
  return WEEKDAYS.map((label, index) => ({ value: String(index + 1), label: `星期${label}` }));
}

export function greeting(nowMinute: number): string {
  const hour = Math.floor(nowMinute / 60);
  if (hour < 6) return '夜深了';
  if (hour < 11) return '早上好';
  if (hour < 14) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
}
