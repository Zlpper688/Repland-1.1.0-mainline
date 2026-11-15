// Weekly report card rendered locally, then hosted by the shared backend so QQ
// can fetch it. @napi-rs/canvas ships prebuilt Skia binaries for Windows.
import { existsSync } from 'node:fs';
import { createCanvas, GlobalFonts } from '@napi-rs/canvas';

const FAMILY = 'ReplandCard';
const REGULAR = 'C:\\Windows\\Fonts\\msyh.ttc';
const BOLD = 'C:\\Windows\\Fonts\\msyhbd.ttc';
if (existsSync(REGULAR)) GlobalFonts.registerFromPath(REGULAR, FAMILY);
if (existsSync(BOLD)) GlobalFonts.registerFromPath(BOLD, `${FAMILY} Bold`);

const W = 900;
const PAD = 48;
const CW = W - PAD * 2;
const C = {
  paper: '#f6f0e4',
  card: '#fffdf8',
  ink: '#24201b',
  muted: '#74695a',
  line: '#e3dacb',
  track: '#eae2d4',
  green: '#2f7a5a',
  greenSoft: '#dcebe2',
  amber: '#b98338',
  amberSoft: '#f4e6cf',
  red: '#b4553f',
  redSoft: '#f3ded7',
};

function roundRect(ctx, x, y, w, h, r) {
  const radius = Math.min(r, h / 2, w / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.lineTo(x + w - radius, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + radius);
  ctx.lineTo(x + w, y + h - radius);
  ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h);
  ctx.lineTo(x + radius, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - radius);
  ctx.lineTo(x, y + radius);
  ctx.quadraticCurveTo(x, y, x + radius, y);
  ctx.closePath();
}

function fitText(ctx, text, maxWidth) {
  const value = String(text ?? '');
  if (ctx.measureText(value).width <= maxWidth) return value;
  let low = 0;
  let high = value.length;
  while (low < high) {
    const mid = Math.ceil((low + high) / 2);
    if (ctx.measureText(`${value.slice(0, mid)}…`).width <= maxWidth) low = mid;
    else high = mid - 1;
  }
  return `${value.slice(0, low)}…`;
}

function drawBar(ctx, x, y, width, height, percent, color) {
  ctx.fillStyle = C.track;
  roundRect(ctx, x, y, width, height, height / 2);
  ctx.fill();
  const filled = Math.max(percent > 0 ? height : 0, Math.round((Math.max(0, Math.min(100, percent)) / 100) * width));
  if (!filled) return;
  ctx.fillStyle = color;
  roundRect(ctx, x, y, filled, height, height / 2);
  ctx.fill();
}

function sectionTitle(ctx, y, title, note = '') {
  ctx.fillStyle = C.ink;
  ctx.font = `700 22px "${FAMILY} Bold"`;
  ctx.textAlign = 'left';
  ctx.fillText(title, PAD, y);
  if (note) {
    ctx.fillStyle = C.muted;
    ctx.font = `400 13px "${FAMILY}"`;
    ctx.textAlign = 'right';
    ctx.fillText(note, W - PAD, y);
    ctx.textAlign = 'left';
  }
  return y + 34;
}

function generatedLabel(iso) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(date);
  const pick = (type) => parts.find((part) => part.type === type)?.value || '';
  return `${pick('month')}-${pick('day')} ${pick('hour')}:${pick('minute')}`;
}

export function renderWeeklyCard(report) {
  const stats = report.stats || {};
  const done = (report.done || []).slice(0, 6);
  const doing = (report.doing || []).slice(0, 4);
  const members = (report.members || []).filter((member) => member.doneHours > 0 || member.remainingHours > 0).slice(0, 6);
  const deliveredMode = members.some((member) => member.doneHours > 0);
  const risks = [
    ...(report.blocked || []).map((item) => ({ kind: 'blocked', text: `${item.title} · 等「${item.waits.join('、')}」` })),
    ...(report.due || []).map((item) => ({
      kind: 'due',
      text: `${item.title} · ${item.deadline} · ${item.leftDays < 0 ? `逾期 ${-item.leftDays} 天` : item.leftDays === 0 ? '今天截止' : `还剩 ${item.leftDays} 天`}`,
    })),
  ].slice(0, 6);

  const membersHeight = members.length ? 34 + members.length * 52 + 26 : 0;
  const doneHeight = 34 + Math.max(1, done.length) * 50 + 26;
  const doingHeight = doing.length ? 34 + doing.length * 58 + 14 : 0;
  const riskHeight = risks.length ? 34 + risks.length * 30 + 14 : 0;
  const height = 156 + 118 + membersHeight + doneHeight + doingHeight + riskHeight + 64;

  const canvas = createCanvas(W, height);
  const ctx = canvas.getContext('2d');
  ctx.textBaseline = 'alphabetic';

  ctx.fillStyle = C.paper;
  ctx.fillRect(0, 0, W, height);
  ctx.fillStyle = C.green;
  ctx.fillRect(0, 0, W, 10);

  // Header
  ctx.fillStyle = C.muted;
  ctx.font = `500 15px "${FAMILY}"`;
  ctx.fillText('REPLAND · 团队周报', PAD, 58);
  ctx.fillStyle = C.ink;
  ctx.font = `700 44px "${FAMILY} Bold"`;
  ctx.fillText(fitText(ctx, report.teamName || '团队', CW - 220), PAD, 112);
  ctx.textAlign = 'right';
  ctx.fillStyle = C.ink;
  ctx.font = `600 21px "${FAMILY} Bold"`;
  ctx.fillText(report.range?.label || '', W - PAD, 70);
  ctx.fillStyle = C.muted;
  ctx.font = `400 13px "${FAMILY}"`;
  ctx.fillText(`生成于 ${generatedLabel(report.generatedAt)}`, W - PAD, 98);
  ctx.textAlign = 'left';

  // Stats
  const tiles = [
    ['本周归档', `${stats.doneCount ?? 0} 张`],
    ['交付时长', `${stats.deliveredHours ?? 0}h`],
    ['进行中', `${stats.doingCount ?? 0} 张`],
    ['风险 / 临期', `${stats.blockedCount ?? 0} / ${stats.dueCount ?? 0}`],
  ];
  const tileGap = 12;
  const tileW = (CW - tileGap * 3) / 4;
  let y = 156;
  tiles.forEach(([label, value], index) => {
    const x = PAD + index * (tileW + tileGap);
    ctx.fillStyle = C.card;
    roundRect(ctx, x, y, tileW, 96, 16);
    ctx.fill();
    ctx.strokeStyle = C.line;
    ctx.lineWidth = 1;
    ctx.stroke();
    ctx.fillStyle = C.muted;
    ctx.font = `400 14px "${FAMILY}"`;
    ctx.fillText(label, x + 18, y + 32);
    ctx.fillStyle = index === 3 && (stats.blockedCount || stats.dueCount) ? C.amber : C.ink;
    ctx.font = `700 30px "${FAMILY} Bold"`;
    ctx.fillText(String(value), x + 18, y + 72);
  });
  y += 96 + 22;

  // Members
  if (members.length) {
    y = sectionTitle(ctx, y + 16, deliveredMode ? '成员交付' : '剩余工作量', deliveredMode ? '按本周归档工时' : '按未完成剩余工时');
    const key = deliveredMode ? 'doneHours' : 'remainingHours';
    const max = Math.max(...members.map((member) => member[key] || 0), 1);
    for (const member of members) {
      const value = member[key] || 0;
      ctx.fillStyle = C.ink;
      ctx.font = `600 17px "${FAMILY} Bold"`;
      ctx.fillText(fitText(ctx, member.name, CW - 170), PAD, y + 14);
      ctx.fillStyle = C.muted;
      ctx.font = `400 13px "${FAMILY}"`;
      ctx.textAlign = 'right';
      ctx.fillText(`${member.role || ''}`, W - PAD - 76, y + 14);
      ctx.fillStyle = C.ink;
      ctx.font = `700 17px "${FAMILY} Bold"`;
      ctx.fillText(`${value.toFixed(1)}h`, W - PAD, y + 14);
      ctx.textAlign = 'left';
      drawBar(ctx, PAD, y + 26, CW, 10, (value / max) * 100, deliveredMode ? C.green : C.amber);
      y += 52;
    }
    y += 26;
  }

  // Delivered this week
  y = sectionTitle(ctx, y, '本周交付', done.length ? `${done.length} 张卡已归档` : '');
  if (!done.length) {
    ctx.fillStyle = C.muted;
    ctx.font = `400 16px "${FAMILY}"`;
    ctx.fillText('本周还没有归档，先把进行中的卡推一推。', PAD, y + 18);
    y += 50;
  } else {
    for (const item of done) {
      ctx.fillStyle = C.greenSoft;
      ctx.beginPath();
      ctx.arc(PAD + 10, y + 12, 10, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = C.green;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(PAD + 6, y + 12);
      ctx.lineTo(PAD + 9, y + 16);
      ctx.lineTo(PAD + 15, y + 8);
      ctx.stroke();
      ctx.fillStyle = C.ink;
      ctx.font = `600 18px "${FAMILY} Bold"`;
      ctx.fillText(fitText(ctx, item.title, CW - 190), PAD + 30, y + 10);
      ctx.fillStyle = C.muted;
      ctx.font = `400 13px "${FAMILY}"`;
      const note = [item.owner, item.remark].filter(Boolean).join(' · ');
      ctx.fillText(fitText(ctx, note, CW - 190), PAD + 30, y + 30);
      ctx.fillStyle = C.ink;
      ctx.font = `700 17px "${FAMILY} Bold"`;
      ctx.textAlign = 'right';
      ctx.fillText(`${item.hours}h`, W - PAD, y + 18);
      ctx.textAlign = 'left';
      y += 50;
    }
  }
  y += 26;

  // In progress
  if (doing.length) {
    y = sectionTitle(ctx, y, '进行中', `${doing.length} 张`);
    for (const item of doing) {
      ctx.fillStyle = C.ink;
      ctx.font = `600 18px "${FAMILY} Bold"`;
      ctx.fillText(fitText(ctx, item.title, CW - 210), PAD, y + 12);
      ctx.fillStyle = C.muted;
      ctx.font = `400 14px "${FAMILY}"`;
      ctx.textAlign = 'right';
      ctx.fillText(`${item.owner} · 剩 ${item.remainingHours.toFixed(1)}h`, W - PAD, y + 12);
      ctx.textAlign = 'left';
      drawBar(ctx, PAD, y + 26, CW - 64, 8, item.progress, C.green);
      ctx.fillStyle = C.green;
      ctx.font = `700 15px "${FAMILY} Bold"`;
      ctx.textAlign = 'right';
      ctx.fillText(`${item.progress}%`, W - PAD, y + 34);
      ctx.textAlign = 'left';
      y += 58;
    }
    y += 14;
  }

  // Risks
  if (risks.length) {
    y = sectionTitle(ctx, y, '风险与临期', `${stats.blockedCount || 0} 个阻塞 · ${stats.dueCount || 0} 个临期`);
    for (const item of risks) {
      ctx.fillStyle = item.kind === 'blocked' ? C.red : C.amber;
      ctx.beginPath();
      ctx.arc(PAD + 5, y + 10, 4, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = C.ink;
      ctx.font = `400 15px "${FAMILY}"`;
      ctx.fillText(fitText(ctx, item.text, CW - 20), PAD + 18, y + 15);
      y += 30;
    }
    y += 22;
  }

  // Footer
  ctx.strokeStyle = C.line;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(PAD, y + 6);
  ctx.lineTo(W - PAD, y + 6);
  ctx.stroke();
  ctx.fillStyle = C.muted;
  ctx.font = `400 13px "${FAMILY}"`;
  ctx.fillText('Repland 团队助手 · 回复「任务」查看完整看板', PAD, y + 36);
  ctx.textAlign = 'right';
  ctx.fillText('和网页 / 安卓共用同一份数据', W - PAD, y + 36);
  ctx.textAlign = 'left';

  return canvas.toBuffer('image/png');
}
