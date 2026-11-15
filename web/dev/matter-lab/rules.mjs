// Deterministic baseline parser: the offline floor.
// It must return mustAI instead of guessing when the sentence leaves the grammar.

const CN_NUM = { 零: 0, 一: 1, 二: 2, 两: 2, 俩: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 };
const WEEKDAY = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 7, 天: 7 };

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
    // A bare weekday means the upcoming one; this week's may already have passed.
    if (!match[1] && date < today) date = addDays(date, 7);
    return { date, span: match[0] };
  }
  const words = [
    ['大后天', 3],
    ['后天', 2],
    ['明天', 1],
    ['明日', 1],
    ['明儿', 1],
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
  const period = '(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|今晚|明晚)?';
  const rangeRe = new RegExp(`${period}\\s*${clock}\\s*(?:点|时|:)\\s*(\\d{1,2}|半|一刻|三刻)?分?\\s*(?:到|至|-|—|~|–)\\s*${period}\\s*${clock}\\s*(?:点|时|:)\\s*(\\d{1,2}|半|一刻|三刻)?分?`);
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
  const singleRe = new RegExp(`${period}\\s*${clock}\\s*(?:点|时|:)\\s*(\\d{1,2}|半|一刻|三刻)?分?`);
  const single = text.match(singleRe);
  if (single) {
    const clockValue = parseClockPart(single[2], single[3], single[1]);
    if (clockValue) {
      let endMinute = null;
      let durationMinutes = null;
      let crossMidnight = false;
      const tail = text.slice((single.index || 0) + single[0].length);
      const continued = tail.match(new RegExp(`(?:持续到|一直到|直到)\\s*${period}\\s*${clock}\\s*(?:点|时)\\s*(\\d{1,2}|半|一刻|三刻)?分?`));
      if (continued) {
        const end = parseClockPart(continued[2], continued[3], continued[1] || single[1]);
        if (end) {
          endMinute = end.minute;
          if (endMinute <= clockValue.minute) {
            endMinute += 24 * 60;
            crossMidnight = true;
          }
          durationMinutes = endMinute - clockValue.minute;
        }
      }
      return {
        startMinute: clockValue.minute,
        endMinute,
        durationMinutes,
        crossMidnight,
        span: single[0],
        startAmbiguous: !single[1] && clockValue.hour <= 6,
      };
    }
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
  match = text.match(/([一二两俩三四五六七八九十]{1,3})\s*(?:分钟|分)/);
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
    .replace(/^(帮|帮我|麻烦|请|我想|我要|想要|给我|我|能不能|可以|顺便|然后|再|就|先|只|从)\s*/g, '')
    .replace(/(帮我|麻烦|谢谢|就行|一下|大概|大约|差不多|左右|看着办|你看着排|你安排|合适时间|挑个合适时间|看着空档|看着空挡|和合适时间|排成|别排事|别排了|别排|不用排|不要排|晚饭后|早饭后|起床后|下课后|睡前|考完|吃完|回家后|持续到|一直到|直到|之前|以前|截止|截至)/g, ' ')
    .replace(/(没做完|没完成|没搞完|只写了一半|写了一半|只做了一半|做了一半|只完成了一半|完成了一半|做到一半|再给我|给我|额外|只给我)/g, ' ')
    .replace(/没/g, ' ')
    .replace(/(今天|今日|明天|明日|后天|大后天|昨天|昨日|下下周|下周|下星期|下礼拜|本周|这周|这星期|这礼拜)/g, ' ')
    .replace(/(安排|排一下|排|放到|放进|放在|挪到|移到|改到|换到|调整到|提前到|推迟到|顺延到|延到|记一下|记下|记着|先记|只记|取消|完成|做完|写完|搞定|交了|做到|进度|补上|往后推|顺延|推迟|改天)/g, ' ')
    .replace(/(把|的|了|吧|呗|啊|呀|呢|吗|的话|这个|那个|这件事)/g, ' ')
    .replace(/(哦不|不对|说错了|而是|不是)/g, ' ')
    .replace(/[,\s]+/g, ' ')
    .trim();
  value = value.replace(/^(和|从|再|成)+/, '').replace(/(和|成|放|再|前)+$/, '');
  value = value.replace(/^前\s*/, '');
  if (/^[是为就再和从成放\s]+$/.test(value)) value = '';
  return value.replace(/^[:\s]+|[:\s]+$/g, '');
}

function findContextTask(text, tasks) {
  const matches = (tasks || [])
    .filter((task) => task?.name)
    .filter((task) => text.includes(task.name))
    .sort((a, b) => b.name.length - a.name.length);
  return matches[0] || null;
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

export function parseRule(utterance, context = {}) {
  const today = context.today || '2026-11-15';
  const text = normalize(utterance);
  const tasks = context.tasks || [];
  const reasons = [];
  const hardReasons = [];
  const wantsAI = (reason) => reasons.push(reason);

  if (!text) {
    return { intent: 'clarify', items: [], needsAI: true, mustAsk: true, reasons: ['empty'], confidence: 0 };
  }

  if (hasAny(text, SPREAD)) {
    return { intent: 'query', items: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.7, gapMinutes: 30 };
  }

  if (/不排事|不想排/.test(text) && /休息|放松|歇/.test(text)) {
    return { intent: 'query', items: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.7 };
  }

  const hasActionVerb = /安排|排|放到|放进|放在|挪|改到|移到|换到|取消|完成|做完|写完|搞定|顺延|推迟|记一下|记着|先记|只记|补上|没做完|没完成|没搞完|开始/.test(text);
  const hasPlanSignal = /写|开|复习|准备|背|跑|练|取|买|交|做|排|安排|题|报告|作业/.test(text);
  if (!hasActionVerb && !hasPlanSignal && hasAny(text, QUERY)) {
    return { intent: 'query', items: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.7 };
  }
  if (!hasActionVerb && !hasPlanSignal && /[?？]|吗$|多少|什么|哪|怎么|为什么/.test(text)) {
    return { intent: 'query', items: [], needsAI: false, mustAsk: false, reasons: [], confidence: 0.6 };
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

  // Resolve the sentence's reference point: an explicit move target and a spoken
  // self-correction both override the first date/time that appears in the text.
  const correction = text.match(/(?:哦不|不对|说错了)[，,]?\s*(?:是)?\s*(.*)$/) ||
    text.match(/不是([^，,]*)[，,]\s*(?:是)?\s*(.*)$/);
  const correctedText = correction ? (correction[2] ?? correction[1] ?? '') : null;
  const correctedSource = correction && correction.length > 2 ? correction[1] : null;
  const moveTarget = text.match(/(?:挪到|移到|改到|换到|调整到|提前到|推迟到|顺延到|延到)(.*)$/);

  const fallbackDate = parseDate(text, today);
  const fallbackTime = parseTimes(text);
  const targetDate = moveTarget ? parseDate(moveTarget[1], today) : null;
  const targetTime = moveTarget ? parseTimes(moveTarget[1]) : null;
  const correctedDate = correctedText ? parseDate(correctedText, today) : null;
  let correctedTime = correctedText ? parseTimes(correctedText) : null;
  // "哦不，是四点" inherits 下午 from "下午三点".
  if (correctedTime?.startAmbiguous && fallbackTime && fallbackTime.startMinute >= 12 * 60) {
    correctedTime = { ...correctedTime, startMinute: correctedTime.startMinute + 12 * 60, startAmbiguous: false };
  }
  const dateHit = correctedDate || targetDate || fallbackDate;
  const timeHit = correctedTime || targetTime || fallbackTime;
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
  const doneCue = /做完|写完|搞定|交了|完成了|已经.*完/.test(text);
  const progressCue = /没做完|没完成|没搞完|只写了一半|写了一半|做了一半|只完成了一半|完成了一半|做到一半|补上|进度|没写|没交/.test(text);
  const postponeCue = /顺延|往后推|推迟一天|改天|推迟到|延到/.test(text);
  const moveCue = /挪到|移到|改到|换到|调整到|提前到|推迟到|顺延到|延到/.test(text);
  const placeCue = /放到|放进|放在/.test(text);
  const busyBlock = Boolean(timeHit?.endMinute) && /别排事|别排了|不要排/.test(text);
  const targetHasClock = Boolean(targetTime?.startMinute != null);

  let action = 'create';
  if (busyBlock) action = 'busy';
  else if (/取消|不做了|别做|算了/.test(text)) action = 'cancel';
  else if (progressCue) action = 'progress';
  else if (doneCue && !due?.cue) action = 'complete';
  else if (postponeCue) action = targetHasClock ? 'schedule' : 'postpone';
  else if (moveCue) action = 'schedule';
  else if (placeCue) action = contextTask ? 'schedule' : 'create';
  else if (recurrence && !recurrence.unsupported) action = 'routine';
  else if (captureCue) action = 'create';
  else if (contextTask && (dateHit || timeHit)) action = 'schedule';

  if (MULTI.filter((word) => text.includes(word)).length > 0 && /，|,/.test(text)) {
    const clauses = text.split(/[，,]/).filter(Boolean);
    const actionClauses = clauses.filter((clause) => /安排|排|挪|改到|移到|换到|取消|完成|做完|写完|记一下|记着|复习|写|开|取|买|跑|背|练|准备/.test(clause));
    if (actionClauses.length >= 2) wantsAI('multi-intent');
  }

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
  ].filter(Boolean);
  let name = contextTask?.name || stripName(text, spans);
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
    const base = contextTask?.date ? new Date(`${contextTask.date}T00:00:00Z`) : null;
    let date = dateHit?.date ?? null;
    if (!date && base && /一天/.test(text)) {
      base.setUTCDate(base.getUTCDate() + 1);
      date = base.toISOString().slice(0, 10);
    }
    items.push({ action, name, date, startMinute: null, durationMinutes: null });
  } else if (action === 'progress') {
    const progress = /一半|50%|百分之五十/.test(text) ? 50 : null;
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
      date: due.clockBefore ? dueDate : null,
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
    needsAI: mustAsk || reasons.length > 0,
    mustAsk,
    reasons: [...new Set([...hardReasons, ...reasons])],
    confidence,
    spans: { date: dateHit?.span, time: timeHit?.span, duration: durationHit?.span, recurrence: recurrence?.span },
  };
}

export function ruleSummary(parsed) {
  return {
    needsAI: parsed.needsAI,
    reasons: parsed.reasons,
    confidence: parsed.confidence,
    items: parsed.items,
  };
}
