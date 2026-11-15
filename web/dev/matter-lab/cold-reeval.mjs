// Replays the stored live predictions through the current rule layer without
// touching the network, so parser fixes can be measured on the same AI answers.
import { readFileSync, writeFileSync } from 'node:fs';
import { buildColdCorpus, COLD_TODAY } from './cold-corpus.mjs';
import { evaluateCase, summarize } from './eval.mjs';
import { applyAiOverrides, buildRulePlan } from '../../functions/rule-parser.mjs';

function toEval(items) {
  return (items || []).map((item) => ({
    action: item.action,
    name: item.name,
    date: item.date ?? null,
    startMinute: item.start ?? item.startMinute ?? null,
    endMinute: item.end ?? item.endMinute ?? null,
    durationMinutes: item.durationMinutes ?? null,
    weekdays: item.weekdays ?? null,
    move: Boolean(item.move),
    progress: item.progress ?? null,
    dueDate: item.dueDate ?? null,
    dueMinute: item.dueMinute ?? null,
    actualMinutes: item.actualMinutes ?? null,
  }));
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

const rows = readFileSync('dev/matter-lab/out/cold-ai.jsonl', 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line));
const byId = new Map();
for (const row of rows) if (!byId.has(row.id)) byId.set(row.id, row);

const out = [];
for (const testCase of buildColdCorpus()) {
  const row = byId.get(testCase.id);
  if (!row) continue;
  const aiPrediction = row.aiPrediction || { intent: 'clarify', items: [], reply: '' };
  const plan = buildRulePlan({
    message: testCase.utterance,
    today: COLD_TODAY,
    tasks: testCase.context?.tasks || [],
  });
  let adjusted = applyAiOverrides(toProposal(aiPrediction.items), plan, testCase.utterance);
  let hybridIntent = aiPrediction.intent;
  if (!adjusted.items.length && plan.items.length && !plan.mustAsk && (aiPrediction.intent === 'plan' || aiPrediction.intent === 'clarify')) {
    adjusted = { items: plan.items.map((item) => ({ ...item })), changed: true, protocol: 'floor' };
    hybridIntent = 'plan';
  }
  const aiEval = evaluateCase(testCase, { intent: aiPrediction.intent, items: toEval(aiPrediction.items), reply: aiPrediction.reply });
  const hybridEval = evaluateCase(testCase, { intent: hybridIntent, items: toEval(adjusted.items), reply: aiPrediction.reply });
  out.push({ id: testCase.id, category: testCase.category, utterance: testCase.utterance, source: row.source, protocol: adjusted.protocol || null, mustAsk: plan.mustAsk, reasons: plan.reasons, ai: aiEval, hybrid: hybridEval, aiPrediction, adjusted: toEval(adjusted.items) });
}

const aiSummary = summarize(out.map((row) => row.ai));
const hybridSummary = summarize(out.map((row) => row.hybrid));
const summary = {
  ai: aiSummary,
  hybrid: hybridSummary,
  aiFailures: out.filter((row) => !row.ai.ok).map((row) => `${row.id}:${row.utterance}`),
  hybridFailures: out.filter((row) => !row.hybrid.ok).map((row) => `${row.id}:${row.utterance}`),
  protocols: out.reduce((acc, row) => {
    if (row.protocol) acc[row.protocol] = (acc[row.protocol] || 0) + 1;
    return acc;
  }, {}),
};
writeFileSync('dev/matter-lab/out/cold-ai-summary.json', JSON.stringify(summary, null, 2));
writeFileSync('dev/matter-lab/out/cold-ai-reeval.json', JSON.stringify(out, null, 2));
console.log(JSON.stringify({ ai: { accuracy: aiSummary.accuracy, unsafe: aiSummary.unsafe, total: aiSummary.total }, hybrid: { accuracy: hybridSummary.accuracy, unsafe: hybridSummary.unsafe, total: hybridSummary.total }, protocols: summary.protocols }, null, 2));
console.log('ai failures', summary.aiFailures.join(' | '));
console.log('hybrid failures', summary.hybridFailures.join(' | '));
