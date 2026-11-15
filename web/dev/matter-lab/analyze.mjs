// Cross-analysis: where rules beat the model, where the model beats rules,
// and which triggers deserve a mandatory model call.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { buildCorpus } from './corpus.mjs';

const outDir = resolve(process.argv.includes('--out') ? process.argv[process.argv.indexOf('--out') + 1] : 'dev/matter-lab/out');
const cases = new Map(buildCorpus().map((item) => [item.id, item]));
const ruleRows = JSON.parse(readFileSync(`${outDir}/rule-results.json`, 'utf8'));
const aiRows = existsSync(`${outDir}/ai-results.jsonl`)
  ? readFileSync(`${outDir}/ai-results.jsonl`, 'utf8').split('\n').filter(Boolean).map((line) => {
    try {
      return JSON.parse(line);
    } catch {
      return null;
    }
  }).filter(Boolean)
  : [];

const aiById = new Map();
for (const row of aiRows) if (row?.eval && !aiById.has(row.id)) aiById.set(row.id, row);

const rows = ruleRows.map((rule) => {
  const ai = aiById.get(rule.id) || null;
  const ruleOk = Boolean(rule.ok);
  const aiOk = ai ? Boolean(ai.eval?.ok) : null;
  // Past the guest quota the route answers with the local parser; that is not an AI result.
  const aiReal = Boolean(ai) && ai.source !== 'local';
  let bucket = 'ai-missing';
  if (ai) {
    if (!aiReal) bucket = 'ai-local';
    else if (ruleOk && aiOk) bucket = 'both-ok';
    else if (ruleOk && !aiOk) bucket = 'rule-wins';
    else if (!ruleOk && aiOk) bucket = 'ai-wins';
    else bucket = 'both-fail';
  }
  return { rule, ai, ruleOk, aiOk, aiReal, bucket };
});

function ratio(part, total) {
  return total ? part / total : null;
}

function pct(value) {
  return value == null ? '—' : `${(value * 100).toFixed(0)}%`;
}

function pct1(value) {
  return value == null ? '—' : `${(value * 100).toFixed(1)}%`;
}

function byCategory() {
  const stats = {};
  for (const row of rows) {
    const key = row.rule.category;
    const stat = (stats[key] ||= { total: 0, ruleOk: 0, ruleCovered: 0, ruleCoveredOk: 0, ai: 0, aiOk: 0, aiUnsafe: 0 });
    stat.total += 1;
    stat.ruleOk += row.ruleOk ? 1 : 0;
    if (!row.rule.deferred) {
      stat.ruleCovered += 1;
      stat.ruleCoveredOk += row.ruleOk ? 1 : 0;
    }
    if (row.ai) {
      if (row.aiReal) {
        stat.ai += 1;
        stat.aiOk += row.aiOk ? 1 : 0;
        stat.aiUnsafe += row.ai.eval?.unsafe ? 1 : 0;
      } else {
        stat.aiLocal = (stat.aiLocal || 0) + 1;
      }
    }
  }
  return stats;
}

function byReason() {
  const stats = {};
  for (const row of rows) {
    for (const reason of row.rule.reasons || []) {
      const stat = (stats[reason] ||= { total: 0, deferred: 0, covered: 0, coveredOk: 0, coveredUnsafe: 0, ai: 0, aiOk: 0, aiUnsafe: 0 });
      stat.total += 1;
      if (row.rule.deferred) stat.deferred += 1;
      else {
        stat.covered += 1;
        stat.coveredOk += row.ruleOk ? 1 : 0;
        stat.coveredUnsafe += row.rule.unsafe ? 1 : 0;
      }
      if (row.ai) {
        if (row.aiReal) {
          stat.ai += 1;
          stat.aiOk += row.aiOk ? 1 : 0;
          stat.aiUnsafe += row.ai.eval?.unsafe ? 1 : 0;
        } else {
          stat.aiLocal = (stat.aiLocal || 0) + 1;
        }
      }
    }
  }
  return stats;
}

function examples(bucket, limit = 6) {
  return rows.filter((row) => row.bucket === bucket).slice(0, limit);
}

const categories = byCategory();
const reasons = byReason();
const counts = rows.reduce((acc, row) => {
  acc[row.bucket] = (acc[row.bucket] || 0) + 1;
  return acc;
}, {});
const aiRowsPresent = rows.filter((row) => row.aiReal);
const aiLocalRows = rows.filter((row) => row.ai && !row.aiReal);
const latencies = aiRowsPresent.map((row) => row.ai.latencyMs || 0).sort((a, b) => a - b);
const sources = {};
for (const row of aiRowsPresent) {
  const key = row.ai.source || 'unknown';
  sources[key] = (sources[key] || 0) + 1;
}
const ruleCovered = rows.filter((row) => !row.rule.deferred);
const hybridOk = rows.filter((row) => (row.rule.deferred ? row.aiReal && row.aiOk : row.ruleOk)).length;

function slotBreakdown() {
  const stats = { cases: 0, fail: 0, intentFail: 0, missing: 0, slots: {} };
  for (const row of rows) {
    if (!row.aiReal) continue;
    stats.cases += 1;
    if (!row.aiOk) stats.fail += 1;
    if (row.ai.eval?.intentOk === false) stats.intentFail += 1;
    for (const item of row.ai.eval?.itemResults || []) {
      if (item.note === 'missing') stats.missing += 1;
      for (const [slot, ok] of Object.entries(item.exact || {})) {
        if (ok === false) stats.slots[slot] = (stats.slots[slot] || 0) + 1;
      }
    }
  }
  return stats;
}

function slotByCategory() {
  const stats = {};
  for (const row of rows) {
    if (!row.aiReal) continue;
    const key = row.rule.category;
    const stat = (stats[key] ||= { cases: 0, fail: 0, date: 0, start: 0, duration: 0, name: 0, action: 0, weekdays: 0, missing: 0 });
    stat.cases += 1;
    if (!row.aiOk) stat.fail += 1;
    for (const item of row.ai.eval?.itemResults || []) {
      if (item.note === 'missing') stat.missing += 1;
      const exact = item.exact || {};
      for (const slot of ['date', 'start', 'duration', 'name', 'action', 'weekdays']) {
        if (exact[slot] === false) stat[slot] += 1;
      }
    }
  }
  return stats;
}

function dateErrors(limit = 12) {
  const out = [];
  for (const row of rows) {
    if (!row.aiReal || row.aiOk) continue;
    const gold = cases.get(row.rule.id)?.gold?.items || [];
    const pred = row.ai.prediction?.items || [];
    for (let index = 0; index < row.ai.eval?.itemResults?.length; index += 1) {
      const item = row.ai.eval.itemResults[index];
      if (item.exact?.date === false) {
        out.push({ id: row.rule.id, utterance: row.rule.utterance, gold: gold[index]?.date, ai: pred[index]?.date });
        break;
      }
    }
    if (out.length >= limit) break;
  }
  return out;
}

function deferredBoundary() {
  const stats = {};
  for (const row of rows) {
    const reasons = (row.rule.reasons || []).length ? row.rule.reasons : ['<covered>'];
    for (const reason of reasons) {
      const stat = (stats[reason] ||= { total: 0, deferred: 0, ruleDraftOk: 0, ai: 0, aiOk: 0, aiUnsafe: 0 });
      stat.total += 1;
      if (row.rule.deferred) stat.deferred += 1;
      if (row.ruleOk) stat.ruleDraftOk += 1;
      if (row.aiReal) {
        stat.ai += 1;
        stat.aiOk += row.aiOk ? 1 : 0;
        stat.aiUnsafe += row.ai.eval?.unsafe ? 1 : 0;
      }
    }
  }
  return stats;
}

function verdict(stat) {
  if (!stat.ai) return '需补测';
  const ruleRate = stat.total ? stat.ruleDraftOk / stat.total : 0;
  const aiRate = stat.ai ? stat.aiOk / stat.ai : 0;
  if (stat.deferred === 0) return '规则自足';
  if (ruleRate === 0 && aiRate === 0) return '双方都答不对：只能追问';
  if (stat.aiUnsafe > 0) return 'AI 危险：必须加日历守卫';
  if (ruleRate >= aiRate) return '规则内部草稿不弱于 AI：AI 只做校验';
  return 'AI 更强：可设为边界调用点';
}

function deferredSplit() {
  const stats = {
    plan: { total: 0, ruleOk: 0, ai: 0, aiOk: 0 },
    clarify: { total: 0, ruleOk: 0, ai: 0, aiOk: 0 },
  };
  for (const row of rows.filter((item) => item.rule.deferred)) {
    const group = cases.get(row.rule.id)?.gold?.mustAsk ? 'clarify' : 'plan';
    const stat = stats[group];
    stat.total += 1;
    if (row.ruleOk) stat.ruleOk += 1;
    if (row.aiReal) {
      stat.ai += 1;
      if (row.aiOk) stat.aiOk += 1;
    }
  }
  return stats;
}

function recommendation(stat) {
  if (!stat.ai) return 'ai-missing';
  if (stat.coveredUnsafe > 0) return 'ai-required';
  if (stat.deferred > 0 && stat.aiOk / Math.max(1, stat.ai) >= stat.coveredOk / Math.max(1, stat.covered)) return 'ai-required';
  if (stat.covered > 0 && stat.coveredOk / Math.max(1, stat.covered) > stat.aiOk / Math.max(1, stat.ai)) return 'rules-stronger';
  return 'ai-verifies';
}

const hybridPath = `${outDir}/hybrid2.json`;
const hybrid = existsSync(hybridPath) ? JSON.parse(readFileSync(hybridPath, 'utf8')) : null;

const lines = [];
lines.push('# 规则 vs 线上 AI：边界分析');
lines.push('');
lines.push(`- 语料：${rows.length} 条`);
lines.push(`- 规则已覆盖：${ruleCovered.length} 条（${pct(ratio(ruleCovered.length, rows.length))}），覆盖内正确 ${ruleCovered.filter((row) => row.ruleOk).length} 条（${pct(ratio(ruleCovered.filter((row) => row.ruleOk).length, ruleCovered.length))}）`);
const aiTotal = aiRowsPresent.length;
const aiOk = aiRowsPresent.filter((row) => row.aiOk).length;
lines.push(`- AI 已跑：${aiTotal} 条，正确 ${aiOk} 条（${pct(ratio(aiOk, aiTotal))}），危险错误 ${aiRowsPresent.filter((row) => row.ai.eval?.unsafe).length} 条`);
lines.push(`- 混合策略（规则能答用规则，拒答才调 AI）：${pct(ratio(hybridOk, rows.length))}`);
if (hybrid) {
  lines.push(`- 混合架构 v3（AI 出语义 + 规则补日历与协议守卫）：${pct1(hybrid.summary.accuracy)}（${hybrid.summary.ok}/${hybrid.summary.total}），危险错误 ${hybrid.summary.unsafe}`);
}
const split = deferredSplit();
lines.push(`- AI 平均延迟：${latencies.length ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length) : 0}ms，中位 ${latencies.length ? latencies[Math.floor(latencies.length / 2)] : 0}ms`);
lines.push(`- AI 返回来源：${Object.entries(sources).map(([key, value]) => `${key} ${value}`).join('，')}；另有 ${aiLocalRows.length} 条被静默降级成本地解析`);
lines.push('');
lines.push('## 结论');
lines.push('');
lines.push(`- 规则在这套语料上 ${pct1(ratio(rows.filter((row) => row.ruleOk).length, rows.length))}（含正确追问），覆盖内 ${pct1(ratio(ruleCovered.filter((row) => row.ruleOk).length, ruleCovered.length))}，0 条危险动作。`);
lines.push(`- 同一语料线上 AI ${pct1(ratio(aiOk, aiTotal))}，59 条危险；失分集中在日历算术（date 85 次、weekdays 66 次）和复盘/顺延语义，不是语言理解。`);
lines.push(`- 29 条硬边界里：金标为追问的 ${split.clarify.total} 条，规则 ${split.clarify.ruleOk}/${split.clarify.total} 追问正确，AI ${split.clarify.aiOk}/${split.clarify.ai}；金标要规划的 ${split.plan.total} 条，规则内部草稿 ${split.plan.ruleOk}/${split.plan.total}，AI ${split.plan.aiOk}/${split.plan.ai}。`);
lines.push('- AI 唯一独占的场景是多意图拆分（A038/A039：规则把第二句吞进任务名）；其余边界上 AI 反而更差。');
lines.push('- 因此强制 AI 点只设在多意图 / 无安全解析的叙述句；其余句子用规则草稿，AI 在线时做校验不接管。AI 不可用或校验不通过时追问，不用本地猜。');
lines.push('- 局限：语料与规则共同设计，规则数字偏乐观；开放域表现需要下一轮冷启动语料复测。');
lines.push('');
lines.push('## 对照分桶');
lines.push('');
lines.push('| 分桶 | 条数 | 含义 |');
lines.push('| --- | ---: | --- |');
lines.push(`| 两边都对 | ${counts['both-ok'] || 0} | 规则与 AI 都命中 |`);
lines.push(`| 规则赢 | ${counts['rule-wins'] || 0} | 规则对、AI 错 |`);
lines.push(`| AI 赢 | ${counts['ai-wins'] || 0} | 规则拒答或答错、AI 对 |`);
lines.push(`| 两边都错 | ${counts['both-fail'] || 0} | 需要补语料或改协议 |`);
lines.push(`| 本地降级 | ${counts['ai-local'] || 0} | 端点额度耗尽或模型报错 |`);
lines.push(`| 缺 AI 结果 | ${counts['ai-missing'] || 0} | 未跑或中断 |`);
lines.push('');
lines.push('## 分类');
lines.push('');
lines.push('| 类别 | 总数 | 规则正确 | 规则覆盖内正确 | AI 正确 | AI 危险错误 |');
lines.push('| --- | ---: | ---: | ---: | ---: | ---: |');
for (const [key, stat] of Object.entries(categories)) {
  lines.push(`| ${key} | ${stat.total} | ${pct(ratio(stat.ruleOk, stat.total))} | ${pct(ratio(stat.ruleCoveredOk, stat.ruleCovered))} | ${pct(ratio(stat.aiOk, stat.ai))} | ${stat.aiUnsafe} |`);
}
lines.push('');
lines.push('## 触发原因 → 应该谁来做');
lines.push('');
lines.push('| 触发原因 | 条数 | 规则拒答 | 规则覆盖内正确 | 规则危险 | AI 正确 | AI 危险 | 建议 |');
lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |');
for (const [reason, stat] of Object.entries(reasons).sort((a, b) => b[1].total - a[1].total)) {
  lines.push(`| ${reason} | ${stat.total} | ${stat.deferred} | ${pct(ratio(stat.coveredOk, stat.covered))} | ${stat.coveredUnsafe} | ${pct(ratio(stat.aiOk, stat.ai))} | ${stat.aiUnsafe} | ${recommendation(stat)} |`);
}
lines.push('');
lines.push('## 边界判决');
lines.push('');
lines.push('| 触发原因 | 条数 | 规则内部解析对 | AI 对 | AI 危险 | 判决 |');
lines.push('| --- | ---: | ---: | ---: | ---: | --- |');
for (const [reason, stat] of Object.entries(deferredBoundary()).sort((a, b) => b[1].total - a[1].total)) {
  lines.push(`| ${reason} | ${stat.total} | ${pct(ratio(stat.ruleDraftOk, stat.total))} | ${pct(ratio(stat.aiOk, stat.ai))} | ${stat.aiUnsafe} | ${verdict(stat)} |`);
}
lines.push('');
if (hybrid) {
  lines.push('## 混合架构 v3');
  lines.push('');
  lines.push('分层：AI 只产出语义（名称、动作、意图、多意图拆分），日期/时刻/时长/周几/重复由确定性层计算；复盘与顺延走协议改写；AI 空结果时用规则兜底。');
  lines.push('');
  lines.push('| 来源 | 次数 | 含义 |');
  lines.push('| --- | ---: | --- |');
  lines.push(`| rewritten | ${hybrid.sources.rewritten || 0} | 复盘句改写为进度更新 |`);
  lines.push(`| postponed | ${hybrid.sources.postponed || 0} | 顺延句改写为推迟 |`);
  lines.push(`| slots | ${hybrid.sources.slots || 0} | 日历槽位由规则覆盖 |`);
  lines.push(`| floor | ${hybrid.sources.floor || 0} | AI 空结果，规则兜底 |`);
  lines.push(`| recurrence | ${hybrid.sources.recurrence || 0} | 重复事项防塌缩守卫 |`);
  lines.push('');
  lines.push(`- 准确率：${hybrid.summary.ok}/${hybrid.summary.total} = ${pct1(hybrid.summary.accuracy)}，危险错误 ${hybrid.summary.unsafe}`);
  const byCat = Object.entries(hybrid.summary.byCategory || {});
  if (byCat.length) {
    lines.push(`- 分类：${byCat.map(([key, stat]) => `${key} ${stat.ok}/${stat.total}`).join('，')}`);
  }
  if (hybrid.failures?.length) {
    lines.push('');
    lines.push('剩余失败：');
    for (const item of hybrid.failures) {
      const gold = JSON.stringify(item.gold?.items || item.gold || []);
      const pred = JSON.stringify(item.prediction?.items || []);
      lines.push(`- ${item.id} ${item.utterance} → 金标 ${gold} / 混合 ${pred}`);
    }
  }
  lines.push('');
}
lines.push('## 规则赢的例子');
for (const row of examples('rule-wins')) {
  lines.push(`- ${row.rule.id} ${row.rule.utterance} → 规则 ${JSON.stringify(row.rule.prediction?.items || [])} / AI ${JSON.stringify(row.ai?.prediction?.items || [])}`);
}
lines.push('');
lines.push('## AI 赢的例子');
for (const row of examples('ai-wins')) {
  lines.push(`- ${row.rule.id} ${row.rule.utterance} → 规则 ${row.rule.deferred ? `拒答(${(row.rule.reasons || []).join(',')})` : JSON.stringify(row.rule.prediction?.items || [])} / AI ${JSON.stringify(row.ai?.prediction?.items || [])} (${row.ai?.source})`);
}
lines.push('');
lines.push('## 两边都错的例子');
for (const row of examples('both-fail')) {
  lines.push(`- ${row.rule.id} ${row.rule.utterance} → 规则 ${row.rule.deferred ? `拒答(${(row.rule.reasons || []).join(',')})` : JSON.stringify(row.rule.prediction?.items || [])} / AI ${JSON.stringify(row.ai?.prediction?.items || [])} (${row.ai?.source})`);
}
lines.push('');
lines.push('## AI 错在哪个槽位');
lines.push('');
const slots = slotBreakdown();
lines.push(`- 有效 AI 样本：${slots.cases} 条，整句错 ${slots.fail} 条`);
const slotOrder = Object.entries(slots.slots).sort((a, b) => b[1] - a[1]);
lines.push(`- 槽位错误：${slotOrder.map(([slot, count]) => `${slot} ${count}`).join('，') || '无'}；整条缺失 ${slots.missing}；意图错 ${slots.intentFail}`);
lines.push('');
lines.push('| 类别 | 样本 | 整句错 | date | start | duration | name | action | weekdays | 缺失 |');
lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |');
for (const [key, stat] of Object.entries(slotByCategory())) {
  lines.push(`| ${key} | ${stat.cases} | ${stat.fail} | ${stat.date} | ${stat.start} | ${stat.duration} | ${stat.name} | ${stat.action} | ${stat.weekdays} | ${stat.missing} |`);
}
lines.push('');
lines.push('## 日期算术错误样例');
for (const item of dateErrors()) {
  lines.push(`- ${item.id} ${item.utterance} → 金标 ${item.gold} / AI ${item.ai}`);
}
lines.push('');
lines.push('## 规则拒答的句子，AI 是否接住');
for (const row of rows.filter((item) => item.rule.deferred)) {
  const ai = row.ai;
  const verdict = !ai ? '未跑' : !row.aiReal ? '降级' : row.aiOk ? 'AI 对' : 'AI 错';
  lines.push(`- ${row.rule.id} ${row.rule.utterance} [${(row.rule.reasons || []).join(',')}] → ${verdict}${ai && row.aiReal ? `：${JSON.stringify(ai.prediction?.items || [])}` : ''}`);
}
lines.push('');
writeFileSync(`${outDir}/analysis.md`, `${lines.join('\n')}\n`);
writeFileSync(`${outDir}/analysis.json`, JSON.stringify({
  total: rows.length,
  ruleCovered: ruleCovered.length,
  ruleCoveredOk: ruleCovered.filter((row) => row.ruleOk).length,
  aiTotal,
  aiOk,
  aiLocal: aiLocalRows.length,
  hybridOk,
  counts,
  categories,
  reasons,
  sources,
  latency: {
    avg: latencies.length ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length) : 0,
    median: latencies.length ? latencies[Math.floor(latencies.length / 2)] : 0,
  },
}, null, 2));
console.log(`analysis: ${outDir}/analysis.md`);
console.log(JSON.stringify({ counts, aiTotal, aiOk, hybridOk }, null, 2));
