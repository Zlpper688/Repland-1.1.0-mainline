// Runs the production rule parser (functions/rule-parser.mjs) against the corpus
// without any network calls, so the port cannot drift from the experiment.
import { readFileSync } from 'node:fs';
import { buildCorpus, TODAY } from './corpus.mjs';
import { buildRulePlan, applyAiOverrides } from '../../functions/rule-parser.mjs';
import { evaluateCase, summarize, ruleOnlySummary } from './eval.mjs';

const cases = new Map(buildCorpus().map((item) => [item.id, item]));
const aiRows = readFileSync('dev/matter-lab/out/ai-results.jsonl', 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line));
const aiById = new Map();
for (const row of aiRows) if (row.eval && !aiById.has(row.id)) aiById.set(row.id, row);

function toEval(items) {
  return (items || []).map((item) => {
    const restBlock = item.action === 'routine' && item.blockKind === 'REST' && item.date && !(item.weekdays || []).length;
    return {
      action: restBlock ? 'busy' : item.action,
      name: item.name,
      date: item.date ?? null,
      startMinute: item.start ?? null,
      endMinute: item.end ?? null,
      durationMinutes: item.durationMinutes ?? null,
      weekdays: restBlock ? null : item.weekdays ?? null,
      move: Boolean(item.move),
      progress: item.progress ?? null,
      dueDate: item.dueDate ?? null,
      dueMinute: item.dueMinute ?? null,
      actualMinutes: item.actualMinutes ?? null,
    };
  });
}

function toProposal(items) {
  return (items || []).map((item) => ({
    action: item.action,
    name: item.name,
    taskId: item.taskId ?? null,
    date: item.date ?? null,
    start: item.startMinute ?? null,
    end: item.endMinute ?? null,
    durationMinutes: item.durationMinutes ?? null,
    weekdays: item.weekdays ?? null,
    move: Boolean(item.move),
    progress: item.progress ?? null,
    dueDate: item.dueDate ?? null,
    dueMinute: item.dueMinute ?? null,
    actualMinutes: item.actualMinutes ?? null,
  }));
}

const ruleResults = [];
const hybridResults = [];
const hybridFailures = [];
const protocols = {};

for (const testCase of buildCorpus()) {
  const plan = buildRulePlan({ message: testCase.utterance, today: TODAY, tasks: testCase.context?.tasks || [] });
  const rulePrediction = { intent: plan.intent, items: toEval(plan.items), needsAI: plan.needsAI };
  ruleResults.push(evaluateCase(testCase, rulePrediction));

  const ai = aiById.get(testCase.id);
  if (!ai?.prediction) {
    hybridResults.push(evaluateCase(testCase, { intent: 'clarify', items: [] }));
    continue;
  }
  const adjusted = applyAiOverrides(toProposal(ai.prediction.items), plan, testCase.utterance);
  // The model refused or leaked prose: a confident rule draft is still better
  // than asking the user to repeat a clear sentence.
  if (!adjusted.items.length && plan.items.length && !plan.mustAsk) {
    adjusted.items = plan.items.map((item) => ({ ...item }));
    adjusted.changed = true;
    adjusted.protocol = 'floor';
  }
  protocols[adjusted.protocol || 'slots'] = (protocols[adjusted.protocol || 'slots'] || 0) + (adjusted.changed ? 1 : 0);
  const prediction = { intent: ai.prediction.intent, items: toEval(adjusted.items) };
  const evaluation = evaluateCase(testCase, prediction);
  hybridResults.push(evaluation);
  if (!evaluation.ok) {
    hybridFailures.push({
      id: testCase.id,
      utterance: testCase.utterance,
      gold: testCase.gold,
      prediction,
    });
  }
}

const ruleSummary = summarize(ruleResults);
const ruleOnly = ruleOnlySummary(ruleResults);
const hybridSummary = summarize(hybridResults);
console.log(JSON.stringify({
  rule: {
    accuracy: ruleSummary.accuracy,
    coverage: ruleOnly.coverage,
    coveredAccuracy: ruleOnly.coveredAccuracy,
    unsafe: ruleSummary.unsafe,
  },
  hybrid: {
    accuracy: hybridSummary.accuracy,
    ok: hybridSummary.ok,
    total: hybridSummary.total,
    unsafe: hybridSummary.unsafe,
  },
  protocols,
  ruleFailures: ruleResults.filter((item) => !item.ok).map((item) => `${item.id}:${item.utterance}`),
  hybridFailures: hybridFailures.map((item) => item.id),
}, null, 2));
