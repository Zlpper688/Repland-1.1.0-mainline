// Deterministic reading of the sentence: names, actions and calendar slots.
// The model owns language judgment; this module owns dates, clocks, durations,
// recurrence and the review/postpone protocol, so a slot can never be invented.

const CN_NUM = { 零: 0, 一: 1, 二: 2, 两: 2, 俩: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 };
const WEEKDAY = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 7, 天: 7 };
const STATUS_ACTIONS = new Set(['complete', 'cancel', 'progress', 'postpone']);

function normalize(text) {
  return String(text || '')
    .normalize('NFKC')
    .replace(/[０-９]/g, (ch) => String.fromCharCode(ch.charCodeAt(0) - 0xFEE0))
    .replace(/[：]/g, ':')
    .replace(/[，、]/g, ',')
    .replace(/[。！？!?；;]/g, (ch) => (ch === '？' || ch === '?' ? '?' : ','))
    .replace(/\s+/g, ' ')
    .trim();
}

function cnToNumber(raw) {
  if (raw == null) return null;
  const value = String(raw);
  if (/^\d+(?:\.\d+)?$/.test(value)) return Number(value);
  if (value === '半') return 0.5;
  if (value.length === 1 && CN_NUM[value] != null) return CN_NUM[value];
  if (value === '十') return 10;
  if (value.startsWith('十')) return 10 + (CN_NUM[value.slice(1)] || 0);
  if (value.endsWith('十')) return (CN_NUM[value[0]] || 0) * 10;
  const parts = value.split('十');
  if (parts.length === 2) return (CN_NUM[parts[0]] || 0) * 10 + (CN_NUM[parts[1]] || 0);
  return null;
}

function pad(n) {
  return String(n).padStart(2, '0');
}

function addDays(date, n) {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

function weekdayOf(date) {
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  return day === 0 ? 7 : day;
}

function endOfMonth(date) {
  const d = new Date(`${date}T00:00:00Z`);
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).toISOString().slice(0, 10);
}

function monthDay(date, month, day) {
  const year = Number(date.slice(0, 4));
  let result = `${year}-${pad(month)}-${pad(day)}`;
  if (result < date) result = `${year + 1}-${pad(month)}-${pad(day)}`;
  return result;
}

function nextWeekday(date, target, weekOffset = 0) {
  const current = weekdayOf(date);
  const thisWeek = addDays(date, target - current);
  return addDays(thisWeek, weekOffset * 7);
}

function parseDate(text, today) {
  let match = text.match(/(\d{4})-(\d{1,2})-(\d{1,2})/);
  if (match) return { date: `${match[1]}-${pad(match[2])}-${pad(match[3])}`, span: match[0] };
  match = text.match(/(\d{1,2})[月/](\d{1,2})[号日]?/);
  if (match) return { date: monthDay(today, Number(match[1]), Number(match[2])), span: match[0] };
  // "16号" in speech rarely carries the month; it means the coming 16th.
  match = text.match(/(\d{1,2})\s*[号日](?!\s*[点时分])/);
  if (match) {
    const day = Number(match[1]);
    if (day >= 1 && day <= 31) return { date: monthDay(today, Number(today.slice(5, 7)), day), span: match[0] };
  }
  // "1.5小时" is a duration, not January 5.
  match = text.match(/(\d{1,2})\.(\d{1,2})(?!\d)(?!\s*(?:个)?\s*(?:小时|钟头|分钟|分|min|h|hr))/);
  if (match) {
    const month = Number(match[1]);
    const day = Number(match[2]);
    if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
      return { date: monthDay(today, month, day), span: match[0] };
    }
  }
  match = text.match(/(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天])/);
  if (match) {
    const offset = match[1] === '下下' ? 2 : match[1] === '下' ? 1 : 0;
    let date = nextWeekday(today, WEEKDAY[match[2]], offset);
    // A weekday without 下 that has already passed means the coming one, so
    // "这周六" said on Sunday lands on next Saturday instead of yesterday.
    if (match[1] !== '下' && match[1] !== '下下' && date < today) date = addDays(date, 7);
    return { date, span: match[0] };
  }
  const words = [
    ['大后天', 3],
    ['后天', 2],
    ['明晚', 1],
    ['明早', 1],
    ['明天', 1],
    ['明日', 1],
    ['明儿', 1],
    ['今晚', 0],
    ['今早', 0],
    ['今天', 0],
    ['今日', 0],
    ['昨天', -1],
    ['昨日', -1],
  ];
  for (const [word, delta] of words) {
    if (text.includes(word)) return { date: addDays(today, delta), span: word };
  }
  if (text.includes('月底')) return { date: endOfMonth(today), span: '月底' };
  if (text.includes('下个月')) return { date: null, span: '下个月', unsupported: true };
  return null;
}

function parseRecurrence(text) {
  const range = text.match(/(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天])\s*(?:到|至|-|~)\s*(?:周|星期|礼拜)?([一二三四五六日天])/);
  if (range) {
    const start = WEEKDAY[range[2]];
    const end = WEEKDAY[range[3]];
    const offset = range[1] === '下下' ? 2 : range[1] === '下' ? 1 : 0;
    const weekdays = [];
    for (let day = start; day <= end; day += 1) weekdays.push(day);
    if (!weekdays.length) weekdays.push(start);
    return { weekdays, span: range[0], weekOffset: offset };
  }
  let match = text.match(/每(?:周|星期|礼拜)(?:周|星期|礼拜)?([一二三四五六日天]+)/);
  if (match) {
    const weekdays = [...new Set([...match[1]].map((ch) => WEEKDAY[ch]).filter(Boolean))];
    return { weekdays: weekdays.sort((a, b) => a - b), span: match[0] };
  }
  // "周二和周四晚上" is a weekly pair even without 每.
  const pair = text.match(/(?:周|星期|礼拜)([一二三四五六日天])\s*(?:和|与|及|、|,)\s*(?:周|星期|礼拜)?([一二三四五六日天])/);
  if (pair) {
    const weekdays = [...new Set([WEEKDAY[pair[1]], WEEKDAY[pair[2]]].filter(Boolean))].sort((a, b) => a - b);
    return { weekdays, span: pair[0] };
  }
  match = text.match(/每(?:周|星期|礼拜)([1-7,，、和及]+)/);
  if (match) {
    const weekdays = [...new Set(match[1].split(/[,，、和及]/).map(Number).filter((n) => n >= 1 && n <= 7))];
    return { weekdays: weekdays.sort((a, b) => a - b), span: match[0] };
  }
  if (/每天|每日|天天/.test(text)) {
    return { weekdays: [1, 2, 3, 4, 5, 6, 7], span: text.match(/每天|每日|天天/)[0] };
  }
  if (/工作日|周一到周五|星期一到星期五|周一至周五/.test(text)) {
    return { weekdays: [1, 2, 3, 4, 5], span: text.match(/工作日|周一到周五|星期一到星期五|周一至周五/)[0] };
  }
  if (/周末/.test(text)) return { weekdays: [6, 7], span: '周末' };
  if (/隔天|隔日|每隔一天|每两天|每月|每个月|每周两次|一周两次|一周三次/.test(text)) {
    return { weekdays: null, span: text.match(/隔天|隔日|每隔一天|每两天|每月|每个月|每周两次|一周两次|一周三次/)[0], unsupported: true };
  }
  return null;
}

function applyPeriod(hour, period) {
  if (period === '凌晨' || period === '早上' || period === '早晨' || period === '上午') return hour;
  if (period === '中午') return hour < 12 ? hour + 12 : hour;
  if (period === '下午' || period === '傍晚') return hour < 12 ? hour + 12 : hour;
  if (period === '晚上' || period === '夜里' || period === '今晚' || period === '明晚') {
    if (hour === 12) return 0;
    return hour < 12 ? hour + 12 : hour;
  }
  return hour;
}

function parseClockPart(rawHour, rawMinute, rawPeriod) {
  let hour = cnToNumber(rawHour);
  if (hour == null || hour > 23) return null;
  let minute = rawMinute == null ? 0 : cnToNumber(rawMinute);
  if (rawMinute === '半') minute = 30;
  if (rawMinute === '一刻') minute = 15;
  if (rawMinute === '三刻') minute = 45;
  if (minute == null || minute > 59) return null;
  hour = applyPeriod(hour, rawPeriod);
  return { minute: hour * 60 + minute, hour, rawPeriod };
}

function parseTimes(text) {
  const clock = '(\\d{1,2}|[一二两三四五六七八九十]{1,3}|半)';
  const minute = '(\\d{1,2}|[一二两三四五六七八九十]{1,3}|半|一刻|三刻)';
  const period = '(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|今晚|明晚)?';
  const rangeRe = new RegExp(`${period}\\s*${clock}\\s*(?:点|时|:)\\s*${minute}?分?\\s*(?:到|至|-|—|~|–)\\s*${period}\\s*${clock}\\s*(?:点|时|:)\\s*${minute}?分?`);
  const range = text.match(rangeRe);
  if (range) {
    const start = parseClockPart(range[2], range[3], range[1]);
    const end = parseClockPart(range[5], range[6], range[4] || range[1]);
    if (start && end) {
      let endMinute = end.minute;
      let crossMidnight = false;
      if (endMinute <= start.minute) {
        endMinute += 24 * 60;
        crossMidnight = true;
      }
      return {
        startMinute: start.minute,
        endMinute,
        durationMinutes: endMinute - start.minute,
        crossMidnight,
        span: range[0],
        startAmbiguous: !range[1] && start.hour <= 6,
      };
    }
  }
  const singleRe = new RegExp(`${period}\\s*${clock}\\s*(?:点|时|:)\\s*${minute}?分?`);
  const single = text.match(singleRe);
  if (single) {
    const clockValue = parseClockPart(single[2], single[3], single[1]);
    if (clockValue) {
      let endMinute = null;
      let durationMinutes = null;
      let endSpan = null;
      let crossMidnight = false;
      const tail = text.slice((single.index || 0) + single[0].length);
      // "写到五点" closes the block; "持续到/直到" is the same relation.
      const continued = tail.match(new RegExp(`(?:写到|做到|干到|学到|练到|开到|打到|跑到|看到|读到|背到|玩到|用到|持续到|一直到|直到)\\s*${period}\\s*${clock}\\s*(?:点|时)\\s*${minute}?分?`));
      if (continued) {
        const end = parseClockPart(continued[2], continued[3], continued[1] || single[1]);
        if (end) {
          endMinute = end.minute;
          if (endMinute <= clockValue.minute) {
            endMinute += 24 * 60;
            crossMidnight = true;
          }
          durationMinutes = endMinute - clockValue.minute;
          endSpan = continued[0];
        }
      }
      return {
        startMinute: clockValue.minute,
        endMinute,
        durationMinutes,
        crossMidnight,
        span: single[0],
        endSpan,
        startAmbiguous: !single[1] && clockValue.hour <= 6,
      };
    }
  }
  return null;
}

const BARE_PERIOD_MINUTE = {
  凌晨: 5 * 60,
  早上: 7 * 60,
  早晨: 7 * 60,
  中午: 12 * 60,
  傍晚: 18 * 60,
  晚上: 20 * 60,
  夜里: 22 * 60,
  今晚: 20 * 60,
  明晚: 20 * 60,
};

// A bare period ("改明天中午") still names a slot; 上午/下午 are left to the model.
function parseBarePeriod(text) {
  for (const [word, minute] of Object.entries(BARE_PERIOD_MINUTE)) {
    if (text.includes(word)) return { startMinute: minute, span: word, barePeriod: true };
  }
  return null;
}

function parseDuration(text) {
  let match = text.match(/(\d+(?:\.\d+)?)\s*(?:个)?\s*(?:小时|钟头|h|hr|hours?)/i);
  if (match) return { minutes: Math.round(Number(match[1]) * 60), span: match[0] };
  match = text.match(/(半|一|两|俩|二|三|四|五|六|七|八|九|十)\s*个?\s*半\s*(?:小时|钟头)/);
  if (match) return { minutes: Math.round((cnToNumber(match[1]) + 0.5) * 60), span: match[0] };
  match = text.match(/(半|一|两|俩|二|三|四|五|六|七|八|九|十)\s*个?\s*(?:小时|钟头)/);
  if (match) return { minutes: Math.round((cnToNumber(match[1]) || 0) * 60), span: match[0] };
  match = text.match(/(\d+)\s*(?:分钟|min|minutes?)/i);
  if (match) return { minutes: Number(match[1]), span: match[0] };
  match = text.match(/([一二两俩三四五六七八九十]{1,3})\s*(?:分钟|分)(?!之)/);
  if (match) return { minutes: Math.round((cnToNumber(match[1]) || 0) * (match[1] === '半' ? 30 : 1)), span: match[0] };
  match = text.match(/(半)\s*(?:分钟|分)/);
  if (match) return { minutes: 30, span: match[0] };
  match = text.match(/(\d+)\s*(?:天|日)/);
  if (match) return { minutes: null, days: Number(match[1]), span: match[0] };
  return null;
}

function parseDue(text) {
  const dueCue = /之前|以前|截止|截至|前把|前要|前交|前完成|前做|点(?:半)?前/.test(text);
  if (!dueCue) return null;
  return { cue: true, clockBefore: /点(?:半)?前/.test(text) };
}

function stripName(text, spans) {
  let value = text;
  for (const span of spans.filter(Boolean).sort((a, b) => b.length - a.length)) {
    value = value.split(span).join(' ');
  }
  value = value
    .replace(/^(帮|帮我|麻烦|请|我想|我要|想要|给我|我|能不能|可以|顺便|然后|再|就|先|只|从|开始|得)\s*/g, '')
    .replace(/(帮我|麻烦|谢谢|记一下|就行|一下|大概|大约|差不多|左右|看着办|你看着排|你安排|合适时间|挑个合适时间|看着空档|看着空挡|和合适时间|排成|别排事|别排了|别排|不用排|不要排|晚饭后|早饭后|饭后|起床后|下课后|睡前|考完|吃完|回家后|找个时间|找时间|抽个时间|抽时间|最近|开始|再往后拖两天|再往后拖|往后拖|拖两天|拖几天|持续到|一直到|直到|之前|以前|截止|截至)/g, ' ')
    .replace(/(没做完|没完成|没搞完|只写了一半|写了一半|只做了一半|做了一半|只完成了一半|完成了一半|做到一半|再给我|给我|额外|只给我)/g, ' ')
    .replace(/没/g, ' ')
    .replace(/(今天|今日|明天|明日|后天|大后天|昨天|昨日|下下周|下周|下星期|下礼拜|本周|这周|这星期|这礼拜)/g, ' ')
    .replace(/(安排|排一下|排|放到|放进|放在|挪到|移到|改到|换到|调到|调整到|提前到|推迟到|顺延到|延到|记一下|记下|记着|先记|只记|取消|完成|做完|写完|搞定|交了|做到|进度|补上|往后推|顺延|推迟|改天)/g, ' ')
    .replace(/(把|的|了|吧|呗|啊|呀|呢|吗|的话|这个|那个|这件事)/g, ' ')
    .replace(/(写|做|看|背|买|练|读|听|跑|吃|交|开|打|学|记|取|洗|修|改|约|订|寄|发)个/g, '$1')
    .replace(/(哦不|不对|说错了|而是|不是)/g, ' ')
    .replace(/[,\s]+/g, ' ')
    .trim();
  value = value.replace(/^(和|从|再|成)+/, '').replace(/(和|成|放|再|前)+$/, '');
  value = value.replace(/^前\s*/, '');
  value = value.replace(/^[我你\s]+/, '').replace(/^在\s*/, '').replace(/^[我你\s]+/, '');
  if (/^[是为就再和从成放我你\s]+$/.test(value)) value = '';
  return value.replace(/^[:\s]+|[:\s]+$/g, '');
}

const BA_VERBS = '收拾|整理|写|交|发|送|取|拿|买|洗|修|改|做|背|看|读|练|跑|开|打|准备|完成|处理|提交|打印|寄|还|带|装|挂|订|约|复习';

// "把周报发出去" / "把房间收拾一下": the object follows the verb in a name.
function baObjectName(text) {
  const match = text.match(new RegExp(`把\\s*([^,，。；;！？!?]{1,16}?)\\s*(${BA_VERBS})(?:一下|一遍|了|好|完|出去|过去|上去|下来|回来|给|到)?`));
  if (!match) return null;
  const object = match[1].replace(/^(明天|今天|后天|这周|下周|上午|下午|晚上|中午|的|我|你)+/g, '').trim();
  return object ? `${match[2]}${object}` : null;
}

function parseProgress(text) {
  const fraction = text.match(/([一二两三四五六七八九十]{1,2})\s*分之\s*([一二两三四五六七八九十])/);
  if (fraction) {
    const denominator = cnToNumber(fraction[1]);
    const numerator = cnToNumber(fraction[2]);
    if (denominator && numerator != null && numerator <= denominator) {
      return Math.round((numerator / denominator) * 100);
    }
  }
  if (/一半|50%|百分之五十/.test(text)) return 50;
  return null;
}

function findContextTask(text, tasks) {
  const matches = (tasks || [])
    .filter((task) => task?.name)
    .filter((task) => text.includes(task.name))
    .sort((a, b) => b.name.length - a.name.length);
  if (matches[0]) return matches[0];
  // Spoken Chinese reverses compounds ("蓝桥杯报名" for the task 报名蓝桥杯);
  // requiring every character keeps this from matching unrelated tasks.
  const reordered = (tasks || [])
    .filter((task) => task?.name && [...task.name].length >= 3)
    .filter((task) => [...task.name].every((ch) => text.includes(ch)))
    .sort((a, b) => b.name.length - a.name.length);
  return reordered[0] || null;
}

function typeDefaultMinutes(name) {
  if (/报告|作业|周报|PPT/i.test(name)) return 60;
  if (/组会|开会|会议|讨论/.test(name)) return 60;
  if (/快递|盖章|教材|买/.test(name)) return 30;
  if (/跑|背|练|冥想/.test(name)) return 30;
  if (/复习|准备|比赛|答辩/.test(name)) return 90;
  return 60;
}

function hasAny(text, words) {
  return words.some((word) => text.includes(word));
}

const CORRECTION = ['不是', '哦不', '不对', '而是', '说错了'];
const CONDITIONAL = ['如果', '要是', '除非', '否则', '万一'];
const PRONOUN = ['那个', '这个', '它', '刚才', '之前那个', '第一个'];
const ANCHOR = ['晚饭后', '早饭后', '起床后', '下课后', '睡前', '考完', '吃完', '回家后'];
const QUANTITY = ['两章', '三章', '一套', '两套', '三套', '两篇', '三篇', '两节', '三节'];
const TEAM = ['队友', '组员', '他们', '她们', '小王', '小李', '老师'];
const MULTI = ['然后', '顺便', '同时', '接着', '再'];
const QUERY = ['有空吗', '还剩多少', '还有多少', '有多少时间', '今天有什么', '安排是什么', '来得及', '先做什么', '该不该', '怎么办', '太累', '不想学', '休息'];
const SPREAD = ['排松', '太紧', '别排太满', '排得太满', '别排那么紧', '留点缓冲', '留半小时', '均匀'];

/**
 * The single source of truth for "what did this sentence say".
 * Returns the parsed draft plus why a model call is still warranted:
 * needsAI is soft (a draft exists), mustAsk is hard (the draft must not execute).
 */
export function parseRule(utterance, context = {}) {
  const today = context.today || '2026-11-15';
  const text = normalize(utterance);
  const tasks = context.tasks || [];
  const reasons = [];
  const hardReasons = [];
  const wantsAI = (reason) => reasons.push(reason);

  if (!text) {
    return { intent: 'clarify', items: [], draftItems: [], needsAI: true, mustAsk: true, reasons: ['empty'], confidence: 0 };
  }

  const DIRECT_QUERY = /还剩(?:几|多少)|还有(?:什么|哪些|多少)(?:没|要|需要|待)?(?:做|完成|干)?|好累|不想动|先(?:做|干|安排|写|学)哪|哪个.{0,3}先|怎么(?:安排|排|规划).{0,4}(?:比较|才)?好|来不来得及|该不该|要不要/;
  if (DIRECT_QUERY.test(text) && !/记一下|记着|提醒我|别忘/.test(text)) {
    return { intent: 'query', items: [], draftItems: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.7 };
  }

  if (hasAny(text, SPREAD)) {
    return { intent: 'query', items: [], draftItems: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.7, gapMinutes: 30 };
  }

  if (/不排事|不想排/.test(text) && /休息|放松|歇/.test(text)) {
    return { intent: 'query', items: [], draftItems: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.7 };
  }

  const hasActionVerb = /安排|排|放到|放进|放在|挪|改到|移到|换到|取消|完成|做完|写完|搞定|顺延|推迟|记一下|记着|先记|只记|补上|没做完|没完成|没搞完|开始/.test(text);
  const hasPlanSignal = /写|开|复习|准备|背|跑|练|取|买|交|做|排|安排|题|报告|作业/.test(text);
  if (!hasActionVerb && !hasPlanSignal && hasAny(text, QUERY)) {
    return { intent: 'query', items: [], draftItems: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.7 };
  }
  if (!hasActionVerb && !hasPlanSignal && /[?？]|吗$|多少|什么|哪|怎么|为什么/.test(text)) {
    return { intent: 'query', items: [], draftItems: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.6 };
  }

  if (hasAny(text, CORRECTION)) wantsAI('correction');
  if (hasAny(text, CONDITIONAL)) {
    wantsAI('conditional');
    hardReasons.push('conditional');
  }
  if (hasAny(text, PRONOUN)) {
    wantsAI('pronoun');
    hardReasons.push('pronoun');
  }
  if (/下周末|下个周末/.test(text)) {
    wantsAI('ambiguous-weekend');
    hardReasons.push('ambiguous-weekend');
  }
  if (hasAny(text, ANCHOR)) wantsAI('relative-anchor');
  if (hasAny(text, QUANTITY)) wantsAI('quantity');
  if (hasAny(text, TEAM)) wantsAI('people');
  const hasEnglish = /[A-Za-z]{3,}/.test(text.replace(/\b(PPT|min|h|hr|pm|am)\b/gi, ''));
  if (hasEnglish) wantsAI('english');

  const recurrence = parseRecurrence(text);
  if (recurrence?.unsupported) {
    wantsAI('unsupported-recurrence');
    hardReasons.push('unsupported-recurrence');
  }

  // An explicit move target and a spoken self-correction both override the first
  // date/time that appears in the text.
  const correction = text.match(/(?:哦不|不对|说错了)[，,]?\s*(?:是)?\s*(.*)$/) ||
    text.match(/不是([^，,]*)[，,]\s*(?:是)?\s*(.*)$/);
  const correctedText = correction ? (correction[2] ?? correction[1] ?? '') : null;
  const correctedSource = correction && correction.length > 2 ? correction[1] : null;
  const moveTarget = text.match(/(?:挪到|移到|改到|换到|调到|调整到|提前到|推迟到|推后到|顺延到|延到)(.*)$/);
  // "改明天中午" names the new day but no clock, so the period itself is the slot.
  const bareTargetTime = moveTarget ? parseBarePeriod(moveTarget[1]) : null;
  const bareTime = !hasAny(text, ANCHOR) && !/\d|点|时|:/.test(text) ? parseBarePeriod(text) : null;

  const fallbackDate = parseDate(text, today);
  const fallbackTime = parseTimes(text);
  const targetDate = moveTarget ? parseDate(moveTarget[1], today) : null;
  const targetTime = moveTarget ? parseTimes(moveTarget[1]) || bareTargetTime : null;
  const correctedDate = correctedText ? parseDate(correctedText, today) : null;
  let correctedTime = correctedText ? parseTimes(correctedText) : null;
  // "哦不，是四点" inherits 下午 from "下午三点".
  if (correctedTime?.startAmbiguous && fallbackTime && fallbackTime.startMinute >= 12 * 60) {
    correctedTime = { ...correctedTime, startMinute: correctedTime.startMinute + 12 * 60, startAmbiguous: false };
  }
  const dateHit = correctedDate || targetDate || fallbackDate;
  const timeHit = correctedTime || targetTime || fallbackTime || bareTime;
  if (dateHit?.unsupported) {
    wantsAI('relative-month');
    hardReasons.push('relative-month');
  }
  if (timeHit?.crossMidnight) {
    wantsAI('cross-midnight');
    hardReasons.push('cross-midnight');
  }
  if (timeHit?.startAmbiguous) wantsAI('ambiguous-clock');
  const durationHit = parseDuration(text);
  if (durationHit?.days) wantsAI('multi-day');

  if (/课/.test(text) && /取消|改到|挪|停|调课/.test(text)) {
    wantsAI('fixed-block');
    hardReasons.push('fixed-block');
  }
  if (/(这周|本周|下周)/.test(text) && /(都|全部|所有)/.test(text) && /(挪|推|移|调)/.test(text)) {
    wantsAI('bulk-move');
    hardReasons.push('bulk-move');
  }
  if (/看着办|随你|随便|你定|都行/.test(text)) {
    wantsAI('ambiguous');
    hardReasons.push('ambiguous');
  }
  if (/排/.test(text) && /下周|这周|本周/.test(text) && !dateHit && !recurrence) {
    wantsAI('ambiguous');
    hardReasons.push('ambiguous');
  }
  if (hasEnglish && !dateHit && !recurrence) {
    hardReasons.push('english-intent');
  }

  const contextTask = findContextTask(text, tasks);
  const due = parseDue(text);
  const captureCue = /记一下|记着|先记|只记|别排|不用排/.test(text);
  const doneCue = /做完|写完|搞定|交了|完成了|已经.*完|吃完(?!饭)|看完|读完|背完|跑完|练完|开完|学完|弄完|干完|搞完|复习完|交完|发完|买完|收拾完|整理完|画完|改完|编完|洗完了|准备好了|交上去了|发出去了|考完了/.test(text);
  const progressCue = /没做完|没完成|没搞完|没搞定|没写完|没背完|没练完|没看完|没学完|没交|没写|只写了一半|写了一半|做了一半|只完成了一半|完成了一半|做到一半|到一半|一半|分之|写了个开头|写个开头|开了个头|只写|只做|只复习|补上|进度/.test(text);
  const postponeCue = /顺延|往后推|往后拖|再拖|拖几天|拖两天|拖一天|推迟|改天|推后到/.test(text);
  const moveCue = /挪到|移到|改到|换到|调到|调整到|提前到|推迟到|推后到|顺延到|延到/;
  const placeCue = /放到|放进|放在/;
  const busyBlock = Boolean(timeHit?.endMinute) && /别排事|别排了|不要排/.test(text);
  const targetHasClock = Boolean(targetTime?.startMinute != null);

  console.error("DBG cues", {busyBlock, cancel:/取消|不做了|别做|算了|不开了|不上了|不去了/.test(text), postponeCue, progressCue, doneCue, due:due?.cue, move:moveCue.test(text), place:placeCue, rec:Boolean(recurrence), capture:captureCue});
  let action = 'create';
  if (busyBlock) action = 'busy';
  else if (/取消|不做了|别做|算了|不开了|不上了|不去了/.test(text)) action = 'cancel';
  else if (postponeCue) action = targetHasClock ? 'schedule' : 'postpone';
  else if (progressCue) action = 'progress';
  else if (doneCue && !due?.cue) action = 'complete';
  else if (moveCue.test(text)) action = 'schedule';
  else if (placeCue) action = contextTask ? 'schedule' : 'create';
  else if (recurrence && !recurrence.unsupported) action = 'routine';
  else if (captureCue) action = 'create';
  else if (contextTask && (dateHit || timeHit)) action = 'schedule';

  const clauses = text.split(/[，,]/).map((clause) => clause.trim()).filter(Boolean);
  const clauseSignals = clauses.filter((clause) => Boolean(parseDate(clause, today) || parseTimes(clause) || parseBarePeriod(clause))).length;
  const actionClauses = clauses.filter((clause) => /安排|排|挪|改到|移到|换到|调到|取消|完成|做完|写完|记一下|记着|复习|写|开|取|买|跑|背|练|准备|交|做|看|读|整理|收拾|打|约|送|发/.test(clause)).length;
  const multiCue = /然后|顺便|同时|接着|随后|之后/.test(text);
  if (clauses.length >= 2 && actionClauses >= 2 && (multiCue || clauseSignals >= 2)) {
    wantsAI('multi-intent');
    hardReasons.push('multi-intent');
  }

  console.error("DBG action", action);
  const spans = [
    fallbackDate?.span,
    fallbackTime?.span,
    targetDate?.span,
    targetTime?.span,
    correctedDate?.span,
    correctedTime?.span,
    correctedSource,
    durationHit?.span,
    recurrence?.span,
    timeHit?.endSpan,
    bareTime?.span,
  ].filter(Boolean);
  let name = contextTask?.name || baObjectName(text) || stripName(text, spans);
  if (!name) {
    wantsAI('missing-name');
    hardReasons.push('missing-name');
  }

  const items = [];
  if (action === 'busy') {
    items.push({
      action: 'busy',
      name,
      date: dateHit?.date ?? today,
      startMinute: timeHit?.startMinute ?? null,
      endMinute: timeHit?.endMinute ?? null,
      durationMinutes: timeHit?.durationMinutes ?? null,
    });
  } else if (action === 'routine') {
    items.push({
      action: 'routine',
      name,
      date: null,
      startMinute: timeHit?.startMinute ?? null,
      endMinute: timeHit?.endMinute ?? null,
      durationMinutes: timeHit?.durationMinutes ?? durationHit?.minutes ?? (recurrence ? typeDefaultMinutes(name) : null),
      weekdays: recurrence?.weekdays || null,
    });
  } else if (action === 'schedule') {
    items.push({
      action: 'schedule',
      name,
      date: dateHit?.date ?? null,
      startMinute: timeHit?.startMinute ?? null,
      endMinute: timeHit?.endMinute ?? null,
      durationMinutes: timeHit?.durationMinutes ?? durationHit?.minutes ?? contextTask?.durationMinutes ?? typeDefaultMinutes(name),
      move: true,
    });
  } else if (action === 'cancel') {
    items.push({ action, name, date: null, startMinute: null, durationMinutes: null });
  } else if (action === 'complete') {
    const extra = /多花|多用|超时/.test(text)
      ? (contextTask?.durationMinutes ?? typeDefaultMinutes(name)) + (durationHit?.minutes || 0)
      : null;
    items.push({
      action,
      name,
      date: null,
      startMinute: null,
      durationMinutes: null,
      ...(extra != null ? { actualMinutes: extra } : {}),
    });
  } else if (action === 'postpone') {
    let date = dateHit?.date ?? null;
    const base = contextTask?.date || null;
    const drag = text.match(/(?:再)?(?:往后)?(?:拖|推)([一二两三四五六七八九十\d]+)天/);
    if (!date && base && drag) {
      const days = cnToNumber(drag[1]);
      if (days != null) date = addDays(base, days);
    }
    if (!date && base && /一天/.test(text)) date = addDays(base, 1);
    items.push({ action, name, date, startMinute: null, durationMinutes: null });
  } else if (action === 'progress') {
    const progress = parseProgress(text);
    items.push({ action, name, date: null, startMinute: null, durationMinutes: null, progress });
    if (dateHit?.date) {
      items.push({
        action: 'create',
        name,
        date: dateHit.date,
        startMinute: timeHit?.startMinute ?? null,
        durationMinutes: timeHit?.durationMinutes ?? durationHit?.minutes ?? null,
      });
    }
  } else if (due?.cue && (dateHit?.date || due.clockBefore)) {
    const dueDate = dateHit?.date ?? (due.clockBefore ? today : null);
    const dueMinute = due.clockBefore && timeHit?.startMinute != null ? timeHit.startMinute : null;
    items.push({
      action: 'create',
      name,
      // An explicit day plus a clock deadline is a deadline, not a placed block.
      date: due.clockBefore && !dateHit?.date ? dueDate : null,
      dueDate,
      dueMinute,
      startMinute: null,
      durationMinutes: durationHit?.minutes ?? null,
    });
  } else {
    items.push({
      action: 'create',
      name,
      date: dateHit?.date ?? null,
      startMinute: timeHit?.startMinute ?? null,
      endMinute: timeHit?.endMinute ?? null,
      durationMinutes: timeHit?.durationMinutes ?? durationHit?.minutes ?? (captureCue ? null : typeDefaultMinutes(name)),
    });
  }

  const mustAsk = hardReasons.length > 0;
  const hasSchedule = action === 'create' || action === 'schedule' || action === 'routine' || action === 'busy';
  const confidence = mustAsk ? 0.2 : reasons.length ? 0.55 : hasSchedule && (dateHit || recurrence) && (timeHit || action === 'create') ? 0.95 : 0.75;
  return {
    intent: mustAsk ? 'clarify' : 'plan',
    items: mustAsk ? [] : items,
    draftItems: items,
    needsAI: mustAsk || reasons.length > 0,
    mustAsk,
    reasons: [...new Set([...hardReasons, ...reasons])],
    confidence,
    spans: { date: dateHit?.span, time: timeHit?.span, duration: durationHit?.span, recurrence: recurrence?.span },
  };
}

function adaptTasks(tasks) {
  return (tasks || [])
    .map((task) => ({
      id: task?.id || null,
      name: String(task?.name || '').trim(),
      durationMinutes: task?.duration_minutes ?? task?.durationMinutes ?? null,
      date: task?.planned_date ?? task?.plannedDate ?? task?.date ?? null,
    }))
    .filter((task) => task.name);
}

function toProposalItem(item, contextTask) {
  const base = {
    name: item.name,
    taskId: contextTask?.id || null,
    action: item.action,
    move: Boolean(item.move),
    durationMinutes: item.durationMinutes ?? null,
    date: item.date ?? null,
    start: item.startMinute ?? null,
    progress: item.progress ?? null,
    dueDate: item.dueDate ?? null,
    dueMinute: item.dueMinute ?? null,
    actualMinutes: item.actualMinutes ?? null,
    note: '',
  };
  if (item.action === 'busy') {
    // A one-off "don't schedule here" window is a single-date REST block.
    return {
      ...base,
      action: 'routine',
      date: item.date ?? null,
      start: item.startMinute ?? null,
      end: item.endMinute ?? null,
      weekdays: [],
      blockKind: 'REST',
      move: false,
    };
  }
  if (item.action === 'routine') {
    return {
      ...base,
      weekdays: Array.isArray(item.weekdays) ? item.weekdays : [],
      end: item.endMinute ?? null,
      blockKind: 'FIXED',
    };
  }
  return base;
}

function intentOf(items, rawIntent) {
  if (!items.length) return rawIntent === 'query' ? 'query' : 'clarify';
  return items.every((item) => STATUS_ACTIONS.has(item.action)) ? 'status' : 'plan';
}

export function buildRulePlan({ message, today, tasks = [] }) {
  const adapted = adaptTasks(tasks);
  const parsed = parseRule(message, { today, tasks: adapted });
  const contextTask = findContextTask(normalize(message), adapted);
  const draftItems = parsed.draftItems.map((item) => toProposalItem(item, contextTask));
  const items = parsed.mustAsk ? [] : draftItems;
  return {
    intent: intentOf(items, parsed.intent),
    items,
    draftItems,
    mustAsk: parsed.mustAsk,
    needsAI: parsed.needsAI,
    reasons: parsed.reasons,
    confidence: parsed.confidence,
    gapMinutes: parsed.gapMinutes ?? null,
  };
}

function canonicalName(name) {
  return String(name || '')
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[\s，,。.!！?？:：;；、"'“”‘’]/g, '');
}

function sameSet(a, b) {
  if (!Array.isArray(a) || !Array.isArray(b)) return false;
  const left = [...new Set(a)].sort((x, y) => x - y);
  const right = [...new Set(b)].sort((x, y) => x - y);
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

const REVIEW_CUE = /没做完|没完成|没搞完|没写|只写了一半|做到一半|补上/;
const EXTRA_CUE = /再(?:给我|排|安排|留)?\s*(?:[一二两三四五六七八九十百\d]+|半)?\s*个?\s*(?:小时|钟头|分钟|分|min)|再排|额外/;
const MOVE_CUE = /挪到|移到|改到|换到|调整到|提前到|推迟到|顺延到|延到/;

/**
 * The model reads the sentence; this function decides the calendar and the
 * protocol. Review keeps the original block and adds extra time; postpone keeps
 * the clock and moves the day; recurrence wording can never collapse to one day.
 */
export function applyAiOverrides(aiItems, rulePlan, message) {
  const items = (aiItems || []).map((item) => ({ ...item }));
  if (!items.length) return { items, changed: false, protocol: null };
  const draft = rulePlan?.draftItems || [];
  const ruleItem = draft[0] || null;
  if (!ruleItem) return { items, changed: false, protocol: null };
  // A hard trigger means the local reading is not safe to execute, so the model's
  // items stand untouched; only its prose may need the focused question.
  if (rulePlan.mustAsk) return { items, changed: false, protocol: null };

  // Review: "没做完，明天再给我四十分钟" is progress + an extra block, never a move.
  // "今天补上" carries its extra day in the rule draft even without a duration.
  const ruleExtra = draft.slice(1).find((item) => item.date || item.start != null);
  const reviewTurn = REVIEW_CUE.test(message) &&
    (EXTRA_CUE.test(message) || ruleExtra || (ruleItem.action === 'progress' && draft.length > 1));
  if (reviewTurn) {
    const aiOne = items[0];
    if (ruleExtra) {
      const merged = draft.map((item) => ({
        ...item,
        name: aiOne?.name || item.name,
        taskId: item.taskId || aiOne?.taskId || null,
      }));
      return { items: merged, changed: true, protocol: 'review' };
    }
    const aiExtra = items.find((item) => (item.action === 'create' || item.action === 'schedule') && (item.date || item.start != null));
    if (ruleItem.action === 'progress' && !aiExtra) {
      const merged = draft.map((item) => ({
        ...item,
        name: aiOne?.name || item.name,
        taskId: item.taskId || aiOne?.taskId || null,
      }));
      return { items: merged, changed: true, protocol: 'review' };
    }
    if (aiExtra) {
      const progress = {
        name: aiExtra.name,
        taskId: aiExtra.taskId || null,
        action: 'progress',
        move: false,
        progress: null,
        date: null,
        start: null,
        durationMinutes: null,
      };
      const add = { ...aiExtra, action: aiExtra.taskId ? 'schedule' : 'create', move: false };
      return { items: [progress, add], changed: true, protocol: 'review' };
    }
  }

  // Postpone: "往后推一天" moves the day but keeps the clock. The day is calendar
  // arithmetic, so the deterministic parse wins over the model's guess.
  if (ruleItem.action === 'postpone') {
    const target = items.find((item) => ['schedule', 'create', 'postpone'].includes(item.action));
    if (target) {
      target.action = 'postpone';
      target.move = false;
      if (ruleItem.date) target.date = ruleItem.date;
      return { items, changed: true, protocol: 'postpone' };
    }
  }

  // "改到周四四点" is a move even when the model read it as a brand-new block.
  if (!rulePlan.mustAsk && ruleItem.action === 'schedule' && MOVE_CUE.test(message)) {
    let moved = false;
    for (const item of items) {
      if (item.action !== 'create' && item.action !== 'schedule') continue;
      item.action = 'schedule';
      item.move = true;
      moved = true;
    }
    if (moved) {
      const slots = applySlots(items, draft, ruleItem);
      return { items: slots.items, changed: true, protocol: 'move' };
    }
  }

  // Recurrence wording is grammar, not a judgment call: 每天/工作日/周末/每周X
  // must survive even when the model reads the sentence as a single day.
  if (ruleItem.action === 'routine' && items.length === 1 && items[0].action !== 'routine') {
    const only = items[0];
    only.action = 'routine';
    only.date = null;
    only.move = false;
    if (Array.isArray(ruleItem.weekdays) && ruleItem.weekdays.length) only.weekdays = ruleItem.weekdays;
    if (only.durationMinutes == null && ruleItem.durationMinutes != null) only.durationMinutes = ruleItem.durationMinutes;
    if (only.start == null && ruleItem.start != null) only.start = ruleItem.start;
    return { items, changed: true, protocol: 'recurrence' };
  }

  const slots = applySlots(items, draft, ruleItem);
  return { items: slots.items, changed: slots.changed, protocol: null };
}

// Calendar slots come from the sentence, matched to the model's items by name.
// A 1:1 turn can still align when the model paraphrased the name.
function applySlots(items, draft, ruleItem) {
  let changed = false;
  for (const item of items) {
    if (!['create', 'schedule', 'postpone', 'routine'].includes(item.action)) continue;
    let source = draft.find((candidate) => canonicalName(candidate.name) === canonicalName(item.name));
    if (!source && draft.length === 1 && items.length === 1) source = ruleItem;
    if (!source || STATUS_ACTIONS.has(source.action)) continue;
    if (item.action === 'routine') {
      if (Array.isArray(source.weekdays) && source.weekdays.length && !sameSet(source.weekdays, item.weekdays)) {
        item.weekdays = source.weekdays;
        changed = true;
      }
      if (source.start != null && item.start !== source.start) {
        item.start = source.start;
        changed = true;
      }
      if (source.durationMinutes != null && item.durationMinutes !== source.durationMinutes) {
        item.durationMinutes = source.durationMinutes;
        changed = true;
      }
      continue;
    }
    if (source.date && item.date !== source.date) {
      item.date = source.date;
      changed = true;
    }
    if (source.start != null && item.start !== source.start) {
      item.start = source.start;
      changed = true;
    }
    if (source.durationMinutes != null && item.durationMinutes !== source.durationMinutes) {
      item.durationMinutes = source.durationMinutes;
      changed = true;
    }
    if (source.dueDate && item.dueDate !== source.dueDate) {
      item.dueDate = source.dueDate;
      changed = true;
    }
    if (source.dueMinute != null && item.dueMinute !== source.dueMinute) {
      item.dueMinute = source.dueMinute;
      changed = true;
    }
  }
  return { items, changed };
}

export function ruleClarifyReply(reasons = []) {
  const has = (reason) => reasons.includes(reason);
  if (has('multi-intent')) return '这句里有两件事，我先记哪一件？拆成两句告诉我更稳。';
  if (has('conditional')) return '这件事带着条件，要我先按常规排上，还是等条件确认了再排？';
  if (has('pronoun')) return '你说的「那个」我还不确定是哪一项，能说一下名字吗？';
  if (has('unsupported-recurrence')) return '这种重复方式（隔天/每月）我还没法直接落到日程，先告诉我按哪几天排？';
  if (has('fixed-block')) return '课表这块我还没接上，先告诉我周几、几点到几点？';
  if (has('missing-name')) return '要给哪件事排时间？';
  if (has('bulk-move')) return '批量挪动我还没法直接执行，先说是哪几项、挪到哪天？';
  if (has('ambiguous')) return '这句我有两种理解：是要新增，还是改已有安排？';
  if (has('english-intent')) return '这句我不太确定要做什么，可以用中文说一下任务和时间吗？';
  if (has('cross-midnight')) return '这个时段跨天了，固定安排不能跨天；要拆成两段，还是换个结束时间？';
  if (has('relative-month')) return '「下个月」还没法直接落到具体日期，给我一个日子或「下月几号」？';
  if (has('people')) return '这件事还涉及别人，先确认下具体是哪项任务、安排在什么时候？';
  return '这句我还没把握，能补一下要做什么、什么时候吗？';
}
