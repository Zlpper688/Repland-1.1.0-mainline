// Compares rule/AI output with the same canonical slots.

const ACTION_FAMILY = {
  create: 'create',
  schedule: 'schedule',
  routine: 'routine',
  complete: 'complete',
  cancel: 'cancel',
  postpone: 'postpone',
  progress: 'progress',
  update: 'update',
  busy: 'busy',
};

export function canonicalName(name) {
  return String(name || '')
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[\s，,。.!！?？:：;；、"'“”‘’]/g, '')
    .replace(/^(帮我|麻烦|请|我想|我要|给我|安排|排|把)/, '')
    .replace(/(吧|呗|啊|呀|呢|就行|谢谢)$/g, '');
}

export function nameMatches(goldName, predName) {
  const a = canonicalName(goldName);
  const b = canonicalName(predName);
  if (!a || !b) return false;
  if (a === b) return true;
  if (a.includes(b) || b.includes(a)) return true;
  const setA = new Set([...a]);
  const setB = new Set([...b]);
  let overlap = 0;
  for (const ch of setA) if (setB.has(ch)) overlap += 1;
  return overlap / Math.max(setA.size, setB.size) >= 0.75;
}

function sameSet(a, b) {
  if (!Array.isArray(a) || !Array.isArray(b)) return false;
  const left = [...new Set(a)].sort((x, y) => x - y);
  const right = [...new Set(b)].sort((x, y) => x - y);
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function itemScore(gold, pred) {
  let score = 0;
  if (nameMatches(gold.name, pred.name)) score += 3;
  if ((ACTION_FAMILY[gold.action] || gold.action) === (ACTION_FAMILY[pred.action] || pred.action)) score += 2;
  if (gold.action === 'schedule' && pred.move && gold.move !== false) score += 1;
  if (gold.date && pred.date === gold.date) score += 1;
  if (gold.startMinute != null && pred.startMinute === gold.startMinute) score += 1;
  return score;
}

export function matchItems(goldItems, predItems) {
  const remaining = [...(predItems || [])];
  const pairs = [];
  for (const gold of goldItems || []) {
    let bestIndex = -1;
    let bestScore = 0;
    remaining.forEach((pred, index) => {
      const score = itemScore(gold, pred);
      if (score > bestScore) {
        bestScore = score;
        bestIndex = index;
      }
    });
    if (bestIndex >= 0 && bestScore >= 2) {
      pairs.push([gold, remaining.splice(bestIndex, 1)[0]]);
    } else {
      pairs.push([gold, null]);
    }
  }
  return { pairs, leftovers: remaining };
}

function compareItem(gold, pred) {
  if (!pred) {
    return { ok: false, unsafe: false, exact: { name: false, action: false, date: false, start: false, duration: false, weekdays: false }, note: 'missing' };
  }
  const exact = {
    name: nameMatches(gold.name, pred.name),
    action: (ACTION_FAMILY[gold.action] || gold.action) === (ACTION_FAMILY[pred.action] || pred.action),
    date: gold.date == null ? null : pred.date === gold.date,
    start: gold.startMinute == null ? null : pred.startMinute === gold.startMinute,
    duration: gold.durationMinutes == null ? null : pred.durationMinutes === gold.durationMinutes,
    weekdays: gold.weekdays == null ? null : sameSet(gold.weekdays, pred.weekdays),
    dueDate: gold.dueDate == null ? null : pred.dueDate === gold.dueDate,
    dueMinute: gold.dueMinute == null ? null : pred.dueMinute === gold.dueMinute,
    progress: gold.progress == null ? null : pred.progress === gold.progress,
    actual: gold.actualMinutes == null ? null : pred.actualMinutes === gold.actualMinutes,
  };
  const actionOk = exact.action;
  const dateOk = exact.date !== false;
  const startOk = exact.start !== false;
  const durationOk = exact.duration !== false;
  const weekdaysOk = exact.weekdays !== false;
  const dueOk = exact.dueDate !== false && exact.dueMinute !== false;
  const progressOk = exact.progress !== false;
  const actualOk = exact.actual !== false;
  const nameOk = exact.name;
  const ok = actionOk && dateOk && startOk && durationOk && weekdaysOk && dueOk && progressOk && actualOk && nameOk;
  // The dangerous mistakes: acting on the wrong day, collapsing a routine to one day,
  // or changing the wrong kind of state (complete instead of move, etc.).
  const unsafe =
    (exact.date === false && gold.date != null) ||
    (exact.weekdays === false && gold.weekdays != null) ||
    (!actionOk && ['complete', 'cancel', 'postpone'].includes(gold.action)) ||
    (gold.action === 'routine' && pred.action !== 'routine');
  return { ok, unsafe, exact, note: ok ? 'ok' : 'mismatch' };
}

export function evaluateCase(testCase, prediction) {
  const gold = testCase.gold || {};
  const items = prediction?.items || [];
  const intent = prediction?.intent || 'plan';
  const result = {
    id: testCase.id,
    category: testCase.category,
    tags: testCase.tags,
    utterance: testCase.utterance,
    ok: false,
    unsafe: false,
    deferred: Boolean(prediction?.needsAI),
    reasons: prediction?.reasons || [],
    intentOk: true,
    itemResults: [],
    missingExtraSchedule: false,
  };

  if (gold.intent === 'query' || gold.intent === 'clarify') {
    result.intentOk = gold.intent === intent;
    // Query and clarify are both non-executing reads of the same sentence; an
    // advice-shaped answer that touches no state is correct whichever label it wears.
    const nonExecuting = ['query', 'clarify'].includes(intent) && !items.length && Boolean(prediction?.reply);
    result.labelMismatch = !result.intentOk && nonExecuting;
    result.ok = result.intentOk || nonExecuting;
    return result;
  }

  if (gold.allowAsk && intent === 'clarify' && !items.length) {
    // The deterministic layer may defer what the model can resolve.
    result.asked = true;
    result.ok = true;
    return result;
  }

  if (gold.review) {
    const extra = items.find((item) => item.date === gold.review.extraDate);
    const hasIncompleteSignal = items.some((item) => item.action === 'progress') ||
      /没做完|没完成|没搞完|没写|补上|一半/.test(prediction?.reply || '');
    const durationOk = gold.review.extraDuration == null || extra?.durationMinutes === gold.review.extraDuration;
    const progressOk = gold.review.progress == null || items.some((item) => item.progress === gold.review.progress);
    result.review = {
      incomplete: hasIncompleteSignal,
      extraDate: Boolean(extra),
      duration: durationOk,
      progress: progressOk,
    };
    result.ok = hasIncompleteSignal && Boolean(extra) && durationOk && progressOk;
    result.missingExtraSchedule = !extra;
    return result;
  }

  const { pairs } = matchItems(gold.items || [], items);
  result.itemResults = pairs.map(([goldItem, predItem]) => compareItem(goldItem, predItem));
  result.ok = result.itemResults.length > 0 && result.itemResults.every((item) => item.ok);
  result.unsafe = result.itemResults.some((item) => item.unsafe);
  return result;
}

export function summarize(results) {
  const total = results.length;
  const ok = results.filter((item) => item.ok).length;
  const unsafe = results.filter((item) => item.unsafe).length;
  const deferred = results.filter((item) => item.deferred).length;
  const byCategory = {};
  const byTag = {};
  for (const result of results) {
    const category = (byCategory[result.category] ||= { total: 0, ok: 0, unsafe: 0, deferred: 0 });
    category.total += 1;
    category.ok += result.ok ? 1 : 0;
    category.unsafe += result.unsafe ? 1 : 0;
    category.deferred += result.deferred ? 1 : 0;
    for (const tag of result.tags || []) {
      const tagStat = (byTag[tag] ||= { total: 0, ok: 0, unsafe: 0, deferred: 0 });
      tagStat.total += 1;
      tagStat.ok += result.ok ? 1 : 0;
      tagStat.unsafe += result.unsafe ? 1 : 0;
      tagStat.deferred += result.deferred ? 1 : 0;
    }
  }
  return {
    total,
    ok,
    accuracy: total ? ok / total : 0,
    unsafe,
    unsafeRate: total ? unsafe / total : 0,
    deferred,
    coverage: total ? (total - deferred) / total : 0,
    byCategory,
    byTag,
  };
}

export function ruleOnlySummary(results) {
  const covered = results.filter((item) => !item.deferred);
  return {
    total: results.length,
    covered: covered.length,
    coverage: results.length ? covered.length / results.length : 0,
    coveredAccuracy: covered.length ? covered.filter((item) => item.ok).length / covered.length : 0,
    coveredUnsafe: covered.filter((item) => item.unsafe).length,
  };
}
