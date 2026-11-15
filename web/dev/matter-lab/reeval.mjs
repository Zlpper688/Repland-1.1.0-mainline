// Re-scores stored rows from their proposals after a harness mapping fix; no AI calls.
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { buildCorpus } from './corpus.mjs';
import { evaluateCase } from './eval.mjs';
import { toPrediction } from './live.mjs';

const outDir = resolve('dev/matter-lab/out');
const file = `${outDir}/ai-results.jsonl`;
const cases = new Map(buildCorpus().map((item) => [item.id, item]));
const rows = readFileSync(file, 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line));

let changed = 0;
const updated = rows.map((row) => {
  const testCase = cases.get(row.id);
  if (!testCase) return row;
  const prediction = row.proposal
    ? toPrediction({ proposal: row.proposal, reply: row.reply, source: row.source })
    : row.prediction;
  const evaluation = evaluateCase(testCase, prediction || { intent: 'clarify', items: [] });
  if (JSON.stringify(evaluation) !== JSON.stringify(row.eval)) changed += 1;
  return { ...row, prediction, eval: evaluation };
});

writeFileSync(file, `${updated.map((row) => JSON.stringify(row)).join('\n')}\n`);
console.log(`re-scored ${updated.length} rows, ${changed} changed`);
