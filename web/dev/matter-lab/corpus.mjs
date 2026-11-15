// 500 reproducible requirement cases with gold slots.
// today is pinned to 2026-11-15 (Sunday) so relative dates never drift.

export const TODAY = '2026-11-15';
export const NOW_MINUTE = 20 * 60 + 30;

function addDays(date, n) {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

const DATE = {
  tomorrow: { expr: '明天', date: '2026-11-16' },
  dayAfter: { expr: '后天', date: '2026-11-17' },
  threeDays: { expr: '大后天', date: '2026-11-18' },
  nextWed: { expr: '下周三', date: '2026-11-18' },
  nextFri: { expr: '下周五', date: '2026-11-20' },
  nextNextMon: { expr: '下下周一', date: '2026-11-23' },
  nov20: { expr: '11月20号', date: '2026-11-20' },
  today: { expr: '今天', date: '2026-11-15' },
};

const FUTURE_DATES = [
  DATE.tomorrow,
  DATE.dayAfter,
  DATE.threeDays,
  DATE.nextWed,
  DATE.nextFri,
  DATE.nextNextMon,
  DATE.nov20,
];

const TIMES = [
  { expr: '早上七点', minute: 420 },
  { expr: '上午十点', minute: 600 },
  { expr: '中午十二点', minute: 720 },
  { expr: '下午三点', minute: 900 },
  { expr: '下午三点半', minute: 930 },
  { expr: '下午四点', minute: 960 },
  { expr: '晚上八点', minute: 1200 },
  { expr: '20:30', minute: 1230 },
];

const DURATIONS = [
  { expr: '半小时', minutes: 30 },
  { expr: '四十分钟', minutes: 40 },
  { expr: '一小时', minutes: 60 },
  { expr: '一个半小时', minutes: 90 },
  { expr: '两小时', minutes: 120 },
  { expr: '三个钟头', minutes: 180 },
];

const MATTERS = [
  { name: '写实验报告', object: '实验报告', verb: '写', type: 'assignment', minutes: 120 },
  { name: '交数学作业', object: '数学作业', verb: '交', type: 'assignment', minutes: 60 },
  { name: '复习高数', object: '高数', verb: '复习', type: 'exam', minutes: 90 },
  { name: '准备英语六级', object: '英语六级', verb: '准备', type: 'exam', minutes: 120 },
  { name: '开组会', object: '组会', verb: '开', type: 'meeting', minutes: 60 },
  { name: '项目讨论', object: '项目', verb: '讨论', type: 'meeting', minutes: 60 },
  { name: '准备数模比赛', object: '数模比赛', verb: '准备', type: 'competition', minutes: 120 },
  { name: '报名蓝桥杯', object: '蓝桥杯', verb: '报名', type: 'competition', minutes: 30 },
  { name: '取快递', object: '快递', verb: '取', type: 'errand', minutes: 30 },
  { name: '去教务处盖章', object: '教务处盖章', verb: '去', type: 'errand', minutes: 45 },
  { name: '买教材', object: '教材', verb: '买', type: 'errand', minutes: 60 },
  { name: '跑步', object: '步', verb: '跑', type: 'habit', minutes: 60 },
  { name: '背单词', object: '单词', verb: '背', type: 'habit', minutes: 30 },
  { name: '练吉他', object: '吉他', verb: '练', type: 'habit', minutes: 45 },
  { name: '写代码', object: '代码', verb: '写', type: 'generic', minutes: 90 },
  { name: '看书', object: '书', verb: '看', type: 'generic', minutes: 60 },
  { name: '整理房间', object: '房间', verb: '整理', type: 'generic', minutes: 45 },
  { name: '写周报', object: '周报', verb: '写', type: 'assignment', minutes: 60 },
  { name: '做PPT', object: 'PPT', verb: '做', type: 'assignment', minutes: 90 },
  { name: '准备答辩', object: '答辩', verb: '准备', type: 'competition', minutes: 120 },
];

const POLITE = ['', '帮我', '麻烦', '我想', '给我', '能不能'];
const PARTICLES = ['', '吧', '呗', '就行', '谢谢', '啊'];

function withVerb(matter, date, time, duration, polite, particle, index) {
  const templates = [
    (m, d, t, u) => `${polite}${d.expr}${t.expr}${m.verb}${m.object}，${u.expr}${particle}`,
    (m, d, t, u) => `${polite}${m.verb}${m.object}，${d.expr}${t.expr}，${u.expr}${particle}`,
    (m, d, t, u) => `${polite}把${m.name}放到${d.expr}${t.expr}，${u.expr}${particle}`,
    (m, d, t, u) => `${m.name}，${d.expr}${t.expr}，${u.expr}${particle}`,
    (m, d, t, u) => `${polite}${d.expr}${t.expr}安排一下${m.name}，${u.expr}${particle}`,
    (m, d, t, u) => `${d.expr}${t.expr}${m.verb}${m.object}${particle}，大概${u.expr}`,
  ];
  return templates[index % templates.length](matter, date, time, duration);
}

function baseCase(id, category, utterance, gold, tags, context = {}) {
  return { id, category, utterance, context, gold, tags };
}

function buildMatrix() {
  const cases = [];

  // 1. Explicit create: date + time + duration.
  for (let i = 0; i < 120; i += 1) {
    const matter = MATTERS[i % MATTERS.length];
    const date = FUTURE_DATES[(i * 3) % FUTURE_DATES.length];
    const time = TIMES[(i * 5) % TIMES.length];
    const duration = DURATIONS[(i * 7) % DURATIONS.length];
    const polite = POLITE[(i * 2) % POLITE.length];
    const particle = PARTICLES[(i * 3 + 1) % PARTICLES.length];
    const utterance = withVerb(matter, date, time, duration, polite, particle, i);
    cases.push(
      baseCase(`E${String(i + 1).padStart(3, '0')}`, 'create_explicit', utterance, {
        intent: 'plan',
        items: [
          {
            action: 'create',
            name: matter.name,
            date: date.date,
            startMinute: time.minute,
            durationMinutes: duration.minutes,
          },
        ],
      }, ['explicit', matter.type, 'rules'],
      { today: TODAY, nowMinute: NOW_MINUTE },
      ),
    );
  }

  // 2. Date only: time is delegated.
  for (let i = 0; i < 60; i += 1) {
    const matter = MATTERS[i % MATTERS.length];
    const date = FUTURE_DATES[(i * 2 + 1) % FUTURE_DATES.length];
    const duration = DURATIONS[(i * 5 + 2) % DURATIONS.length];
    const polite = POLITE[(i + 2) % POLITE.length];
    const particle = PARTICLES[(i * 2) % PARTICLES.length];
    const templates = [
      `${polite}${date.expr}${matter.verb}${matter.object}，${duration.expr}${particle}`,
      `${polite}${date.expr}安排${matter.name}，${duration.expr}${particle}`,
      `${matter.name}${date.expr}${matter.verb}${particle}，${duration.expr}`,
    ];
    const utterance = templates[i % templates.length];
    cases.push(
      baseCase(`D${String(i + 1).padStart(3, '0')}`, 'create_date_only', utterance, {
        intent: 'plan',
        items: [
          {
            action: 'create',
            name: matter.name,
            date: date.date,
            startMinute: null,
            durationMinutes: duration.minutes,
          },
        ],
      }, ['date-only', matter.type, 'rules'],
      { today: TODAY, nowMinute: NOW_MINUTE },
      ),
    );
  }

  // 3. Capture only: no date, no time, no duration.
  for (let i = 0; i < 50; i += 1) {
    const matter = MATTERS[i % MATTERS.length];
    const polite = POLITE[i % POLITE.length];
    const templates = [
      `${polite}记一下，${matter.name}`,
      `${polite}先记着${matter.name}，别排`,
      `只记一下${matter.name}`,
    ];
    cases.push(
      baseCase(`C${String(i + 1).padStart(3, '0')}`, 'capture_no_date', templates[i % templates.length], {
        intent: 'plan',
        items: [
          {
            action: 'create',
            name: matter.name,
            date: null,
            startMinute: null,
            durationMinutes: null,
          },
        ],
      }, ['capture', matter.type, 'rules'],
      { today: TODAY, nowMinute: NOW_MINUTE },
      ),
    );
  }

  // 4. Routine / recurrence.
  for (let i = 0; i < 80; i += 1) {
    const variant = Math.floor(i / 20);
    const idx = i % 20;
    const matter = MATTERS[idx];
    const duration = DURATIONS[(i * 5) % DURATIONS.length];
    let utterance;
    let weekdays;
    let startMinute = null;
    let tags = ['routine', matter.type];
    if (variant === 0) {
      weekdays = [1, 2, 3, 4, 5, 6, 7];
      const options = [
        `每天${matter.verb}${matter.object}${duration.expr}，看着空档`,
        `我想每天${duration.expr}${matter.verb}${matter.object}，你看着排`,
        `${matter.name}排成每天${duration.expr}，合适时间就行`,
      ];
      utterance = options[idx % options.length];
      tags.push('daily', 'auto-time');
    } else if (variant === 1) {
      weekdays = [1, 2, 3, 4, 5];
      const time = TIMES[(i * 3 + 1) % TIMES.length];
      startMinute = time.minute;
      const options = [
        `工作日${time.expr}${matter.verb}${matter.object}，${duration.expr}`,
        `周一到周五${time.expr}${matter.verb}${matter.object}，${duration.expr}`,
      ];
      utterance = options[idx % options.length];
      tags.push('workdays', 'explicit-time');
    } else if (variant === 2) {
      weekdays = [6, 7];
      const options = [
        `周末${matter.verb}${matter.object}${duration.expr}，挑个合适时间`,
        `${matter.name}放周末，${duration.expr}，你安排`,
      ];
      utterance = options[idx % options.length];
      tags.push('weekend', 'auto-time');
    } else {
      const weekday = [1, 3, 5][i % 3];
      weekdays = [weekday];
      const time = TIMES[(i * 5 + 3) % TIMES.length];
      startMinute = time.minute;
      const label = ['一', '三', '五'][i % 3];
      const options = [
        `每周${label}${time.expr}${matter.verb}${matter.object}，${duration.expr}`,
        `每周${label}${time.expr}安排${matter.name}，${duration.expr}`,
      ];
      utterance = options[i % options.length];
      tags.push('weekly', 'explicit-time');
    }
    cases.push(
      baseCase(`R${String(i + 1).padStart(3, '0')}`, 'routine', utterance, {
        intent: 'plan',
        items: [
          {
            action: 'routine',
            name: matter.name,
            date: null,
            startMinute,
            durationMinutes: duration.minutes,
            weekdays,
          },
        ],
      }, tags,
      { today: TODAY, nowMinute: NOW_MINUTE },
      ),
    );
  }

  // 5. Move an existing task.
  for (let i = 0; i < 50; i += 1) {
    const matter = MATTERS[i % 10];
    const templateIndex = Math.floor(i / 10) % 5;
    const toDate = [DATE.dayAfter, DATE.nextWed, DATE.nextFri, DATE.nov20, DATE.nextNextMon][i % 5];
    const toTime = TIMES[(i * 3 + 2) % TIMES.length];
    const templates = [
      `把明天的${matter.name}挪到${toDate.expr}${toTime.expr}`,
      `${matter.name}改到${toDate.expr}${toTime.expr}`,
      `帮我把${matter.name}移到${toDate.expr}${toTime.expr}`,
      `${matter.name}换到${toDate.expr}${toTime.expr}`,
      `把明天的${matter.name}推迟到${toDate.expr}${toTime.expr}`,
    ];
    const task = {
      id: 'seed-1',
      name: matter.name,
      date: '2026-11-16',
      startMinute: 900,
      durationMinutes: matter.minutes,
    };
    cases.push(
      baseCase(`M${String(i + 1).padStart(3, '0')}`, 'move', templates[templateIndex], {
        intent: 'plan',
        items: [
          {
            action: 'schedule',
            name: matter.name,
            date: toDate.date,
            startMinute: toTime.minute,
            durationMinutes: matter.minutes,
            move: true,
          },
        ],
      }, ['move', matter.type, 'rules'],
      { today: TODAY, nowMinute: NOW_MINUTE, tasks: [task] },
      ),
    );
  }

  // 6. Status changes on an existing task.
  for (let i = 0; i < 40; i += 1) {
    const matter = MATTERS[i % 10];
    const action = ['complete', 'cancel', 'postpone', 'progress'][Math.floor(i / 10) % 4];
    const task = {
      id: 'seed-1',
      name: matter.name,
      date: '2026-11-16',
      startMinute: 900,
      durationMinutes: matter.minutes,
    };
    const templates = {
      complete: [
        `${matter.name}写完了`,
        `${matter.name}搞定了`,
        `我已经把${matter.name}做完了`,
        `${matter.name}交了`,
      ],
      cancel: [
        `取消${matter.name}`,
        `${matter.name}不做了`,
        `别做${matter.name}了`,
        `把${matter.name}取消掉`,
      ],
      postpone: [
        `${matter.name}往后推一天`,
        `${matter.name}顺延到后天`,
        `今天不${matter.verb}${matter.object}了，改天`,
        `${matter.name}推迟一天`,
      ],
      progress: [
        `${matter.name}写了一半`,
        `${matter.name}做到一半`,
        `${matter.name}进度50%`,
        `${matter.name}只完成了一半`,
      ],
    };
    cases.push(
      baseCase(`S${String(i + 1).padStart(3, '0')}`, 'status', templates[action][i % 4], {
        intent: 'plan',
        items: [
          {
            action,
            name: matter.name,
            date: action === 'postpone' ? (i % 4 === 2 ? null : '2026-11-17') : null,
            startMinute: null,
            durationMinutes: null,
            progress: action === 'progress' ? 50 : null,
          },
        ],
      }, ['status', action, matter.type, 'rules'],
      { today: TODAY, nowMinute: NOW_MINUTE, tasks: [task] },
      ),
    );
  }

  // 7. Natural-language incomplete + extra time.
  for (let i = 0; i < 40; i += 1) {
    const matter = MATTERS[i % 10];
    const variant = Math.floor(i / 10) % 4;
    const duration = DURATIONS[(i * 3 + 1) % DURATIONS.length];
    const task = {
      id: 'seed-1',
      name: matter.name,
      date: '2026-11-15',
      startMinute: 900,
      durationMinutes: matter.minutes,
    };
    const templates = [
      {
        text: `${matter.name}没做完，明天再给我${duration.expr}`,
        extraDate: '2026-11-16',
        extraDuration: duration.minutes,
        progress: null,
      },
      {
        text: `今天${matter.name}只写了一半，明天补上`,
        extraDate: '2026-11-16',
        extraDuration: null,
        progress: 50,
      },
      {
        text: `${matter.name}没搞完，后天再排${duration.expr}`,
        extraDate: '2026-11-17',
        extraDuration: duration.minutes,
        progress: null,
      },
      {
        text: `昨天没${matter.verb}${matter.object}，今天补上`,
        extraDate: '2026-11-15',
        extraDuration: null,
        progress: null,
      },
    ][variant];
    cases.push(
      baseCase(`V${String(i + 1).padStart(3, '0')}`, 'review', templates.text, {
        intent: 'plan',
        review: {
          incomplete: true,
          progress: templates.progress,
          extraDate: templates.extraDate,
          extraDuration: templates.extraDuration,
        },
        items: [],
      }, ['review', 'self-report', 'ai'],
      { today: TODAY, nowMinute: NOW_MINUTE, tasks: [task] },
      ),
    );
  }

  return cases;
}

const ADVERSARIAL = [
  ['不是周三，是周四下午三点开组会', 'plan', [{ action: 'create', name: '开组会', date: '2026-11-19', startMinute: 900, durationMinutes: null }], ['correction', 'ai']],
  ['别排上午，下午再写报告', 'plan', [{ action: 'create', name: '写报告', date: null, startMinute: null, durationMinutes: null }], ['constraint', 'ai']],
  ['明天下午三点到五点写报告', 'plan', [{ action: 'create', name: '写报告', date: '2026-11-16', startMinute: 900, endMinute: 1020, durationMinutes: 120 }], ['range', 'rules']],
  ['晚上十一点到凌晨一点打游戏', 'clarify', [], ['cross-midnight', 'ai']],
  ['每天晚饭后跑半小时', 'plan', [{ action: 'routine', name: '跑步', date: null, startMinute: null, durationMinutes: 30, weekdays: [1, 2, 3, 4, 5, 6, 7] }], ['anchor', 'routine', 'ai']],
  ['每周三晚上八点开组会，持续到九点半', 'plan', [{ action: 'routine', name: '开组会', date: null, startMinute: 1200, durationMinutes: 90, weekdays: [3] }], ['routine', 'range', 'rules']],
  ['工作日早上七点背单词二十分钟', 'plan', [{ action: 'routine', name: '背单词', date: null, startMinute: 420, durationMinutes: 20, weekdays: [1, 2, 3, 4, 5] }], ['routine', 'rules']],
  ['周末打篮球两小时，看着空档', 'plan', [{ action: 'routine', name: '打篮球', date: null, startMinute: null, durationMinutes: 120, weekdays: [6, 7] }], ['routine', 'auto-time', 'rules']],
  ['隔天跑一次步', 'clarify', [], ['unsupported-recurrence', 'ai']],
  ['每月5号交房租', 'clarify', [], ['unsupported-recurrence', 'ai']],
  ['下下周一下午两点半开项目会，一个小时', 'plan', [{ action: 'create', name: '开项目会', date: '2026-11-23', startMinute: 870, durationMinutes: 60 }], ['relative-date', 'rules']],
  ['11.20 前把报告交了', 'plan', [{ action: 'create', name: '交报告', dueDate: '2026-11-20', date: null, startMinute: null, durationMinutes: null }], ['due-date', 'ai']],
  ['月底之前复习完高数，每天两小时', 'plan', [{ action: 'routine', name: '复习高数', date: null, startMinute: null, durationMinutes: 120, weekdays: [1, 2, 3, 4, 5, 6, 7] }], ['deadline', 'routine', 'ai']],
  ['这周三的组会改到周四下午四点', 'plan', [{ action: 'schedule', name: '组会', date: '2026-11-19', startMinute: 960, durationMinutes: null, move: true }], ['move', 'relative-date', 'ai']],
  ['把明天的跑步取消', 'plan', [{ action: 'cancel', name: '跑步', date: null, startMinute: null, durationMinutes: null }], ['cancel', 'ai']],
  ['报告写完了，比预计多花了半小时', 'plan', [{ action: 'complete', name: '报告', date: null, startMinute: null, durationMinutes: null, actualMinutes: 90 }], ['complete', 'actual', 'ai']],
  ['数学只写了一半，明天再给我一个小时', 'plan', [{ action: 'progress', name: '数学', progress: 50, date: null, startMinute: null, durationMinutes: null }], ['review', 'self-report', 'ai']],
  ['今天太累了，不想学了', 'query', [], ['chat', 'no-action']],
  ['我明天有空吗', 'query', [], ['query', 'free-time']],
  ['今天还剩多少时间', 'query', [], ['query', 'time-budget']],
  ['帮我排一下下周', 'clarify', [], ['ambiguous', 'ai']],
  ['最近有点忙，你看着办', 'clarify', [], ['ambiguous', 'ai']],
  ['明天下午三点', 'clarify', [], ['missing-task', 'ai']],
  ['把那个会挪到四点', 'clarify', [], ['pronoun', 'ai']],
  ['不是明天，是后天', 'clarify', [], ['correction', 'needs-draft', 'ai']],
  ['先记一下：买教材', 'plan', [{ action: 'create', name: '买教材', date: null, startMinute: null, durationMinutes: null }], ['capture', 'rules']],
  ['只记着，别排', 'clarify', [], ['missing-task', 'ai']],
  ['顺便帮我取个快递', 'plan', [{ action: 'create', name: '取快递', date: null, startMinute: null, durationMinutes: 30 }], ['capture', 'errand', 'ai']],
  ['中午十二点到一点吃饭，别排事', 'plan', [{ action: 'busy', name: '吃饭', date: '2026-11-15', startMinute: 720, endMinute: 780 }], ['busy-block', 'ai']],
  ['周三下午考试，考前一周每天复习两小时', 'plan', [{ action: 'routine', name: '复习', date: null, startMinute: null, durationMinutes: 120, weekdays: [1, 2, 3, 4, 5, 6, 7] }], ['deadline', 'routine', 'ai']],
  ['如果周五下雨就不跑步了', 'clarify', [], ['conditional', 'ai']],
  ['除非作业写完，否则不玩游戏', 'clarify', [], ['conditional', 'ai']],
  ['明天上午十点开组会，地点在图书馆，记得带电脑', 'plan', [{ action: 'create', name: '开组会', date: '2026-11-16', startMinute: 600, durationMinutes: 60 }], ['meeting', 'note', 'ai']],
  ['帮我报名蓝桥杯，截止11月30号', 'plan', [{ action: 'create', name: '报名蓝桥杯', dueDate: '2026-11-30', date: null, startMinute: null, durationMinutes: null }], ['competition', 'due-date', 'ai']],
  ['取快递，菜鸟驿站，晚上七点前', 'plan', [{ action: 'create', name: '取快递', date: '2026-11-15', startMinute: null, dueMinute: 1140, durationMinutes: null }], ['errand', 'deadline', 'ai']],
  ['下周三交报告', 'plan', [{ action: 'create', name: '交报告', date: '2026-11-18', startMinute: null, durationMinutes: null }], ['date-only', 'rules']],
  ['周五前把PPT做完，大概三个小时', 'plan', [{ action: 'create', name: '做PPT', dueDate: '2026-11-20', date: null, startMinute: null, durationMinutes: 180 }], ['deadline', 'ai']],
  ['明天下午三点写报告两个小时，然后四点开组会一小时', 'plan', [{ action: 'create', name: '写报告', date: '2026-11-16', startMinute: 900, durationMinutes: 120 }, { action: 'create', name: '开组会', date: '2026-11-16', startMinute: 960, durationMinutes: 60 }], ['multi-intent', 'overlap', 'ai']],
  ['明天下午三点开会，顺便把周报写了', 'plan', [{ action: 'create', name: '开会', date: '2026-11-16', startMinute: 900, durationMinutes: null }, { action: 'create', name: '写周报', date: null, startMinute: null, durationMinutes: null }], ['multi-intent', 'ai']],
  ['明天下午三点到五点写报告，中间休息十分钟', 'plan', [{ action: 'create', name: '写报告', date: '2026-11-16', startMinute: 900, endMinute: 1020, durationMinutes: 120 }], ['range', 'break', 'ai']],
  ['别把任务排得太满，留半小时缓冲', 'query', [], ['constraint', 'spread', 'ai']],
  ['把周三的课取消', 'clarify', [], ['fixed-block', 'ai']],
  ['周三下午的课改到周四', 'clarify', [], ['fixed-block', 'ai']],
  ['每天四个小时玩王者荣耀，看着空档', 'plan', [{ action: 'routine', name: '玩王者荣耀', date: null, startMinute: null, durationMinutes: 240, weekdays: [1, 2, 3, 4, 5, 6, 7] }], ['routine', 'daily', 'auto-time', 'rules']],
  ['给我把玩王者荣耀这件事排成每天四个小时看着空挡和合适时间放', 'plan', [{ action: 'routine', name: '玩王者荣耀', date: null, startMinute: null, durationMinutes: 240, weekdays: [1, 2, 3, 4, 5, 6, 7] }], ['routine', 'daily', 'auto-time', 'typo', 'rules']],
  ['明儿下午三点写报告，俩小时', 'plan', [{ action: 'create', name: '写报告', date: '2026-11-16', startMinute: 900, durationMinutes: 120 }], ['dialect', 'rules']],
  ['后天下午3点开会，1.5小时', 'plan', [{ action: 'create', name: '开会', date: '2026-11-17', startMinute: 900, durationMinutes: 90 }], ['arabic', 'decimal', 'rules']],
  ['11/20 15:00 写报告 120min', 'plan', [{ action: 'create', name: '写报告', date: '2026-11-20', startMinute: 900, durationMinutes: 120 }], ['date-format', 'english-unit', 'rules']],
  ['tomorrow 3pm meeting 1h', 'clarify', [], ['english', 'ai']],
  ['下周三下午三点开会，哦不，是四点', 'plan', [{ action: 'create', name: '开会', date: '2026-11-18', startMinute: 960, durationMinutes: null }], ['self-correction', 'ai']],
  ['下周一到周三每天上午复习高数', 'plan', [{ action: 'routine', name: '复习高数', date: null, startMinute: 600, durationMinutes: null, weekdays: [1, 2, 3] }], ['date-range', 'routine', 'ai']],
  ['今天下午四点前把作业交了', 'plan', [{ action: 'create', name: '交作业', dueDate: '2026-11-15', dueMinute: 960, date: null, startMinute: null, durationMinutes: null }], ['deadline', 'ai']],
  ['今天晚上不排事，我想休息', 'query', [], ['boundary', 'no-action', 'ai']],
  ['帮我把这周的安排都往后挪一天', 'clarify', [], ['bulk-move', 'ai']],
  ['从明天开始每天背单词半小时', 'plan', [{ action: 'routine', name: '背单词', date: null, startMinute: null, durationMinutes: 30, weekdays: [1, 2, 3, 4, 5, 6, 7] }], ['routine', 'ai']],
  ['每周一三五早上七点跑步，周末休息', 'plan', [{ action: 'routine', name: '跑步', date: null, startMinute: 420, durationMinutes: null, weekdays: [1, 3, 5] }], ['routine', 'rules']],
  ['下周五之前准备答辩，每天一小时', 'plan', [{ action: 'routine', name: '准备答辩', date: null, startMinute: null, durationMinutes: 60, weekdays: [1, 2, 3, 4, 5, 6, 7] }], ['deadline', 'routine', 'ai']],
  ['把明天下午的写报告挪到晚上八点，时长不变', 'plan', [{ action: 'schedule', name: '写报告', date: '2026-11-16', startMinute: 1200, durationMinutes: null, move: true }], ['move', 'relative-date', 'ai']],
  ['我昨天没写报告，今天补上', 'plan', [{ action: 'progress', name: '写报告', date: null, startMinute: null, durationMinutes: null }], ['review', 'self-report', 'ai']],
  ['这周数学作业没交，老师说明天要补交', 'plan', [{ action: 'create', name: '交数学作业', dueDate: '2026-11-16', date: null, startMinute: null, durationMinutes: null }], ['overdue', 'due-date', 'ai']],
];

function buildAdversarial() {
  return ADVERSARIAL.map(([utterance, intent, items, tags], index) => {
    const needsDraft = tags.includes('needs-draft');
    const gold = { intent, items };
    if (intent === 'clarify') gold.mustAsk = true;
    return baseCase(`A${String(index + 1).padStart(3, '0')}`, 'adversarial', utterance, gold, tags, {
      today: TODAY,
      nowMinute: NOW_MINUTE,
      tasks: tags.includes('fixed-block') ? [] : undefined,
      needsDraft,
    });
  });
}

export function buildCorpus() {
  const cases = [...buildMatrix(), ...buildAdversarial()];
  const seen = new Set();
  const unique = [];
  for (const item of cases) {
    if (seen.has(item.utterance)) continue;
    seen.add(item.utterance);
    unique.push(item);
  }
  return unique.slice(0, 500).map((item, index) => ({ ...item, index: index + 1 }));
}

export function summarizeCorpus(cases) {
  const byCategory = {};
  const byTag = {};
  for (const item of cases) {
    byCategory[item.category] = (byCategory[item.category] || 0) + 1;
    for (const tag of item.tags) byTag[tag] = (byTag[tag] || 0) + 1;
  }
  return { total: cases.length, byCategory, byTag };
}
