// Shared team domain: one command engine used by the QQ bot now, and by the
// website / Android later through the same /api/team routes. Pure JS only so it
// runs on the Deno function runtime and in Node tests.

const TZ = 'Asia/Shanghai';
const STATUS = { TODO: '未开始', DOING: '进行中', DONE: '已完成', CANCELLED: '已取消' };
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

export const TEAM_KEY = 'main';

export const DAY_START = 8 * 60;
export const DAY_END = 22 * 60;
const SLOT_STEP = 30;

const NAV_ROW = [
  { label: '我的任务', data: '/我的任务' },
  { label: '工作量', data: '/工作量' },
  { label: '日报', data: '/日报' },
  { label: '周报', data: '/周报' },
];

export const HELP = [
  '## Repland 团队助手',
  '> 不用记格式，像说话一样发就行。',
  '',
  '### 看板',
  '- **任务** 看全部未完成（回数字打开卡片）',
  '- **我的任务** · **工作量** · **日报** · **周报**（会自动配一张图）',
  '',
  '### 动任务卡',
  '- **开始 竞品调研**',
  '- **进度 竞品调研 60%**（只发「进度 竞品调研」自动 +20%）',
  '- **完成 竞品调研 已交初稿**',
  '- **新建 市场调研问卷 | 给 阿橙 | 3小时 | 截止 11-18 | 前置 竞品调研**',
  '',
  '### 绑定',
  '- **我是 小满**（把这个 QQ 绑到队里的人）',
].join('\n');

// ---------- dates ----------

export function todayText(offsetDays = 0) {
  const formatted = new Intl.DateTimeFormat('en-CA', {
    timeZone: TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
  if (!offsetDays) return formatted;
  return addDays(formatted, offsetDays);
}

export function addDays(dateText, days) {
  const date = new Date(`${dateText}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

export function daysBetween(fromText, toText) {
  return Math.round((Date.parse(`${toText}T00:00:00Z`) - Date.parse(`${fromText}T00:00:00Z`)) / 86_400_000);
}

function nowIso() {
  return new Date().toISOString();
}

function nextId(prefix) {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

export function progressBar(percent, cells = 10) {
  const value = Math.max(0, Math.min(100, Number(percent) || 0));
  const filled = Math.round((value / 100) * cells);
  return `${'▰'.repeat(filled)}${'▱'.repeat(cells - filled)}`;
}

// ---------- doc ----------

export function createTeamDoc({ name = '数模赛备赛组', code = '' } = {}) {
  const today = todayText();
  const block = (title, weekday, startHour, endHour) => ({
    id: `${weekday}-${startHour}-${title}`,
    title,
    weekday,
    startMinute: startHour * 60,
    endMinute: endHour * 60,
    kind: 'CLASS',
  });
  // weekday follows Date#getUTCDay (0 = Sunday), matching the planner's fixed blocks.
  const timetables = {
    'm-captain': [block('专业课', 1, 9, 11), block('讨论课', 3, 14, 16), block('实验课', 5, 10, 12)],
    'm-acheng': [block('设计课', 1, 14, 17), block('英语', 2, 9, 12), block('专业课', 4, 10, 12), block('讲座', 5, 14, 16)],
    'm-xiaoman': [block('高数', 1, 9, 12), block('算法', 3, 9, 12), block('实验', 4, 14, 17)],
    'm-linyi': [block('统计', 2, 14, 17), block('调研方法', 3, 10, 12), block('数据分析', 5, 9, 11)],
    'm-zhouzhou': [block('运营课', 1, 10, 12), block('传播学', 4, 9, 12), block('选修', 5, 15, 17)],
  };
  const member = (id, memberName, role) => ({
    id,
    name: memberName,
    role,
    openids: [],
    timetable: timetables[id] || [],
  });
  const task = (id, title, ownerId, hours, deadlineOffset, deps = []) => ({
    id,
    title,
    ownerId,
    hours,
    deadline: addDays(today, deadlineOffset),
    deps,
    status: 'TODO',
    progress: 0,
    createdAt: nowIso(),
    startedAt: null,
    doneAt: null,
    unblockedAt: null,
  });
  return {
    team: { name, code, createdAt: nowIso() },
    members: [
      member('m-captain', '队长', '队长'),
      member('m-acheng', '阿橙', '设计'),
      member('m-xiaoman', '小满', '开发'),
      member('m-linyi', '林一', '调研'),
      member('m-zhouzhou', '周舟', '运营'),
    ],
    tasks: [
      task('t-research', '竞品调研', 'm-linyi', 4, 2),
      task('t-paper', '报名材料初稿', 'm-zhouzhou', 3, 3),
      task('t-tech', '技术方案选型', 'm-xiaoman', 3, 3),
      task('t-survey', '市场调研问卷', 'm-acheng', 3, 4, ['t-research']),
      task('t-visual', '视觉物料', 'm-acheng', 4, 5, ['t-survey']),
      task('t-proto', '原型开发', 'm-xiaoman', 6, 6, ['t-tech']),
      task('t-ppt', '答辩 PPT', 'm-captain', 4, 7, ['t-proto', 't-visual']),
      task('t-rehearsal', '路演彩排', 'm-captain', 2, 7, ['t-ppt']),
    ],
    ledger: [],
    pending: [],
    sessions: {},
    meetings: [],
  };
}

export function normalizeDoc(doc) {
  const base = doc && typeof doc === 'object' ? doc : {};
  return {
    team: { name: '团队', code: '', ...(base.team || {}) },
    members: (Array.isArray(base.members) ? base.members : []).map((member) => ({
      ...member,
      openids: Array.isArray(member.openids) ? member.openids : [],
      timetable: Array.isArray(member.timetable) ? member.timetable : [],
    })),
    tasks: Array.isArray(base.tasks) ? base.tasks : [],
    ledger: Array.isArray(base.ledger) ? base.ledger : [],
    pending: Array.isArray(base.pending) ? base.pending : [],
    sessions: base.sessions && typeof base.sessions === 'object' ? base.sessions : {},
    meetings: Array.isArray(base.meetings) ? base.meetings : [],
  };
}

export function chatKey(chat) {
  if (chat.kind === 'group') return `g:${chat.groupOpenid}:${chat.memberOpenid}`;
  return `c:${chat.userOpenid}`;
}

export function resolveMember(doc, chat) {
  const key = chatKey(chat);
  return doc.members.find((member) => member.openids.includes(key)) || null;
}

function memberById(doc, id) {
  return doc.members.find((member) => member.id === id) || null;
}

function memberName(doc, id) {
  return memberById(doc, id)?.name || '未分配';
}

function memberRole(doc, id) {
  return memberById(doc, id)?.role || '';
}

function taskName(doc, id) {
  return doc.tasks.find((task) => task.id === id)?.title || id;
}

function c2cOpenid(member) {
  const key = member?.openids?.find((item) => item.startsWith('c:'));
  return key ? key.slice(2) : null;
}

function isBlocked(doc, task) {
  return task.deps.some((depId) => doc.tasks.find((dep) => dep.id === depId)?.status !== 'DONE');
}

function blockedBy(doc, task) {
  return task.deps
    .map((depId) => doc.tasks.find((dep) => dep.id === depId))
    .filter((dep) => dep && dep.status !== 'DONE')
    .map((dep) => dep.title);
}

function dependents(doc, task) {
  return doc.tasks.filter((item) => item.deps.includes(task.id) && item.status !== 'CANCELLED');
}

function deadlineText(deadline) {
  if (!deadline) return '无截止';
  const day = WEEKDAYS[new Date(`${deadline}T00:00:00Z`).getUTCDay()];
  const left = daysBetween(todayText(), deadline);
  if (left === 0) return `${deadline.slice(5)} ${day} · **今天截止**`;
  if (left < 0) return `${deadline.slice(5)} ${day} · **已逾期 ${-left} 天**`;
  return `${deadline.slice(5)} ${day} · 还剩 ${left} 天`;
}

function deadlineShort(deadline) {
  if (!deadline) return '无截止';
  const day = WEEKDAYS[new Date(`${deadline}T00:00:00Z`).getUTCDay()];
  const left = daysBetween(todayText(), deadline);
  const distance = left === 0 ? '今天截止' : left < 0 ? `逾期 ${-left} 天` : `剩 ${left} 天`;
  return `${deadline.slice(5)} ${day} · ${distance}`;
}

function progressText(task) {
  if (task.status === 'DONE') return '已完成';
  if (task.status === 'DOING') return `进行中 ${task.progress}%`;
  return STATUS[task.status] || task.status;
}

function remainingHours(task) {
  return task.hours * (1 - task.progress / 100);
}

function clock(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: TZ,
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(date);
  const pick = (type) => parts.find((part) => part.type === type)?.value || '';
  return `${pick('month')}-${pick('day')} ${pick('hour')}:${pick('minute')}`;
}

function activeTasks(doc) {
  return doc.tasks.filter((task) => task.status !== 'DONE' && task.status !== 'CANCELLED');
}

function matchTasks(doc, query) {
  const text = query.trim();
  if (!text) return [];
  const exact = doc.tasks.filter((task) => task.title === text);
  if (exact.length) return exact;
  return doc.tasks.filter((task) => task.title.includes(text) || text.includes(task.title));
}

function pickTask(doc, query) {
  const matches = matchTasks(doc, query);
  if (matches.length === 1) return { task: matches[0] };
  if (matches.length === 0) return { error: `没找到「${query}」这张任务卡。发「任务」看看现在有哪些。` };
  return { error: `「${query}」对得上 ${matches.length} 张卡：${matches.map((task) => task.title).join('、')}。说全名。` };
}

function sessionTasks(doc, chat) {
  const entry = doc.sessions?.[chatKey(chat)];
  if (!entry || !Array.isArray(entry.taskIds)) return [];
  return entry.taskIds.map((id) => doc.tasks.find((task) => task.id === id)).filter(Boolean);
}

function queryTask(doc, chat, query) {
  const direct = pickTask(doc, query);
  if (!direct.error) return direct;
  const numeric = query.trim().match(/^#?(\d{1,2})$/);
  if (!numeric) return direct;
  const task = sessionTasks(doc, chat)[Number(numeric[1]) - 1];
  return task ? { task } : direct;
}

function parseDeadline(raw) {
  if (!raw) return null;
  const match = raw.match(/^(\d{1,2})[-/.月](\d{1,2})日?$/);
  if (!match) return null;
  const month = Number(match[1]);
  const day = Number(match[2]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  let year = Number(todayText().slice(0, 4));
  let text = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
  if (daysBetween(todayText(), text) < -30) {
    year += 1;
    text = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
  }
  return text;
}

function note(doc, task, actorId, type, text) {
  doc.ledger.push({
    id: nextId('led'),
    at: nowIso(),
    date: todayText(),
    taskId: task.id,
    actorId,
    type,
    text,
  });
}

// ---------- rendering ----------

function taskBlock(doc, task, index) {
  const number = index == null ? '' : `${index}. `;
  const lines = [`**${number}${task.title}**　${progressText(task)}`];
  const meta = [];
  if (task.status === 'DONE') {
    meta.push(memberName(doc, task.ownerId), `归档 ${clock(task.doneAt).slice(0, 5) || '—'}`, `${task.hours}h`);
  } else {
    meta.push(memberName(doc, task.ownerId), deadlineShort(task.deadline), `${task.hours}h`);
  }
  lines.push(meta.join(' · '));
  if (task.status === 'DOING') lines.push(`${progressBar(task.progress)} ${task.progress}%`);
  if (task.status !== 'DONE' && isBlocked(doc, task)) {
    lines.push(`等「${blockedBy(doc, task).join('、')}」`);
  }
  return lines.join('\n');
}

function taskListReply(doc, tasks, title) {
  const open = tasks.filter((task) => task.status !== 'DONE' && task.status !== 'CANCELLED');
  const done = tasks.filter((task) => task.status === 'DONE');
  const doing = open.filter((task) => task.status === 'DOING');
  const ready = open.filter((task) => task.status !== 'DOING' && !isBlocked(doc, task));
  const blocked = open.filter((task) => task.status !== 'DOING' && isBlocked(doc, task));
  const hours = open.reduce((sum, task) => sum + remainingHours(task), 0);
  const ordered = [...doing, ...ready, ...blocked];
  const taskIds = ordered.map((task) => task.id);

  if (!tasks.length) {
    return { reply: '现在没有任务卡。发「新建 任务名 | 给 某人」建一张。', buttons: [NAV_ROW], taskIds };
  }

  const lines = [`## ${title} · ${open.length} 张`];
  const summary = [`进行中 ${doing.length}`, `可开始 ${ready.length}`, `被挡住 ${blocked.length}`, `剩 ${hours.toFixed(1)}h`];
  if (done.length) summary.push(`已归档 ${done.length}`);
  lines.push(`> ${summary.join(' · ')}`, '');

  const sections = [
    ['进行中', doing],
    ['可以开始', ready],
    ['被前置挡住', blocked],
  ];
  let index = 1;
  for (const [label, group] of sections) {
    if (!group.length) continue;
    lines.push(`### ${label}`, '');
    for (const task of group) {
      lines.push(taskBlock(doc, task, index), '');
      index += 1;
    }
  }

  if (done.length) {
    lines.push('### 已归档', '');
    for (const task of done) {
      lines.push(`- ${task.title} · ${memberName(doc, task.ownerId)} · ${clock(task.doneAt).slice(0, 5) || '—'}`);
    }
    lines.push('');
  }

  lines.push('---', '回数字打开任务卡（如 2），也可以直接说「开始 竞品调研」。');
  return { reply: lines.join('\n'), buttons: [NAV_ROW], taskIds };
}

function taskCardReply(doc, task) {
  const role = memberRole(doc, task.ownerId);
  const owner = `${memberName(doc, task.ownerId)}${role ? `（${role}）` : ''}`;
  const lines = [`## ${task.title}`, `> ${doc.team.name} · ${task.hours}h`, ''];
  lines.push(`**状态**　${progressText(task)}`);
  lines.push(`**负责**　${owner}`);
  lines.push(`**截止**　${deadlineText(task.deadline)}`);
  if (task.status === 'DOING') {
    lines.push(`**进度**　${progressBar(task.progress)} ${task.progress}%`);
  } else if (task.status === 'DONE') {
    lines.push(`**归档**　${clock(task.doneAt) || '—'}`);
  } else {
    lines.push(`**进度**　${progressBar(0)} 0%`);
  }
  const deps = task.deps.map((id) => doc.tasks.find((dep) => dep.id === id)).filter(Boolean);
  lines.push(`**前置**　${deps.length ? deps.map((dep) => `${dep.title}${dep.status === 'DONE' ? '（已完成）' : ''}`).join('、') : '无'}`);
  const downs = dependents(doc, task);
  if (downs.length) {
    lines.push(`**下游**　${downs.map((down) => `${down.title}（${memberName(doc, down.ownerId)}）`).join('、')}`);
  }

  const events = doc.ledger.filter((entry) => entry.taskId === task.id).slice(-3);
  if (events.length) {
    lines.push('', '### 最近动态');
    for (const entry of events) {
      const who = entry.actorId ? `${memberName(doc, entry.actorId)} · ` : '';
      lines.push(`- ${clock(entry.at)} ${who}${entry.text}`);
    }
  }

  lines.push('', '---');
  if (task.status === 'DONE') {
    lines.push('这张卡已经归档，记录留在周报里。');
  } else if (isBlocked(doc, task)) {
    lines.push(`还等「${blockedBy(doc, task).join('、')}」，对方完成后我会来提醒。`);
  } else if (task.status === 'DOING') {
    lines.push(`回「进度 ${task.title} 75%」或「完成 ${task.title} 备注」；也可以直接点下面的按钮。`);
  } else {
    lines.push(`回「开始 ${task.title}」记录开工；也可以直接点下面的按钮。`);
  }

  const buttons = [];
  if (task.status === 'DOING') {
    buttons.push([
      { label: '进度+20%', data: `/进度 ${task.title} +20%` },
      { label: '完成', data: `/完成 ${task.title}`, style: 3 },
    ]);
  } else if (task.status !== 'DONE' && !isBlocked(doc, task)) {
    buttons.push([
      { label: '开始', data: `/开始 ${task.title}`, style: 3 },
      { label: '完成', data: `/完成 ${task.title}` },
    ]);
  }
  buttons.push(NAV_ROW);
  return { reply: lines.join('\n'), buttons, taskIds: [task.id] };
}

function createTask(doc, actor, args) {
  const parts = args.split(/[|｜]/).map((part) => part.trim()).filter(Boolean);
  const title = parts.shift()?.replace(/^新建\s*/, '').trim();
  if (!title) return { reply: '要建什么任务？例如：新建 市场调研问卷 | 给 阿橙 | 3小时 | 截止 11-18' };
  let owner = actor;
  let hours = 3;
  let deadline = addDays(todayText(), 3);
  const deps = [];
  for (const part of parts) {
    const ownerMatch = part.match(/^给\s*(\S+)$/);
    const hoursMatch = part.match(/^(\d+(?:\.\d+)?)\s*(?:小时|h|H)$/);
    const deadlineMatch = part.match(/^截止\s*(.+)$/);
    const depsMatch = part.match(/^前置\s*(.+)$/);
    if (ownerMatch) {
      const target = doc.members.find((member) => member.name === ownerMatch[1]);
      if (!target) return { reply: `队里没有「${ownerMatch[1]}」，现有：${doc.members.map((m) => m.name).join('、')}` };
      owner = target.id;
      continue;
    }
    if (hoursMatch) {
      hours = Number(hoursMatch[1]);
      continue;
    }
    if (deadlineMatch) {
      const parsed = parseDeadline(deadlineMatch[1].trim());
      if (!parsed) return { reply: '截止日写成 11-18 或 11月18日 这样。' };
      deadline = parsed;
      continue;
    }
    if (depsMatch) {
      for (const raw of depsMatch[1].split(/[、,，]/).map((item) => item.trim()).filter(Boolean)) {
        const picked = pickTask(doc, raw);
        if (picked.error) return { reply: picked.error };
        deps.push(picked.task.id);
      }
      continue;
    }
    return { reply: `没看懂「${part}」。可用：给 阿橙 / 3小时 / 截止 11-18 / 前置 竞品调研` };
  }
  const task = {
    id: nextId('t'),
    title,
    ownerId: owner,
    hours,
    deadline,
    deps,
    status: 'TODO',
    progress: 0,
    createdAt: nowIso(),
    startedAt: null,
    doneAt: null,
    unblockedAt: null,
  };
  doc.tasks.push(task);
  note(doc, task, actor, 'create', `创建「${title}」`);
  const lines = [
    `## 建好了：${title}`,
    `**负责**　${memberName(doc, owner)}`,
    `**截止**　${deadlineText(deadline)}`,
    `**预估**　${hours}h`,
    `**前置**　${deps.length ? deps.map((id) => taskName(doc, id)).join('、') : '无'}`,
  ];
  if (deps.length) lines.push('', '前置完成后会私聊提醒负责人。');
  return { reply: lines.join('\n'), buttons: [[NAV_ROW[0], NAV_ROW[2]]] };
}

function completeTask(doc, task, actor, remark) {
  if (task.status === 'DONE') return { reply: `「${task.title}」本来就是完成状态。` };
  task.status = 'DONE';
  task.progress = 100;
  task.doneAt = nowIso();
  note(doc, task, actor, 'done', remark ? `完成：${remark}` : '完成');
  const unlocked = [];
  for (const candidate of doc.tasks) {
    if (candidate.status !== 'TODO' || !candidate.deps.includes(task.id)) continue;
    if (isBlocked(doc, candidate)) continue;
    candidate.unblockedAt = nowIso();
    unlocked.push(candidate);
    note(doc, candidate, actor, 'unlock', `前置「${task.title}」完成，可开始`);
  }
  const lines = [
    '## 归档完成',
    `**${task.title}**${remark ? `（${remark}）` : ''}`,
    `> ${memberName(doc, actor)} · ${task.hours}h · 已计入周报`,
  ];
  const notifications = unlocked.map((candidate) => ({
    memberId: candidate.ownerId,
    memberName: memberName(doc, candidate.ownerId),
    openid: c2cOpenid(memberById(doc, candidate.ownerId)),
    priority: 'high',
    text: [
      '## 可以开始了',
      `**${candidate.title}** 的前置「${task.title}」已完成。`,
      `> ${memberName(doc, candidate.ownerId)} · ${deadlineText(candidate.deadline)} · ${candidate.hours}h`,
      '',
      `回「开始 ${candidate.title}」记进度。`,
    ].join('\n'),
  }));
  if (unlocked.length) {
    lines.push('', `### 解锁了 ${unlocked.length} 张`);
    for (const candidate of unlocked) {
      lines.push(`- **${candidate.title}** · ${memberName(doc, candidate.ownerId)} · ${deadlineShort(candidate.deadline)}`);
    }
  }
  return { reply: lines.join('\n'), notifications, buttons: [NAV_ROW] };
}

function startTask(doc, task, actor) {
  if (task.status === 'DONE') return { reply: `「${task.title}」已经完成了。` };
  if (isBlocked(doc, task)) {
    return { reply: `「${task.title}」还等 ${blockedBy(doc, task).join('、')}，先别开始。对方做完我会提醒你。` };
  }
  if (task.status === 'DOING') {
    return { reply: `「${task.title}」已经在进行中（${task.progress}%）。发「进度 ${task.title} 60%」更新。` };
  }
  task.status = 'DOING';
  task.startedAt = nowIso();
  note(doc, task, actor, 'start', '开始');
  return {
    reply: [
      `## 开始「${task.title}」`,
      `> ${memberName(doc, actor)} · ${task.hours}h · ${deadlineText(task.deadline)}`,
      '',
      `${progressBar(0)} 0%`,
      `有进展发「进度 ${task.title} 50%」，做完发「完成 ${task.title}」。`,
    ].join('\n'),
    buttons: [
      [
        { label: '进度+20%', data: `/进度 ${task.title} +20%` },
        { label: '完成', data: `/完成 ${task.title}`, style: 3 },
      ],
      NAV_ROW,
    ],
  };
}

function updateProgress(doc, task, actor, percent, { auto = false } = {}) {
  if (task.status === 'DONE') return { reply: `「${task.title}」已经完成了。` };
  if (isBlocked(doc, task)) return { reply: `「${task.title}」还等 ${blockedBy(doc, task).join('、')}，现在还不能报进度。` };
  task.status = 'DOING';
  task.progress = percent;
  if (!task.startedAt) task.startedAt = nowIso();
  if (percent >= 100) return completeTask(doc, task, actor, '进度到 100%');
  note(doc, task, actor, 'progress', `进度 ${percent}%`);
  return {
    reply: [
      `**${task.title}** 记到 **${percent}%**${auto ? '（自动 +20%）' : ''}`,
      `${progressBar(percent)} ${percent}%`,
      `> ${memberName(doc, actor)} · ${deadlineShort(task.deadline)} · 剩 ${remainingHours(task).toFixed(1)}h`,
    ].join('\n'),
    buttons: [
      [
        { label: '进度+20%', data: `/进度 ${task.title} +20%` },
        { label: '完成', data: `/完成 ${task.title}`, style: 3 },
      ],
    ],
  };
}

function myTasksReply(doc, member) {
  const tasks = activeTasks(doc).filter((task) => task.ownerId === member.id);
  if (!tasks.length) {
    return {
      reply: `## 我的任务 · ${member.name}\n> 现在没有未完成的卡，可以问队长要一张。`,
      buttons: [NAV_ROW],
      taskIds: [],
    };
  }
  return taskListReply(doc, tasks, `我的任务 · ${member.name}`);
}

function loadReply(doc) {
  const active = activeTasks(doc);
  const byMember = new Map();
  for (const task of active) {
    const entry = byMember.get(task.ownerId) || { hours: 0, count: 0 };
    entry.hours += remainingHours(task);
    entry.count += 1;
    byMember.set(task.ownerId, entry);
  }
  if (!byMember.size) return { reply: '现在没有未完成的任务卡。', buttons: [NAV_ROW] };
  const rows = [...byMember.entries()].sort((a, b) => b[1].hours - a[1].hours);
  const max = Math.max(...rows.map(([, entry]) => entry.hours), 1);
  const total = rows.reduce((sum, [, entry]) => sum + entry.hours, 0);
  const lines = [
    `## 工作量 · 剩 ${total.toFixed(1)}h`,
    '> 未完成任务按剩余工时折算',
    '',
  ];
  for (const [id, entry] of rows) {
    const percent = Math.round((entry.hours / max) * 100);
    lines.push(`**${memberName(doc, id)}**　${entry.hours.toFixed(1)}h · ${entry.count} 张`);
    lines.push(`${progressBar(percent)} ${percent}%`);
    lines.push('');
  }
  lines.push('---', '发「我的任务」看自己的卡。');
  return { reply: lines.join('\n'), buttons: [NAV_ROW] };
}

function dailyReport(doc, member) {
  const today = todayText();
  const events = doc.ledger.filter((entry) => entry.date === today);
  const done = events.filter((entry) => entry.type === 'done');
  const progress = events.filter((entry) => entry.type === 'progress');
  const unlocked = events.filter((entry) => entry.type === 'unlock');
  const doing = activeTasks(doc).filter((task) => task.status === 'DOING');
  const blocked = activeTasks(doc).filter((task) => isBlocked(doc, task));
  const due = activeTasks(doc)
    .filter((task) => task.deadline && daysBetween(today, task.deadline) <= 2)
    .sort((a, b) => a.deadline.localeCompare(b.deadline));

  const lines = [
    `## 今日日报 · ${today.slice(5)}`,
    `> ${member ? member.name : '全队'}视角 · 今天 ${events.length} 条动态`,
  ];

  if (!events.length && !doing.length && !blocked.length && !due.length) {
    lines.push('', '今天还没有动静。发「任务」挑一张卡开工。');
    return { reply: lines.join('\n'), buttons: [NAV_ROW] };
  }

  lines.push('', '### 已完成', '');
  if (done.length) {
    for (const entry of done) {
      lines.push(`- **${taskName(doc, entry.taskId)}** · ${memberName(doc, entry.actorId)}`);
    }
  } else {
    lines.push('- 今天还没有归档');
  }

  lines.push('', `### 进行中 ${doing.length}`, '');
  if (doing.length) {
    for (const task of doing) {
      lines.push(`- **${task.title}** ${task.progress}%（${memberName(doc, task.ownerId)}）`);
      lines.push(`  ${progressBar(task.progress)}`);
    }
  } else {
    lines.push('- 没有进行中的卡');
  }

  if (progress.length) {
    lines.push('', '### 今日进度');
    for (const entry of progress) {
      lines.push(`- ${taskName(doc, entry.taskId)} · ${entry.text}（${memberName(doc, entry.actorId)}）`);
    }
  }

  if (unlocked.length) {
    lines.push('', '### 新可开始');
    for (const entry of unlocked) {
      lines.push(`- ${taskName(doc, entry.taskId)}`);
    }
  }

  if (blocked.length) {
    lines.push('', `### 被挡住 ${blocked.length}`);
    for (const task of blocked) {
      lines.push(`- ${task.title} · 等「${blockedBy(doc, task).join('、')}」`);
    }
  }

  if (due.length) {
    lines.push('', '### 近两天截止');
    for (const task of due) {
      lines.push(`- ${task.title} · ${deadlineShort(task.deadline)} · ${memberName(doc, task.ownerId)}`);
    }
  }

  return { reply: lines.join('\n'), buttons: [NAV_ROW] };
}

function weeklyReport(doc) {
  const today = todayText();
  const day = new Date(`${today}T00:00:00Z`).getUTCDay() || 7;
  const monday = addDays(today, 1 - day);
  const events = doc.ledger.filter((entry) => entry.date >= monday && entry.date <= today);
  const done = events.filter((entry) => entry.type === 'done');
  const perMember = new Map();
  let totalHours = 0;
  for (const task of doc.tasks.filter((item) => item.status === 'DONE' && item.doneAt && item.doneAt.slice(0, 10) >= monday)) {
    perMember.set(task.ownerId, (perMember.get(task.ownerId) || 0) + task.hours);
    totalHours += task.hours;
  }
  const active = activeTasks(doc);
  const blocked = active.filter((task) => isBlocked(doc, task));
  const due = active
    .filter((task) => task.deadline && daysBetween(today, task.deadline) <= 2)
    .sort((a, b) => a.deadline.localeCompare(b.deadline));

  const lines = [
    `## 本周周报 · ${monday.slice(5)} 起`,
    `> 归档 ${done.length} 张 · 交付 ${totalHours}h · 未完成 ${active.length} 张`,
  ];

  if (done.length) {
    lines.push('', '### 本周交付');
    for (const entry of done) {
      const task = doc.tasks.find((item) => item.id === entry.taskId);
      const remark = entry.text.startsWith('完成：') ? entry.text.slice(3) : '';
      lines.push(`- **${taskName(doc, entry.taskId)}** · ${memberName(doc, entry.actorId)}${remark ? ` · ${remark}` : ''}`);
      if (task) lines.push(`  ${task.hours}h`);
    }
  } else {
    lines.push('', '### 本周交付', '', '- 本周还没有归档');
  }

  if (perMember.size) {
    lines.push('', '### 成员交付');
    for (const [id, hours] of [...perMember].sort((a, b) => b[1] - a[1])) {
      lines.push(`- ${memberName(doc, id)} · ${hours}h`);
    }
  }

  if (blocked.length) {
    lines.push('', `### 风险 ${blocked.length}`);
    for (const task of blocked) {
      lines.push(`- ${task.title} · 等「${blockedBy(doc, task).join('、')}」`);
    }
  }

  if (due.length) {
    lines.push('', '### 近两天截止');
    for (const task of due) {
      lines.push(`- ${task.title} · ${deadlineShort(task.deadline)} · ${memberName(doc, task.ownerId)}`);
    }
  }

  return { reply: lines.join('\n'), buttons: [NAV_ROW] };
}

function bindMember(doc, member, chat) {
  const key = chatKey(chat);
  for (const other of doc.members) other.openids = other.openids.filter((item) => item !== key);
  member.openids.push(key);
  return {
    reply: [
      '## 绑定成功',
      `> 这个 QQ 现在就是 **${member.name}**。`,
      '',
      '发「我的任务」看看，回数字也能直接打开任务卡。',
    ].join('\n'),
    buttons: [NAV_ROW],
  };
}

// ---------- shared read models ----------

export function teamBoard(doc) {
  return {
    team: { name: doc.team.name },
    members: doc.members.map((member) => ({
      id: member.id,
      name: member.name,
      role: member.role,
      bound: Boolean(c2cOpenid(member)),
    })),
    tasks: doc.tasks,
    ledger: doc.ledger.slice(-60),
    pending: doc.pending.length,
    today: todayText(),
  };
}

export function weeklyReportData(doc) {
  const today = todayText();
  const day = new Date(`${today}T00:00:00Z`).getUTCDay() || 7;
  const monday = addDays(today, 1 - day);
  const active = activeTasks(doc);
  const doneTasks = doc.tasks.filter((task) => task.status === 'DONE' && task.doneAt && task.doneAt.slice(0, 10) >= monday);
  const blocked = active.filter((task) => isBlocked(doc, task));
  const due = active
    .filter((task) => task.deadline && daysBetween(today, task.deadline) <= 2)
    .sort((a, b) => a.deadline.localeCompare(b.deadline));
  const doing = active.filter((task) => task.status === 'DOING');

  const doneHoursByMember = new Map();
  for (const task of doneTasks) {
    doneHoursByMember.set(task.ownerId, (doneHoursByMember.get(task.ownerId) || 0) + task.hours);
  }
  const remainingByMember = new Map();
  const activeCountByMember = new Map();
  for (const task of active) {
    remainingByMember.set(task.ownerId, (remainingByMember.get(task.ownerId) || 0) + remainingHours(task));
    activeCountByMember.set(task.ownerId, (activeCountByMember.get(task.ownerId) || 0) + 1);
  }

  const remarkOf = (taskId) => {
    const entries = doc.ledger.filter((entry) => entry.taskId === taskId && entry.type === 'done');
    const last = entries[entries.length - 1];
    return last && last.text.startsWith('完成：') ? last.text.slice(3) : '';
  };

  return {
    teamName: doc.team.name,
    generatedAt: nowIso(),
    range: { from: monday, to: today, label: `${monday.slice(5)} – ${today.slice(5)}` },
    stats: {
      doneCount: doneTasks.length,
      deliveredHours: doneTasks.reduce((sum, task) => sum + task.hours, 0),
      activeCount: active.length,
      doingCount: doing.length,
      blockedCount: blocked.length,
      dueCount: due.length,
      remainingHours: active.reduce((sum, task) => sum + remainingHours(task), 0),
    },
    members: doc.members
      .map((member) => ({
        name: member.name,
        role: member.role,
        doneHours: doneHoursByMember.get(member.id) || 0,
        remainingHours: remainingByMember.get(member.id) || 0,
        activeCount: activeCountByMember.get(member.id) || 0,
      }))
      .sort((a, b) => b.doneHours - a.doneHours || b.remainingHours - a.remainingHours),
    done: doneTasks
      .sort((a, b) => String(b.doneAt).localeCompare(String(a.doneAt)))
      .map((task) => ({
        title: task.title,
        owner: memberName(doc, task.ownerId),
        hours: task.hours,
        remark: remarkOf(task.id),
      })),
    doing: doing
      .sort((a, b) => b.progress - a.progress)
      .map((task) => ({
        title: task.title,
        owner: memberName(doc, task.ownerId),
        progress: task.progress,
        remainingHours: remainingHours(task),
        deadline: deadlineShort(task.deadline),
      })),
    blocked: blocked.map((task) => ({
      title: task.title,
      owner: memberName(doc, task.ownerId),
      waits: blockedBy(doc, task),
    })),
    due: due.map((task) => ({
      title: task.title,
      owner: memberName(doc, task.ownerId),
      deadline: deadlineShort(task.deadline),
      leftDays: daysBetween(today, task.deadline),
    })),
  };
}

// ---------- availability ----------

const WEEKDAY_LABEL = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

function weekdayOf(dateText) {
  return new Date(`${dateText}T00:00:00Z`).getUTCDay();
}

function minuteNow() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: TZ,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date());
  const pick = (type) => Number(parts.find((part) => part.type === type)?.value || 0);
  return pick('hour') * 60 + pick('minute');
}

function mergeBusy(list) {
  const sorted = list
    .map((entry) => ({ ...entry, startMinute: Math.max(DAY_START, entry.startMinute), endMinute: Math.min(DAY_END, entry.endMinute) }))
    .filter((entry) => entry.endMinute > entry.startMinute)
    .sort((a, b) => a.startMinute - b.startMinute);
  const merged = [];
  for (const entry of sorted) {
    const last = merged[merged.length - 1];
    if (last && entry.startMinute <= last.endMinute && entry.kind === last.kind) {
      last.endMinute = Math.max(last.endMinute, entry.endMinute);
      last.title = last.title === entry.title ? last.title : `${last.title} / ${entry.title}`;
    } else {
      merged.push({ ...entry });
    }
  }
  return merged;
}

/** Everything that blocks a member on one date: their weekly timetable plus meetings. */
export function memberBusyOn(doc, member, date) {
  if (!member) return [];
  const weekday = weekdayOf(date);
  const busy = (member.timetable || [])
    .filter((entry) => Number(entry.weekday) === weekday)
    .map((entry) => ({ startMinute: Number(entry.startMinute), endMinute: Number(entry.endMinute), title: entry.title || '课程', kind: 'class' }));
  for (const meeting of doc.meetings || []) {
    if (meeting.date !== date || !meeting.memberIds?.includes(member.id)) continue;
    busy.push({ startMinute: meeting.startMinute, endMinute: meeting.endMinute, title: meeting.title, kind: 'meeting' });
  }
  return mergeBusy(busy);
}

export function freeGaps(busy) {
  const gaps = [];
  let cursor = DAY_START;
  for (const entry of busy) {
    if (entry.startMinute - cursor >= SLOT_STEP) gaps.push({ startMinute: cursor, endMinute: entry.startMinute });
    cursor = Math.max(cursor, entry.endMinute);
  }
  if (DAY_END - cursor >= SLOT_STEP) gaps.push({ startMinute: cursor, endMinute: DAY_END });
  return gaps;
}

export function memberFreeOn(doc, member, date) {
  return freeGaps(memberBusyOn(doc, member, date));
}

function intersectWindows(left, right) {
  const out = [];
  for (const a of left) {
    for (const b of right) {
      const startMinute = Math.max(a.startMinute, b.startMinute);
      const endMinute = Math.min(a.endMinute, b.endMinute);
      if (endMinute - startMinute >= SLOT_STEP) out.push({ startMinute, endMinute });
    }
  }
  return out.sort((a, b) => a.startMinute - b.startMinute);
}

/** Free windows shared by every selected member on a date. */
export function commonFreeWindows(doc, memberIds, date) {
  const members = memberIds.map((id) => doc.members.find((member) => member.id === id)).filter(Boolean);
  if (!members.length) return [];
  let windows = memberFreeOn(doc, members[0], date);
  for (const member of members.slice(1)) windows = intersectWindows(windows, memberFreeOn(doc, member, date));
  return windows;
}

function snapStart(minute) {
  return Math.ceil(minute / SLOT_STEP) * SLOT_STEP;
}

/** 9–12 and 14–18 are the slots people actually accept; lunch and late night score lower. */
function slotScore(startMinute) {
  const hour = startMinute / 60;
  if (hour >= 14 && hour < 18) return 3;
  if (hour >= 9 && hour < 12) return 2;
  if (hour >= 18 && hour < 21) return 1;
  if (hour >= 12 && hour < 14) return 0;
  return 1;
}

export function suggestMeetingSlots(doc, { memberIds, duration, days = 8, limit = 8 }) {
  const today = todayText();
  const now = minuteNow();
  const picked = [];
  for (let offset = 0; offset < days; offset += 1) {
    const date = addDays(today, offset);
    const floor = offset === 0 ? snapStart(now + SLOT_STEP) : DAY_START;
    const proposals = [];
    for (const window of commonFreeWindows(doc, memberIds, date)) {
      const startMinute = Math.max(window.startMinute, floor);
      if (window.endMinute - startMinute < duration) continue;
      proposals.push({
        date,
        weekdayLabel: WEEKDAY_LABEL[weekdayOf(date)],
        startMinute,
        endMinute: startMinute + duration,
        freeMinutes: window.endMinute - window.startMinute,
        score: slotScore(startMinute),
      });
    }
    proposals.sort((a, b) => b.freeMinutes - a.freeMinutes || b.score - a.score || a.startMinute - b.startMinute);
    picked.push(...proposals.slice(0, 2));
  }
  return picked
    .sort((a, b) => a.date.localeCompare(b.date) || b.score - a.score || a.startMinute - b.startMinute)
    .slice(0, limit);
}

export function availabilityView(doc, { memberIds, duration = 60, days = 8 } = {}) {
  const today = todayText();
  const ids = memberIds?.length ? memberIds : doc.members.map((member) => member.id);
  const members = ids.map((id) => doc.members.find((member) => member.id === id)).filter(Boolean);
  const empty = members.length === 0;
  const axis = [];
  for (let offset = 0; offset < days; offset += 1) {
    const date = addDays(today, offset);
    axis.push({
      date,
      weekdayLabel: WEEKDAY_LABEL[weekdayOf(date)],
      monthDay: date.slice(5),
      windows: empty ? [] : commonFreeWindows(doc, members.map((member) => member.id), date),
      members: members.map((member) => ({ id: member.id, busy: memberBusyOn(doc, member, date) })),
    });
  }
  return {
    teamName: doc.team.name,
    today,
    duration,
    members: doc.members.map((member) => ({
      id: member.id,
      name: member.name,
      role: member.role,
      bound: Boolean(c2cOpenid(member)),
      classHours: (member.timetable || []).reduce((sum, entry) => sum + Math.max(0, entry.endMinute - entry.startMinute), 0) / 60,
      timetable: member.timetable || [],
    })),
    days: axis,
    suggestions: empty ? [] : suggestMeetingSlots(doc, { memberIds: members.map((member) => member.id), duration, days, limit: 8 }),
    meetings: (doc.meetings || [])
      .filter((meeting) => meeting.date >= today)
      .sort((a, b) => a.date.localeCompare(b.date) || a.startMinute - b.startMinute)
      .slice(0, 12)
      .map((meeting) => ({
        ...meeting,
        memberNames: meeting.memberIds.map((id) => memberName(doc, id)),
      })),
  };
}

export function planMeeting(doc, input) {
  const title = String(input?.title || '').trim().slice(0, 40) || '团队会议';
  const date = String(input?.date || '');
  const startMinute = Number(input?.startMinute);
  const endMinute = Number(input?.endMinute);
  const memberIds = [...new Set((Array.isArray(input?.memberIds) ? input.memberIds : []).filter(Boolean))];
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return { error: '日期不对' };
  if (!Number.isFinite(startMinute) || !Number.isFinite(endMinute)) return { error: '时间不对' };
  if (startMinute < DAY_START || endMinute > DAY_END || endMinute - startMinute < SLOT_STEP) return { error: '会议要在 08:00–22:00 之间，至少 30 分钟' };
  if (!memberIds.length) return { error: '至少要选一个人' };
  const unknown = memberIds.filter((id) => !doc.members.some((member) => member.id === id));
  if (unknown.length) return { error: '有人不在队里' };
  const conflicts = [];
  for (const id of memberIds) {
    const member = doc.members.find((item) => item.id === id);
    const overlap = memberBusyOn(doc, member, date).find((entry) => entry.startMinute < endMinute && entry.endMinute > startMinute);
    if (overlap) conflicts.push({ name: member.name, title: overlap.title });
  }
  if (conflicts.length) {
    return { error: `${conflicts.map((item) => `${item.name} ${item.title ? `有「${item.title}」` : '已有安排'}`).join('、')}` };
  }
  const meeting = {
    id: nextId('mt'),
    title,
    date,
    startMinute,
    endMinute,
    memberIds,
    note: String(input?.note || '').slice(0, 200),
    createdAt: nowIso(),
  };
  doc.meetings.push(meeting);
  return { meeting };
}

export function removeMeeting(doc, id) {
  const before = doc.meetings.length;
  doc.meetings = doc.meetings.filter((meeting) => meeting.id !== id);
  return doc.meetings.length < before;
}

export function replaceTimetable(doc, memberId, entries) {
  const member = doc.members.find((item) => item.id === memberId);
  if (!member) return { error: '没找到这个成员' };
  const clean = (Array.isArray(entries) ? entries : [])
    .map((entry) => ({
      id: String(entry?.id || nextId('tb')),
      title: String(entry?.title || '课程').trim().slice(0, 30),
      weekday: Number(entry?.weekday),
      startMinute: Number(entry?.startMinute),
      endMinute: Number(entry?.endMinute),
      kind: entry?.kind === 'FIXED' ? 'FIXED' : 'CLASS',
    }))
    .filter(
      (entry) =>
        Number.isInteger(entry.weekday) &&
        entry.weekday >= 0 &&
        entry.weekday <= 6 &&
        Number.isFinite(entry.startMinute) &&
        Number.isFinite(entry.endMinute) &&
        entry.startMinute >= 0 &&
        entry.endMinute <= 24 * 60 &&
        entry.endMinute > entry.startMinute,
    )
    .sort((a, b) => a.weekday - b.weekday || a.startMinute - b.startMinute);
  member.timetable = clean;
  return { timetable: clean };
}

// ---------- pending ----------

export function drainPending(doc, memberId) {
  if (!memberId) return [];
  const mine = doc.pending.filter((item) => item.memberId === memberId);
  if (!mine.length) return [];
  doc.pending = doc.pending.filter((item) => item.memberId !== memberId);
  return mine.map((item) => item.text);
}

export function queuePending(doc, memberId, text, priority = 'normal') {
  if (!memberId || !text) return false;
  doc.pending.push({ memberId, text: String(text).slice(0, 1200), priority, at: nowIso() });
  return true;
}

// ---------- command entry ----------

export function handleTeamCommand(doc, chat, rawText) {
  if (!doc.sessions) doc.sessions = {};
  const actor = resolveMember(doc, chat);
  const pending = drainPending(doc, actor?.id);
  const text = String(rawText || '')
    .replace(/<@!?[^>]+>/g, ' ')
    .replace(/^@\S+\s+/, '')
    .replace(/^[@／/]+/, '')
    .replace(/\u3000/g, ' ')
    .trim();
  if (!text) return { reply: HELP, buttons: [NAV_ROW], notifications: [], pending };
  const notifications = [];
  let result = null;
  let image = null;

  if (/^(帮助|help|菜单|能做什么)$/i.test(text)) {
    result = { reply: HELP, buttons: [NAV_ROW] };
  } else if (/^(任务|任务卡|清单)$/.test(text)) {
    result = taskListReply(doc, activeTasks(doc), '未完成任务');
  } else if (text === '全部任务') {
    result = taskListReply(doc, doc.tasks, '全部任务');
  } else if (/^(我的任务|我的|我)$/.test(text)) {
    result = actor
      ? myTasksReply(doc, actor)
      : { reply: '还不知道你是队里的谁。先发「我是 小满」绑定一下。', buttons: [NAV_ROW] };
  } else if (/^(负载|工作量)$/.test(text)) {
    result = loadReply(doc);
  } else if (/^(日报|今天)$/.test(text)) {
    result = dailyReport(doc, actor);
  } else if (/^(周报|这周)$/.test(text)) {
    result = weeklyReport(doc);
    image = { kind: 'weekly', report: weeklyReportData(doc) };
  } else if (/^(周报图|图片周报)$/.test(text)) {
    const report = weeklyReportData(doc);
    result = {
      reply: [
        `## 本周周报 · ${report.range.label}`,
        `> 归档 ${report.stats.doneCount} 张 · 交付 ${report.stats.deliveredHours}h · 风险 ${report.stats.blockedCount} · 近两天截止 ${report.stats.dueCount}`,
        '',
        '图在后面，细节发「周报 文字」看纯文字版。',
      ].join('\n'),
      buttons: [NAV_ROW],
    };
    image = { kind: 'weekly', report };
  } else if (text === '周报 文字') {
    result = weeklyReport(doc);
  } else if (/^我是\s*(\S+)$/.test(text)) {
    const name = text.match(/^我是\s*(\S+)$/)[1];
    const member = doc.members.find((item) => item.name === name);
    result = member
      ? bindMember(doc, member, chat)
      : { reply: `队里没有「${name}」，现有：${doc.members.map((m) => m.name).join('、')}` };
  } else if (/^#?\d{1,2}$/.test(text)) {
    const task = sessionTasks(doc, chat)[Number(text.replace('#', '')) - 1];
    result = task
      ? taskCardReply(doc, task)
      : { reply: '这个数字对不上之前的任务列表，发「任务」重新看一下。', buttons: [NAV_ROW] };
  } else if (/^(详情|任务卡)\s+/.test(text)) {
    const picked = queryTask(doc, chat, text.replace(/^(详情|任务卡)\s+/, ''));
    result = picked.error ? { reply: picked.error, buttons: [NAV_ROW] } : taskCardReply(doc, picked.task);
  } else if (/^新建\s+/.test(text)) {
    if (!actor) {
      result = { reply: '建卡前先绑定一下：发「我是 队长」（或你的名字）。' };
    } else {
      result = createTask(doc, actor.id, text.replace(/^新建\s+/, ''));
    }
  } else if (/^(开始|开工)\s+/.test(text)) {
    if (!actor) {
      result = { reply: '先发「我是 你的名字」绑定。' };
    } else {
      const picked = queryTask(doc, chat, text.replace(/^(开始|开工)\s+/, ''));
      result = picked.error ? { reply: picked.error, buttons: [NAV_ROW] } : startTask(doc, picked.task, actor.id);
    }
  } else if (/^进度\s+/.test(text)) {
    if (!actor) {
      result = { reply: '先发「我是 你的名字」绑定。' };
    } else {
      const match = text.match(/^进度\s+(.+?)\s*([+]?\d{1,3})\s*%?$/);
      if (match) {
        const picked = queryTask(doc, chat, match[1]);
        if (picked.error) {
          result = { reply: picked.error, buttons: [NAV_ROW] };
        } else {
          const increment = match[2].startsWith('+');
          const value = Number(match[2].replace('+', ''));
          const percent = increment
            ? Math.min(95, picked.task.progress + value)
            : Math.max(0, Math.min(100, value));
          result = updateProgress(doc, picked.task, actor.id, percent, { auto: increment });
        }
      } else {
        const picked = queryTask(doc, chat, text.replace(/^进度\s+/, ''));
        result = picked.error
          ? { reply: picked.error, buttons: [NAV_ROW] }
          : updateProgress(doc, picked.task, actor.id, Math.min(95, picked.task.progress + 20), { auto: true });
      }
    }
  } else if (/^(完成|做完了|搞定)\s+/.test(text)) {
    if (!actor) {
      result = { reply: '先发「我是 你的名字」绑定。' };
    } else {
      const body = text.replace(/^(完成|做完了|搞定)\s+/, '');
      const [query, ...rest] = body.split(/[\s,，]+/);
      const picked = queryTask(doc, chat, query);
      if (picked.error) {
        result = { reply: picked.error, buttons: [NAV_ROW] };
      } else {
        result = completeTask(doc, picked.task, actor.id, rest.join(' ').trim() || null);
        notifications.push(...(result.notifications || []));
      }
    }
  } else {
    result = { reply: `没听懂「${text}」。\n\n${HELP}`, buttons: [NAV_ROW] };
  }

  if (result.taskIds?.length) {
    doc.sessions[chatKey(chat)] = { taskIds: result.taskIds, at: nowIso() };
  }

  if (notifications.length) {
    const unbound = notifications.filter((item) => !item.openid);
    if (unbound.length) {
      result.reply += `\n\n（${unbound.map((item) => item.memberName).join('、')} 还没绑定 QQ 私聊，提醒先存在待通知里）`;
    }
  }
  return { reply: result.reply, buttons: result.buttons, notifications, pending, image };
}
