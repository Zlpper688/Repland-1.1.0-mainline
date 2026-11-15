// Runs the corpus through the rule floor and/or the live assistant.
import { writeFileSync, appendFileSync, existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { buildCorpus, summarizeCorpus, TODAY } from './corpus.mjs';
import { parseRule } from './rules.mjs';
import { evaluateCase, summarize, ruleOnlySummary } from './eval.mjs';
import { LiveClient, toPrediction } from './live.mjs';

const args = process.argv.slice(2);
function flag(name, fallback) {
  const index = args.indexOf(`--${name}`);
  return index >= 0 ? args[index + 1] : fallback;
}
const mode = flag('mode', 'rule');
const limit = Number(flag('limit', '500'));
const start = Number(flag('start', '1'));
const category = flag('category', '');
const delay = Number(flag('delay', '350'));
const outDir = resolve(flag('out', 'dev/matter-lab/out'));

const allCases = buildCorpus().filter((item) => !category || item.category === category);
const selected = allCases.slice(Math.max(0, start - 1), Math.max(0, start - 1) + limit);
console.log(`corpus ${allCases.length}, selected ${selected.length}, mode ${mode}`);
console.log(JSON.stringify(summarizeCorpus(selected), null, 2));

function pickRule(caseItem) {
  return parseRule(caseItem.utterance, caseItem.context || { today: TODAY });
}

function ruleRun() {
  const results = selected.map((caseItem) => {
    const prediction = pickRule(caseItem);
    return { ...evaluateCase(caseItem, prediction), prediction };
  });
  const summary = summarize(results);
  const ruleOnly = ruleOnlySummary(results);
  writeFileSync(`${outDir}/rule-results.json`, JSON.stringify(results, null, 2));
  writeFileSync(`${outDir}/rule-summary.json`, JSON.stringify({ summary, ruleOnly }, null, 2));
  console.log('rule:', JSON.stringify({ accuracy: summary.accuracy, coverage: ruleOnly.coverage, coveredAccuracy: ruleOnly.coveredAccuracy, unsafe: summary.unsafe }));
  return results;
}

// The guest AI quota is 60 calls/day and the route silently degrades to the local
// parser past it, so each throwaway guest may only handle a quarter of that.
const CASES_PER_GUEST = 25;

async function aiRun() {
  let client = new LiveClient();
  await client.login();
  let used = 0;
  const rotate = async () => {
    client = new LiveClient();
    await client.login();
    used = 0;
    console.log('rotated guest account');
  };
  const file = `${outDir}/ai-results.jsonl`;
  const done = new Set();
  if (existsSync(file)) {
    for (const line of readFileSync(file, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try {
        const row = JSON.parse(line);
        if (row?.id) done.add(row.id);
      } catch {
        // A torn write is retried by appending a fresh row.
      }
    }
  }
  const results = [];
  let failures = 0;
  for (const caseItem of selected) {
    if (caseItem.context?.needsDraft) {
      results.push({ ...evaluateCase(caseItem, { intent: 'clarify', items: [] }), id: caseItem.id, category: caseItem.category, tags: caseItem.tags, utterance: caseItem.utterance, skipped: 'needs-draft' });
      continue;
    }
    if (done.has(caseItem.id)) {
      const row = readFileSync(file, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line)).find((item) => item.id === caseItem.id);
      if (row?.eval) results.push(row.eval);
      continue;
    }
    const startedAt = Date.now();
    let raw = null;
    let prediction = null;
    const ask = async () => {
      await client.reset();
      for (const task of caseItem.context?.tasks || []) await client.seed(task);
      raw = await client.ask(caseItem.utterance);
      used += 1;
      prediction = toPrediction(raw.data);
    };
    try {
      if (used >= CASES_PER_GUEST) await rotate();
      await ask();
      // An upstream 429 is swallowed by the route and comes back as a local parse,
      // so the only signal is the source tag; cool down instead of swapping guests.
      for (let attempt = 1; prediction?.source === 'local' && attempt <= 2; attempt += 1) {
        const waitMs = 45_000 * attempt;
        console.log(`local fallback on ${caseItem.id}, cooling ${waitMs / 1000}s (attempt ${attempt})`);
        await new Promise((resolve) => setTimeout(resolve, waitMs));
        if (used >= CASES_PER_GUEST) await rotate();
        await ask();
      }
    } catch (error) {
      raw = { status: 0, data: { error: error.name } };
    }
    const latencyMs = Date.now() - startedAt;
    const evaluation = evaluateCase(caseItem, prediction || { intent: 'clarify', items: [], reply: '' });
    const row = {
      id: caseItem.id,
      category: caseItem.category,
      tags: caseItem.tags,
      utterance: caseItem.utterance,
      status: raw?.status ?? 0,
      source: prediction?.source ?? null,
      latencyMs,
      prediction,
      eval: evaluation,
      reply: raw?.data?.reply || '',
      proposal: raw?.data?.proposal || null,
    };
    appendFileSync(file, `${JSON.stringify(row)}\n`);
    results.push(evaluation);
    if ((raw?.status ?? 0) !== 200) {
      failures += 1;
      console.log(`fail ${caseItem.id} ${raw?.status} ${JSON.stringify(raw?.data).slice(0, 160)}`);
      if (failures >= 5) {
        console.log('stopping after 5 consecutive failures');
        break;
      }
    } else {
      failures = 0;
      if (results.length % 10 === 0) {
        console.log(`progress ${results.length}/${selected.length} last=${caseItem.id} ${latencyMs}ms source=${prediction.source}`);
      }
    }
    await new Promise((resolve) => setTimeout(resolve, delay));
  }
  const summary = summarize(results);
  writeFileSync(`${outDir}/ai-summary.json`, JSON.stringify(summary, null, 2));
  console.log('ai:', JSON.stringify({ accuracy: summary.accuracy, unsafe: summary.unsafe, total: summary.total }));
  return results;
}

function boundaryReport(ruleResults, aiResults) {
  const reasons = {};
  for (const result of ruleResults) {
    for (const reason of result.reasons || []) {
      const stat = (reasons[reason] ||= { total: 0, covered: 0, coveredOk: 0, unsafe: 0 });
      stat.total += 1;
      if (!result.deferred) {
        stat.covered += 1;
        stat.coveredOk += result.ok ? 1 : 0;
        stat.unsafe += result.unsafe ? 1 : 0;
      }
    }
  }
  const tagStats = {};
  for (const result of ruleResults) {
    for (const tag of result.tags || []) {
      const stat = (tagStats[tag] ||= { total: 0, deferred: 0, coveredOk: 0, covered: 0, aiTotal: 0, aiOk: 0 });
      stat.total += 1;
      if (result.deferred) stat.deferred += 1;
      else {
        stat.covered += 1;
        stat.coveredOk += result.ok ? 1 : 0;
      }
    }
  }
  for (const result of aiResults || []) {
    for (const tag of result.tags || []) {
      const stat = (tagStats[tag] ||= { total: 0, deferred: 0, coveredOk: 0, covered: 0, aiTotal: 0, aiOk: 0 });
      stat.aiTotal += 1;
      stat.aiOk += result.ok ? 1 : 0;
    }
  }
  const mustAI = Object.entries(reasons)
    .map(([reason, stat]) => ({
      reason,
      total: stat.total,
      coverage: stat.total ? (stat.total - (ruleResults.filter((item) => item.deferred && (item.reasons || []).includes(reason)).length)) / stat.total : 0,
      coveredAccuracy: stat.covered ? stat.coveredOk / stat.covered : null,
      unsafe: stat.unsafe,
    }))
    .sort((a, b) => b.total - a.total);
  const tagBoundary = Object.entries(tagStats)
    .map(([tag, stat]) => ({
      tag,
      total: stat.total,
      ruleCoverage: stat.total ? (stat.total - stat.deferred) / stat.total : 0,
      ruleCoveredAccuracy: stat.covered ? stat.coveredOk / stat.covered : null,
      aiAccuracy: stat.aiTotal ? stat.aiOk / stat.aiTotal : null,
    }))
    .sort((a, b) => a.ruleCoverage - b.ruleCoverage);
  return { reasons: mustAI, tags: tagBoundary };
}

function markdown(report) {
  const lines = [];
  lines.push('# Matter lab report');
  lines.push('');
  lines.push(`- corpus: ${report.corpusTotal}`);
  lines.push(`- rule coverage: ${(report.rule.coverage * 100).toFixed(1)}%`);
  lines.push(`- rule covered accuracy: ${(report.rule.coveredAccuracy * 100).toFixed(1)}%`);
  if (report.ai) lines.push(`- AI accuracy: ${(report.ai.accuracy * 100).toFixed(1)}% (unsafe ${(report.ai.unsafeRate * 100).toFixed(1)}%)`);
  if (report.hybrid) lines.push(`- hybrid accuracy: ${(report.hybrid.accuracy * 100).toFixed(1)}%`);
  lines.push('');
  lines.push('## Mandatory AI triggers');
  lines.push('');
  lines.push('| reason | cases | rule coverage | rule covered accuracy | unsafe |');
  lines.push('| --- | ---: | ---: | ---: | ---: |');
  for (const item of report.boundary.reasons) {
    lines.push(`| ${item.reason} | ${item.total} | ${(item.coverage * 100).toFixed(0)}% | ${item.coveredAccuracy == null ? '—' : (item.coveredAccuracy * 100).toFixed(0) + '%'} | ${item.unsafe} |`);
  }
  lines.push('');
  lines.push('## Tag boundary');
  lines.push('');
  lines.push('| tag | cases | rule coverage | rule covered accuracy | AI accuracy |');
  lines.push('| --- | ---: | ---: | ---: | ---: |');
  for (const item of report.boundary.tags.slice(0, 40)) {
    lines.push(`| ${item.tag} | ${item.total} | ${(item.ruleCoverage * 100).toFixed(0)}% | ${item.ruleCoveredAccuracy == null ? '—' : (item.ruleCoveredAccuracy * 100).toFixed(0) + '%'} | ${item.aiAccuracy == null ? '—' : (item.aiAccuracy * 100).toFixed(0) + '%'} |`);
  }
  lines.push('');
  lines.push('## Rule failures');
  lines.push('');
  const failures = report.ruleFailures || [];
  for (const item of failures.slice(0, 30)) {
    lines.push(`- ${item.id} [${item.category}] ${item.utterance} → ${JSON.stringify(item.prediction?.items || [])} reasons=${(item.reasons || []).join(',')}`);
  }
  return `${lines.join('\n')}\n`;
}

const ruleResults = mode === 'rule' || mode === 'both' ? ruleRun() : null;
const aiResults = mode === 'ai' || mode === 'both' ? await aiRun() : null;

if (ruleResults) {
  const report = {
    corpusTotal: allCases.length,
    selected: selected.length,
    rule: ruleOnlySummary(ruleResults),
    ai: aiResults ? summarize(aiResults) : null,
    boundary: boundaryReport(ruleResults, aiResults),
    ruleFailures: ruleResults.filter((item) => !item.ok).map((item) => ({
      id: item.id,
      category: item.category,
      utterance: item.utterance,
      reasons: item.reasons,
      prediction: item.prediction,
    })),
  };
  if (aiResults) {
    const ruleById = new Map(ruleResults.map((item) => [item.id, item]));
    const aiById = new Map(aiResults.map((item) => [item.id, item]));
    const hybrid = [];
    for (const [id, ruleItem] of ruleById) {
      const aiItem = aiById.get(id);
      if (ruleItem.deferred && aiItem && !aiItem.skipped) hybrid.push(aiItem);
      else if (!ruleItem.deferred) hybrid.push(ruleItem);
      else hybrid.push({ ...ruleItem, ok: false });
    }
    report.hybrid = summarize(hybrid);
  }
  writeFileSync(`${outDir}/report.json`, JSON.stringify(report, null, 2));
  writeFileSync(`${outDir}/report.md`, markdown(report));
  console.log(`report: ${outDir}/report.md`);
}
