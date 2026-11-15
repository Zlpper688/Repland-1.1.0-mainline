// Rule-only pass over the cold-start corpus: how far the deterministic layer
// generalizes to phrasings it was not designed against.
import { writeFileSync } from 'node:fs';
import { buildColdCorpus, COLD_TODAY } from './cold-corpus.mjs';
import { buildRulePlan } from '../../functions/rule-parser.mjs';
import { evaluateCase, summarize, ruleOnlySummary } from './eval.mjs';

function toEval(items) {
  return (items || []).map((entry) => {
    const restBlock = entry.action === 'routine' && entry.blockKind === 'REST' && entry.date && !(entry.weekdays || []).length;
    return {
      action: restBlock ? 'busy' : entry.action,
      name: entry.name,
      date: entry.date ?? null,
      startMinute: entry.start ?? null,
      endMinute: entry.end ?? null,
      durationMinutes: entry.durationMinutes ?? null,
      weekdays: restBlock ? null : entry.weekdays ?? null,
      move: Boolean(entry.move),
      progress: entry.progress ?? null,
      dueDate: entry.dueDate ?? null,
      dueMinute: entry.dueMinute ?? null,
      actualMinutes: entry.actualMinutes ?? null,
    };
  });
}

const results = [];
const failures = [];
for (const testCase of buildColdCorpus()) {
  const plan = buildRulePlan({ message: testCase.utterance, today: COLD_TODAY, tasks: testCase.context?.tasks || [] });
  const prediction = { intent: plan.intent, items: toEval(plan.items), needsAI: plan.needsAI };
  const evaluation = evaluateCase(testCase, prediction);
  results.push(evaluation);
  if (!evaluation.ok) {
    failures.push({
      id: testCase.id,
      tag: testCase.tags.join(','),
      utterance: testCase.utterance,
      gold: testCase.gold.items?.length ? testCase.gold.items : testCase.gold.review || testCase.gold.intent,
      prediction: plan.items,
      mustAsk: plan.mustAsk,
      reasons: plan.reasons,
      itemResults: evaluation.itemResults,
    });
  }
}

const summary = summarize(results);
const ruleOnly = ruleOnlySummary(results);
writeFileSync('dev/matter-lab/out/cold-rule.json', JSON.stringify({ summary, ruleOnly, failures }, null, 2));
console.log(JSON.stringify({ accuracy: summary.accuracy, coverage: ruleOnly.coverage, coveredAccuracy: ruleOnly.coveredAccuracy, unsafe: summary.unsafe, total: summary.total }, null, 2));
console.log('by category:', Object.entries(summary.byCategory).map(([key, value]) => `${key} ${value.ok}/${value.total}`).join(' | '));
for (const failure of failures) {
  console.log(`--- ${failure.id} ${failure.utterance}${failure.mustAsk ? ' [mustAsk]' : ''} ${failure.reasons.join('/')}`);
  console.log('  gold', JSON.stringify(failure.gold));
  console.log('  pred', JSON.stringify(failure.prediction.map((entry) => ({ a: entry.action, n: entry.name, d: entry.date, s: entry.start, w: entry.weekdays, m: entry.move, due: entry.dueDate }))));
}
