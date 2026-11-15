// Live AI pass over the cold-start corpus, then a local hybrid replay:
// the model's items plus the production rule guard (applyAiOverrides).
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { buildColdCorpus, COLD_TODAY } from './cold-corpus.mjs';
import { LiveClient, toPrediction } from './live.mjs';
import { evaluateCase, summarize } from './eval.mjs';
import { applyAiOverrides, buildRulePlan } from '../../functions/rule-parser.mjs';

const CASES_PER_GUEST = 25;
const OUT = 'dev/matter-lab/out/cold-ai.jsonl';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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

const cases = buildColdCorpus();
const done = new Map();
if (existsSync(OUT)) {
  for (const line of readFileSync(OUT, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    try {
      const row = JSON.parse(line);
      if (row?.id && !done.has(row.id)) done.set(row.id, row);
    } catch {
      // torn write, retry below
    }
  }
}

let client = new LiveClient();
await client.login();
let used = 0;
const rotate = async () => {
  client = new LiveClient();
  await client.login();
  used = 0;
  console.log('rotated guest');
};

const rows = [];
for (const testCase of cases) {
  if (done.has(testCase.id)) {
    rows.push(done.get(testCase.id));
    continue;
  }
  if (used >= CASES_PER_GUEST) await rotate();
  let raw = null;
  let prediction = null;
  const ask = async () => {
    await client.reset();
    for (const task of testCase.context?.tasks || []) await client.seed(task);
    raw = await client.ask(testCase.utterance);
    used += 1;
    prediction = toPrediction(raw.data);
  };
  try {
    await ask();
    for (let attempt = 1; prediction?.source === 'local' && attempt <= 2; attempt += 1) {
      const waitMs = 45_000 * attempt;
      console.log(`local fallback on ${testCase.id}, cooling ${waitMs / 1000}s`);
      await sleep(waitMs);
      if (used >= CASES_PER_GUEST) await rotate();
      await ask();
    }
  } catch (error) {
    raw = { status: 0, data: { error: error.name } };
    prediction = null;
  }

  const plan = buildRulePlan({
    message: testCase.utterance,
    today: COLD_TODAY,
    tasks: testCase.context?.tasks || [],
  });
  const aiPrediction = prediction || { intent: 'clarify', items: [], reply: '' };
  let adjusted = applyAiOverrides(toProposal(aiPrediction.items), plan, testCase.utterance);
  let hybridIntent = aiPrediction.intent;
  if (!adjusted.items.length && plan.items.length && !plan.mustAsk && (aiPrediction.intent === 'plan' || aiPrediction.intent === 'clarify')) {
    // Production keeps a clear rule reading when the model contributes nothing;
    // a real hard trigger (mustAsk) is excluded above.
    adjusted = { items: plan.items.map((item) => ({ ...item })), changed: true, protocol: 'floor' };
    hybridIntent = 'plan';
  }
  const hybridPrediction = {
    intent: hybridIntent,
    items: toEval(adjusted.items),
    reply: adjusted.changed ? '' : aiPrediction.reply,
  };
  const aiEval = evaluateCase(testCase, {
    intent: aiPrediction.intent,
    items: toEval(aiPrediction.items),
    reply: aiPrediction.reply,
  });
  const hybridEval = evaluateCase(testCase, hybridPrediction);

  const row = {
    id: testCase.id,
    category: testCase.category,
    utterance: testCase.utterance,
    status: raw?.status ?? 0,
    source: prediction?.source ?? null,
    ai: aiEval,
    hybrid: hybridEval,
    protocol: adjusted.protocol || null,
    aiPrediction,
    plan: { mustAsk: plan.mustAsk, needsAI: plan.needsAI, reasons: plan.reasons },
    reply: raw?.data?.reply || '',
  };
  appendFileSync(OUT, `${JSON.stringify(row)}\n`);
  rows.push(row);
  console.log(`${testCase.id} ${hybridEval.ok ? 'OK' : 'FAIL'} ai=${aiEval.ok ? 'OK' : 'FAIL'} src=${row.source} proto=${row.protocol || '-'}`);
  await sleep(350);
}

const aiSummary = summarize(rows.map((row) => row.ai));
const hybridSummary = summarize(rows.map((row) => row.hybrid));
writeFileSync(
  'dev/matter-lab/out/cold-ai-summary.json',
  JSON.stringify(
    {
      ai: aiSummary,
      hybrid: hybridSummary,
      aiFailures: rows.filter((row) => !row.ai.ok).map((row) => `${row.id}:${row.utterance}`),
      hybridFailures: rows.filter((row) => !row.hybrid.ok).map((row) => `${row.id}:${row.utterance}`),
    },
    null,
    2,
  ),
);
console.log('ai', JSON.stringify({ accuracy: aiSummary.accuracy, unsafe: aiSummary.unsafe, total: aiSummary.total }));
console.log('hybrid', JSON.stringify({ accuracy: hybridSummary.accuracy, unsafe: hybridSummary.unsafe, total: hybridSummary.total }));
