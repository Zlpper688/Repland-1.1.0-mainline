// Proactive reach is scarce in QQ: a DM is a separate, quota-limited message.
// Only high-value unlocks earn one; everything else waits in the shared pending
// queue and is pinned to the top of the member's next conversation.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { queuePending } from './api.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const BUDGET_FILE = process.env.QQ_NOTIFY_BUDGET || join(here, 'data', 'notify.json');
const MONTHLY_LIMIT = Math.max(0, Number(process.env.QQ_PROACTIVE_MONTHLY || 3));
const QUIET_START = 23;
const QUIET_END = 8;
const TZ = 'Asia/Shanghai';

function monthKey() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit' }).format(new Date());
}

function hourNow() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: TZ,
    hour: '2-digit',
    hour12: false,
  }).formatToParts(new Date());
  return Number(parts.find((part) => part.type === 'hour')?.value || 0);
}

function loadBudget() {
  let data = null;
  if (existsSync(BUDGET_FILE)) {
    try {
      data = JSON.parse(readFileSync(BUDGET_FILE, 'utf8'));
    } catch {
      data = null;
    }
  }
  const month = monthKey();
  if (!data || data.month !== month) data = { month, sent: {} };
  if (!data.sent || typeof data.sent !== 'object') data.sent = {};
  return data;
}

function saveBudget(data) {
  mkdirSync(dirname(BUDGET_FILE), { recursive: true });
  writeFileSync(BUDGET_FILE, JSON.stringify(data, null, 2));
}

export async function deliverNotifications(bot, notifications, chat) {
  const queued = [];
  const quiet = hourNow() >= QUIET_START || hourNow() < QUIET_END;
  for (const item of notifications || []) {
    const budget = loadBudget();
    const used = Number(budget.sent[item.memberId] || 0);
    const canProactive = Boolean(item.openid) && item.priority === 'high';
    if (canProactive && used < MONTHLY_LIMIT && !quiet) {
      try {
        await bot.sendProactive({ kind: 'c2c', userOpenid: item.openid }, { markdown: item.text });
        budget.sent[item.memberId] = used + 1;
        saveBudget(budget);
        console.log(`主动私聊 → ${item.memberName}（本月 ${used + 1}/${MONTHLY_LIMIT}）`);
        continue;
      } catch (error) {
        console.log(`主动私聊未送达（${error.message}），改存待通知`);
      }
    } else if (canProactive) {
      const why = quiet ? '免打扰时段' : `本月已发 ${used}/${MONTHLY_LIMIT}`;
      console.log(`主动私聊跳过 ${item.memberName}（${why}），改存待通知`);
    }
    try {
      await queuePending(item.memberId, item.text, item.priority);
      if (item.memberName) queued.push(item.memberName);
    } catch (error) {
      console.log(`待通知写入失败：${error.message}`);
    }
  }
  if (!queued.length) return '';
  if (!chat) return `（${[...new Set(queued)].join('、')} 的通知已排队）`;
  return `（${[...new Set(queued)].join('、')} 的提醒已排队，下次说话会在最上面看到）`;
}
