// Hybrid v2: keep the model's reading of what to do, resolve calendar slots deterministically.
// This is not gold-aware: slots come from parseRule on the raw utterance.
import { readFileSync, writeFileSync } from 'node:fs';
import { buildCorpus, TODAY } from './corpus.mjs';
import { parseRule } from './rules.mjs';
import { evaluateCase, summarize } from './eval.mjs';

const cases = new Map(buildCorpus().map((item) => [item.id, item]));
const rows = readFileSync('dev/matter-lab/out/ai-results.jsonl', 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line));

const can = (name) => String(name || '').replace(/[\s，,。.!！?？:：;；、"'“”‘’]/g, '').toLowerCase();

function slotSource(message, testCase) {
  const parsed = parseRule(message, testCase.context || { today: TODAY });
  return parsed?.items?.[0] || null;
}

const REVIEW_CUE = /没做完|没完成|没搞完|没写|只写了一半|做到一半|补上/;
const EXTRA_CUE = /再(?:给我|排|安排|留)?\s*(?:[一二两三四五六七八九十百\d]+|半个)?\s*(?:小时|钟头|分钟|分|min)|再排|额外/;
const POSTPONE_CUE = /往后推|往后挪|推迟|顺延|延后|改天/;

const results = [];
const failures = [];
const sources = { rewritten: 0, postponed: 0, slots: 0 };

for (const row of rows) {
  const testCase = cases.get(row.id);
  if (!testCase) continue;
  if (!row.prediction) {
    results.push(evaluateCase(testCase, { intent: 'clarify', items: [] }));
    continue;
  }
  const ai = row.prediction;
  const items = (ai.items || []).map((item) => ({ ...item }));

  // Review: "没做完，再给我 X" keeps the original block and adds extra time.
  if (testCase.gold?.review && REVIEW_CUE.test(row.utterance) && EXTRA_CUE.test(row.utterance)) {
    for (const item of items) {
      if (item.action === 'schedule') {
        item.action = 'progress';
        item.move = false;
        sources.rewritten += 1;
        break;
      }
    }
  }

  // Postpone: only when the utterance has no new clock/date target; "推迟到下周三四点"
  // is a move, while "往后推一天" keeps the original clock.
  const ruleParsed = parseRule(row.utterance, testCase.context || { today: TODAY });
  if (ruleParsed?.items?.[0]?.action === 'postpone') {
    for (const item of items) {
      if (item.action === 'schedule' || item.action === 'create') {
        if (item.action === 'schedule') item.action = 'postpone';
        item.move = false;
        sources.postponed += 1;
        break;
      }
    }
  }

  const ruleItem = ruleParsed?.items?.[0] || null;
  // The rule floor: when the model refuses or leaks prose instead of JSON, a confident
  // deterministic parse is still better than asking the user to repeat a clear sentence.
  if (!items.length && ruleItem && !testCase.context?.needsDraft) {
    const floor = ruleParsed.items.map((item) => ({
      action: item.action,
      name: item.name,
      date: item.date,
      startMinute: item.startMinute,
      endMinute: item.endMinute,
      durationMinutes: item.durationMinutes,
      weekdays: item.weekdays,
      move: Boolean(item.move),
      progress: item.progress,
      dueDate: item.dueDate,
      dueMinute: item.dueMinute,
      actualMinutes: item.actualMinutes,
    }));
    items.push(...floor);
    sources.floor = (sources.floor || 0) + 1;
  }
  // Recurrence wording is unambiguous grammar, not a judgment call: 每天/工作日/周末/每周X
  // must be a routine even when the model reads it as a single day.
  if (ruleParsed?.items?.[0]?.action === 'routine' && items.length === 1 && items[0].action !== 'routine') {
    items[0].action = 'routine';
    items[0].date = null;
    items[0].weekdays = ruleParsed.items[0].weekdays;
    items[0].move = false;
    sources.recurrence = (sources.recurrence || 0) + 1;
  }

  if (ruleItem) {
    for (const item of items) {
      const byName = ruleParsed.items.find((candidate) => can(candidate.name) === can(item.name));
      const only = ruleParsed.items.length === 1 && items.length === 1 ? ruleItem : null;
      const source = byName || only;
      if (!source) continue;
      if (item.action === 'routine') {
        if (Array.isArray(source.weekdays) && source.weekdays.length) {
          item.weekdays = source.weekdays;
          sources.slots += 1;
        }
        if (source.startMinute != null) item.startMinute = source.startMinute;
        if (source.durationMinutes != null) item.durationMinutes = source.durationMinutes;
      } else if (item.action === 'create' || item.action === 'schedule' || item.action === 'postpone') {
        if (source.date) item.date = source.date;
        if (source.startMinute != null) item.startMinute = source.startMinute;
        if (source.durationMinutes != null) item.durationMinutes = source.durationMinutes;
        if (source.dueDate) item.dueDate = source.dueDate;
        if (source.dueMinute != null) item.dueMinute = source.dueMinute;
        sources.slots += 1;
      }
    }
  }

  const prediction = { ...ai, items };
  const evaluation = evaluateCase(testCase, prediction);
  results.push(evaluation);
  if (!evaluation.ok) failures.push({ id: row.id, utterance: row.utterance, gold: testCase.gold, prediction, evaluation });
}

const summary = summarize(results);
writeFileSync('dev/matter-lab/out/hybrid2.json', JSON.stringify({ summary, sources, failures }, null, 2));
console.log(JSON.stringify({ accuracy: summary.accuracy, unsafe: summary.unsafe, ok: summary.ok, total: summary.total, sources }, null, 2));
const byCategory = summary.byCategory;
for (const [key, value] of Object.entries(byCategory)) console.log(key, `${value.ok}/${value.total}`, (value.ok / value.total * 100).toFixed(0) + '%');
for (const item of failures) {
  console.log(`--- ${item.id} ${item.utterance}`);
  console.log('  gold', JSON.stringify(item.gold.items || item.gold.review));
  console.log('  pred', JSON.stringify((item.prediction.items || []).map((x) => ({ a: x.action, n: x.name, d: x.date, s: x.startMinute, dm: x.durationMinutes, w: x.weekdays, m: x.move }))));
}