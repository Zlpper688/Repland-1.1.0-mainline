// Cold-start corpus: written after the parser, from everyday phrasing rather than
// from the parser's grammar. Same gold schema, different names, word orders and
// verbs, so it measures generalization instead of agreement with the training set.
import { TODAY } from './corpus.mjs';

export const COLD_TODAY = TODAY;

const T = (name, date, startMinute = null, durationMinutes = null) => ({
  id: 'seed-1',
  name,
  date,
  startMinute,
  durationMinutes,
});

function item(action, name, extra = {}) {
  return {
    action,
    name,
    date: null,
    startMinute: null,
    durationMinutes: null,
    ...extra,
  };
}

function coldCase(id, category, utterance, gold, tags = [], context = {}) {
  return {
    id,
    category,
    utterance,
    gold: { intent: 'plan', items: [], ...gold },
    tags,
    context: { today: COLD_TODAY, nowMinute: 20 * 60 + 30, ...context },
  };
}

export function buildColdCorpus() {
  const cases = [];
  const add = (...args) => cases.push(coldCase(...args));

  // ---- one-off creates, varied order and units ----
  add('K001', 'cold-create', '明儿一早九点去校医院体检，大概一小时', { items: [item('create', '去校医院体检', { date: '2026-11-16', startMinute: 540, durationMinutes: 60 })] }, ['time', 'dialect']);
  add('K002', 'cold-create', '周二下午两点半跟导师讨论论文，四十五分钟', { items: [item('create', '讨论论文', { date: '2026-11-17', startMinute: 870, durationMinutes: 45 })] }, ['time']);
  add('K003', 'cold-create', '16号上午十点开会，一个半小时', { items: [item('create', '开会', { date: '2026-11-16', startMinute: 600, durationMinutes: 90 })] }, ['time']);
  add('K004', 'cold-create', '今天傍晚六点半去食堂吃饭', { items: [item('create', '去食堂吃饭', { date: '2026-11-15', startMinute: 1110 })] }, ['time']);
  add('K005', 'cold-create', '明晚八点看电影，两个小时', { items: [item('create', '看电影', { date: '2026-11-16', startMinute: 1200, durationMinutes: 120 })] }, ['time']);
  add('K006', 'cold-create', '后天中午十二点跟队友开短会', { items: [item('create', '开短会', { date: '2026-11-17', startMinute: 720 })] }, ['time']);
  add('K007', 'cold-create', '下周一早上八点半到九点背单词', { items: [item('create', '背单词', { date: '2026-11-16', startMinute: 510, endMinute: 540, durationMinutes: 30 })] }, ['range']);
  add('K008', 'cold-create', '20号下午四点前交实验报告', { items: [item('create', '交实验报告', { dueDate: '2026-11-20', dueMinute: 960 })] }, ['due']);
  add('K009', 'cold-create', '11月18日晚上七点到九点练琴', { items: [item('create', '练琴', { date: '2026-11-18', startMinute: 1140, durationMinutes: 120 })] }, ['range']);
  add('K010', 'cold-create', '这周六上午十点去菜鸟驿站取快递', { items: [item('create', '取快递', { date: '2026-11-21', startMinute: 600 })] }, ['time']);
  add('K011', 'cold-create', '明天上午十一点半吃饭，半小时', { items: [item('create', '吃饭', { date: '2026-11-16', startMinute: 690, durationMinutes: 30 })] }, ['time']);
  add('K012', 'cold-create', '今晚九点跑步四十分钟', { items: [item('create', '跑步', { date: '2026-11-15', startMinute: 1260, durationMinutes: 40 })] }, ['time']);
  add('K013', 'cold-create', '周五下午五点前把周报发出去', { items: [item('create', '发周报', { dueDate: '2026-11-20', dueMinute: 1020 })] }, ['due']);
  add('K014', 'cold-create', '明天下午两点开始写代码，写到五点', { items: [item('create', '写代码', { date: '2026-11-16', startMinute: 840, durationMinutes: 180 })] }, ['range']);
  add('K015', 'cold-create', '下周四早上七点二十赶高铁', { items: [item('create', '赶高铁', { date: '2026-11-19', startMinute: 440 })] }, ['time']);
  add('K016', 'cold-create', '17号上午九点陪妈妈去医院复查', { items: [item('create', '陪妈妈去医院复查', { date: '2026-11-17', startMinute: 540 })] }, ['time']);
  add('K017', 'cold-create', '后天下午三点到四点半做实验', { items: [item('create', '做实验', { date: '2026-11-17', startMinute: 900, durationMinutes: 90 })] }, ['range']);
  add('K018', 'cold-create', '今晚七点半到八点看新闻', { items: [item('create', '看新闻', { date: '2026-11-15', startMinute: 1170, endMinute: 1200, durationMinutes: 30 })] }, ['range']);
  add('K019', 'cold-create', '11月22号下午两点开会，两小时', { items: [item('create', '开会', { date: '2026-11-22', startMinute: 840, durationMinutes: 120 })] }, ['time']);
  add('K020', 'cold-create', '明天中午吃完饭后去超市买水果', { items: [item('create', '买水果', { date: '2026-11-16' })] }, ['anchor']);

  // ---- vague creates and captures ----
  add('K021', 'cold-vague', '帮我记一下，给实验室买鼠标', { items: [item('create', '给实验室买鼠标')] }, ['capture']);
  add('K022', 'cold-vague', '提醒我周三交课程论文', { items: [item('create', '交课程论文', { date: '2026-11-18' })] }, ['date-only']);
  add('K023', 'cold-vague', '明天要交英语作业', { items: [item('create', '交英语作业', { date: '2026-11-16' })] }, ['date-only']);
  add('K024', 'cold-vague', '找个时间把房间收拾一下', { items: [item('create', '收拾房间')] }, ['no-date']);
  add('K025', 'cold-vague', '下周末去爬山', { intent: 'clarify', mustAsk: true, items: [] }, ['ambiguous-weekend']);
  add('K026', 'cold-vague', '最近得写个总结', { items: [item('create', '写总结')] }, ['no-date']);
  add('K027', 'cold-vague', '帮我加一项：练口语', { items: [item('create', '练口语')] }, ['capture']);
  add('K028', 'cold-vague', '周四下午交课程设计', { items: [item('create', '交课程设计', { date: '2026-11-19' })] }, ['date-only']);

  // ---- moves on an existing task ----
  add('K029', 'cold-move', '把明早的跑步挪到傍晚六点', { items: [item('schedule', '跑步', { date: '2026-11-16', startMinute: 1080, move: true })] }, ['move'], { tasks: [T('跑步', '2026-11-16', 420, 60)] });
  add('K030', 'cold-move', '数学作业改到后天晚上八点', { items: [item('schedule', '数学作业', { date: '2026-11-17', startMinute: 1200, move: true })] }, ['move'], { tasks: [T('数学作业', '2026-11-16', 900, 60)] });
  add('K031', 'cold-move', '组会从周三下午两点改到周四上午十点', { items: [item('schedule', '组会', { date: '2026-11-19', startMinute: 600, move: true })] }, ['move'], { tasks: [T('组会', '2026-11-18', 840, 60)] });
  add('K032', 'cold-move', '实验报告推后到周五下午三点再写', { items: [item('schedule', '实验报告', { date: '2026-11-20', startMinute: 900, move: true })] }, ['move'], { tasks: [T('实验报告', '2026-11-16', 900, 120)] });
  add('K033', 'cold-move', '明天下午的会议提前到上午十点', { items: [item('schedule', '会议', { date: '2026-11-16', startMinute: 600, move: true })] }, ['move'], { tasks: [T('会议', '2026-11-16', 900, 60)] });
  add('K034', 'cold-move', '把练吉他从今晚改到明晚八点', { items: [item('schedule', '练吉他', { date: '2026-11-16', startMinute: 1200, move: true })] }, ['move'], { tasks: [T('练吉他', '2026-11-15', 1200, 45)] });
  add('K035', 'cold-move', '周三的组会调到周五下午四点', { items: [item('schedule', '组会', { date: '2026-11-20', startMinute: 960, move: true })] }, ['move'], { tasks: [T('组会', '2026-11-18', 840, 60)] });
  add('K036', 'cold-move', '取快递改明天中午', { items: [item('schedule', '取快递', { date: '2026-11-16', startMinute: 720, move: true })] }, ['move'], { tasks: [T('取快递', '2026-11-15', 900, 30)] });
  add('K037', 'cold-move', '高数复习换到明早七点', { items: [item('schedule', '高数复习', { date: '2026-11-16', startMinute: 420, move: true })] }, ['move'], { tasks: [T('高数复习', '2026-11-15', 900, 90)] });
  add('K038', 'cold-move', '把明天的跑步改到六点半', { items: [item('schedule', '跑步', { date: '2026-11-16', startMinute: 390, move: true })] }, ['move'], { tasks: [T('跑步', '2026-11-16', 420, 60)] });

  // ---- status reports ----
  add('K039', 'cold-status', '实验报告交上去了', { items: [item('complete', '实验报告')] }, ['complete'], { tasks: [T('实验报告', '2026-11-15', 900, 120)] });
  add('K040', 'cold-status', '高数复习完了，做了三套卷子', { items: [item('complete', '高数复习')] }, ['complete'], { tasks: [T('高数复习', '2026-11-15', 900, 90)] });
  add('K041', 'cold-status', '今天的跑步取消了', { items: [item('cancel', '跑步')] }, ['cancel'], { tasks: [T('跑步', '2026-11-15', 420, 60)] });
  add('K042', 'cold-status', '组会先不开了', { items: [item('cancel', '组会')] }, ['cancel'], { tasks: [T('组会', '2026-11-15', 840, 60)] });
  add('K043', 'cold-status', '论文写到三分之二了', { items: [item('progress', '论文', { progress: 67 })] }, ['progress'], { tasks: [T('论文', '2026-11-15', 900, 120)] });
  add('K044', 'cold-status', '背单词背了四十分钟，才背了一半', { items: [item('progress', '背单词', { progress: 50 })] }, ['progress'], { tasks: [T('背单词', '2026-11-15', 420, 30)] });
  add('K045', 'cold-status', '蓝桥杯报名没搞定，再往后拖两天', { items: [item('postpone', '报名蓝桥杯', { date: '2026-11-18' })] }, ['postpone'], { tasks: [T('报名蓝桥杯', '2026-11-16', 900, 30)] });
  add('K046', 'cold-status', '项目讨论推迟到周五', { items: [item('postpone', '项目讨论', { date: '2026-11-20' })] }, ['postpone'], { tasks: [T('项目讨论', '2026-11-16', 900, 60)] });
  add('K047', 'cold-status', '今天的实验报告没写完', { items: [item('progress', '实验报告', { progress: null })] }, ['progress'], { tasks: [T('实验报告', '2026-11-15', 900, 120)] });
  add('K048', 'cold-status', '练吉他练到一半被打断了', { items: [item('progress', '练吉他', { progress: null })] }, ['progress'], { tasks: [T('练吉他', '2026-11-15', 1200, 45)] });
  add('K049', 'cold-status', '交作业的事已经搞定了', { items: [item('complete', '交作业')] }, ['complete'], { tasks: [T('交作业', '2026-11-15', 900, 60)] });
  add('K050', 'cold-status', '组会取消吧', { items: [item('cancel', '组会')] }, ['cancel'], { tasks: [T('组会', '2026-11-15', 840, 60)] });

  // ---- self-reported incomplete + extra time ----
  add('K051', 'cold-review', '报告没写完，明天再给我一个半小时', { review: { incomplete: true, progress: null, extraDate: '2026-11-16', extraDuration: 90 }, items: [] }, ['review'], { tasks: [T('报告', '2026-11-15', 900, 120)] });
  add('K052', 'cold-review', '实验只做了一半，明天继续，留两个小时', { review: { incomplete: true, progress: 50, extraDate: '2026-11-16', extraDuration: 120 }, items: [] }, ['review'], { tasks: [T('实验', '2026-11-15', 900, 120)] });
  add('K053', 'cold-review', '单词没背完，后天补四十分钟', { review: { incomplete: true, progress: null, extraDate: '2026-11-17', extraDuration: 40 }, items: [] }, ['review'], { tasks: [T('单词', '2026-11-15', 420, 30)] });
  add('K054', 'cold-review', '数学卷子做到一半，今晚再给半小时', { review: { incomplete: true, progress: 50, extraDate: '2026-11-15', extraDuration: 30 }, items: [] }, ['review'], { tasks: [T('数学卷子', '2026-11-15', 900, 60)] });
  add('K055', 'cold-review', '代码没写完，明天上午再排两小时', { review: { incomplete: true, progress: null, extraDate: '2026-11-16', extraDuration: 120 }, items: [] }, ['review'], { tasks: [T('代码', '2026-11-15', 900, 90)] });
  add('K056', 'cold-review', '论文只写了个开头，明天再给一小时', { review: { incomplete: true, progress: null, extraDate: '2026-11-16', extraDuration: 60 }, items: [] }, ['review'], { tasks: [T('论文', '2026-11-15', 900, 120)] });
  add('K057', 'cold-review', '这周的报告没交，明天补上', { review: { incomplete: true, progress: null, extraDate: '2026-11-16', extraDuration: null }, items: [] }, ['review'], { tasks: [T('报告', '2026-11-15', 900, 120)] });
  add('K058', 'cold-review', '实验报告没做完，明天下午再给我两个小时', { review: { incomplete: true, progress: null, extraDate: '2026-11-16', extraDuration: 120 }, items: [] }, ['review'], { tasks: [T('实验报告', '2026-11-15', 900, 120)] });
  add('K059', 'cold-review', '练琴没练完，明晚再排四十五分钟', { review: { incomplete: true, progress: null, extraDate: '2026-11-16', extraDuration: 45 }, items: [] }, ['review'], { tasks: [T('练琴', '2026-11-15', 1200, 45)] });
  add('K060', 'cold-review', '高数只复习了一半，明天再做一套题', { review: { incomplete: true, progress: 50, extraDate: '2026-11-16', extraDuration: null }, items: [] }, ['review'], { tasks: [T('高数', '2026-11-15', 900, 90)] });

  // ---- recurring ----
  add('K061', 'cold-routine', '每天早上六点半跑五公里', { items: [item('routine', '跑五公里', { startMinute: 390, weekdays: [1, 2, 3, 4, 5, 6, 7] })] }, ['routine']);
  add('K062', 'cold-routine', '周一到周五中午十二点吃饭，一小时', { items: [item('routine', '吃饭', { startMinute: 720, durationMinutes: 60, weekdays: [1, 2, 3, 4, 5] })] }, ['routine']);
  add('K063', 'cold-routine', '每周二四六晚上八点练吉他', { items: [item('routine', '练吉他', { startMinute: 1200, weekdays: [2, 4, 6] })] }, ['routine']);
  add('K064', 'cold-routine', '工作日上午十点背单词半小时', { items: [item('routine', '背单词', { startMinute: 600, durationMinutes: 30, weekdays: [1, 2, 3, 4, 5] })] }, ['routine']);
  add('K065', 'cold-routine', '每个周末上午九点去图书馆', { items: [item('routine', '去图书馆', { startMinute: 540, weekdays: [6, 7] })] }, ['routine']);
  add('K066', 'cold-routine', '每周三下午两点组会，一个半小时', { items: [item('routine', '组会', { startMinute: 840, durationMinutes: 90, weekdays: [3] })] }, ['routine']);
  add('K067', 'cold-routine', '每天睡前看半小时书', { items: [item('routine', '看书', { durationMinutes: 30, weekdays: [1, 2, 3, 4, 5, 6, 7] })] }, ['routine', 'anchor']);
  add('K068', 'cold-routine', '每周一早上七点升国旗', { items: [item('routine', '升国旗', { startMinute: 420, weekdays: [1] })] }, ['routine']);
  add('K069', 'cold-routine', '周二和周四晚上七点健身一小时', { items: [item('routine', '健身', { startMinute: 1140, durationMinutes: 60, weekdays: [2, 4] })] }, ['routine']);
  add('K070', 'cold-routine', '从下周一开始每天练口语二十分钟', { items: [item('routine', '练口语', { durationMinutes: 20, weekdays: [1, 2, 3, 4, 5, 6, 7] })] }, ['routine']);

  // ---- deadlines ----
  add('K071', 'cold-due', '下周五前把开题报告写完', { items: [item('create', '写开题报告', { dueDate: '2026-11-20' })] }, ['due']);
  add('K072', 'cold-due', '月底前交房租', { items: [item('create', '交房租', { dueDate: '2026-11-30' })] }, ['due']);
  add('K073', 'cold-due', '12月1号之前报名四级', { items: [item('create', '报名四级', { dueDate: '2026-12-01' })] }, ['due']);
  add('K074', 'cold-due', '周五下午三点前提交表单', { items: [item('create', '提交表单', { dueDate: '2026-11-20', dueMinute: 900 })] }, ['due']);
  add('K075', 'cold-due', '明天上午十点前把材料发给老师', { items: [item('create', '发材料', { dueDate: '2026-11-16', dueMinute: 600 })] }, ['due']);
  add('K076', 'cold-due', '11.25前完成课程设计', { items: [item('create', '完成课程设计', { dueDate: '2026-11-25' })] }, ['due']);

  // ---- questions and boundaries ----
  add('K077', 'cold-query', '我周三下午有空吗', { intent: 'query', items: [] }, ['query']);
  add('K078', 'cold-query', '这周还剩几个小时', { intent: 'query', items: [] }, ['query']);
  add('K079', 'cold-query', '今天还有什么没做的', { intent: 'query', items: [] }, ['query']);
  add('K080', 'cold-query', '好累啊不想动', { intent: 'query', items: [] }, ['chat']);
  add('K081', 'cold-query', '先做哪件事比较好', { intent: 'query', items: [] }, ['query']);
  add('K082', 'cold-query', '明天的时间怎么安排比较好', { intent: 'query', items: [] }, ['advice']);
  add('K083', 'cold-query', '如果明天下雨就取消跑步', { intent: 'clarify', mustAsk: true, items: [] }, ['conditional']);
  add('K084', 'cold-query', '把这个挪到后天', { intent: 'clarify', mustAsk: true, items: [] }, ['pronoun']);
  add('K085', 'cold-query', '随便排就行', { intent: 'clarify', mustAsk: true, items: [] }, ['ambiguous']);
  add('K086', 'cold-create', 'tomorrow 3pm meeting', { allowAsk: true, items: [item('create', 'meeting', { date: '2026-11-16', startMinute: 900 })] }, ['english']);
  add('K087', 'cold-query', '下午两点', { intent: 'clarify', mustAsk: true, items: [] }, ['missing-task']);

  // ---- two intents in one sentence ----
  add('K088', 'cold-multi', '明天上午十点开会，然后下午三点写报告', { items: [item('create', '开会', { date: '2026-11-16', startMinute: 600 }), item('create', '写报告', { date: '2026-11-16', startMinute: 900 })] }, ['multi']);
  add('K089', 'cold-multi', '明早八点跑步，顺便取快递', { items: [item('create', '跑步', { date: '2026-11-16', startMinute: 480 }), item('create', '取快递', { date: '2026-11-16' })] }, ['multi']);
  add('K090', 'cold-multi', '后天交作业，晚上八点打球两小时', { items: [item('create', '交作业', { date: '2026-11-17' }), item('create', '打球', { date: '2026-11-17', startMinute: 1200, durationMinutes: 120 })] }, ['multi']);

  return cases;
}
