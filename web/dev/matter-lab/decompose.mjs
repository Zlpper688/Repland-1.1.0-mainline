// Decomposes AI misses into semantic errors and calendar-arithmetic errors.
import { readFileSync } from 'node:fs';
import { buildCorpus } from './corpus.mjs';
import { matchItems, nameMatches } from './eval.mjs';

const cases = new Map(buildCorpus().map((item) => [item.id, item]));
const ruleRows = JSON.parse(readFileSync('dev/matter-lab/out/rule-results.json', 'utf8'));
const aiRows = readFileSync('dev/matter-lab/out/ai-results.jsonl', 'utf8')
  .split('\n')
  .filter(Boolean)
  .map((line) => JSON.parse(line));
const aiById = new Map(aiRows.map((row) => [row.id, row]));

const CALENDAR_SLOTS = ['date', 'start', 'duration', 'weekdays', 'dueDate', 'dueMinute'];
const stats = {
  total: 0,
  aiOk: 0,
  ruleOk: 0,
  aiSemanticOnly: 0,       // name + action right, some calendar slot wrong
  aiWrongSemantics: 0,     // name or action wrong / missing / empty
  aiIntentOnly: 0,         // intent wrong (clarify vs plan)
  fixableByRule: 0,        // semantic-only AND the rule got the whole case right
  semanticBadRuleOk: 0,    // AI semantics wrong but the rule got it right
  bothBad: 0,
};

const examples = { semanticOnly: [], wrongSemantics: [] };

for (const rule of ruleRows) {
  const ai = aiById.get(rule.id);
  const testCase = cases.get(rule.id);
  if (!ai || !testCase || testCase.gold?.intent !== 'plan' || testCase.gold?.review) continue;
  stats.total += 1;
  if (ai.eval?.ok) stats.aiOk += 1;
  if (rule.ok) stats.ruleOk += 1;
  if (ai.eval?.ok) continue;

  if (ai.eval?.intentOk === false) {
    stats.aiIntentOnly += 1;
    examples.wrongSemantics.push({ id: rule.id, utterance: rule.utterance, why: 'intent', gold: testCase.gold.items, ai: ai.prediction?.items });
    continue;
  }

  const { pairs, leftovers } = matchItems(testCase.gold.items || [], ai.prediction?.items || []);
  const badSemantics = leftovers.length > 0 || pairs.some(([gold, pred]) => !pred || !nameMatches(gold.name, pred.name) || gold.action !== pred.action);
  const calendarWrong = pairs.some(([gold, pred]) => {
    if (!pred) return false;
    return CALENDAR_SLOTS.some((slot) => {
      if (slot === 'dueDate' || slot === 'dueMinute') return gold[slot] != null && pred[slot] !== gold[slot];
      if (slot === 'weekdays') {
        if (gold.weekdays == null) return false;
        const a = [...new Set(gold.weekdays)].sort().join(',');
        const b = [...new Set(pred.weekdays || [])].sort().join(',');
        return a !== b;
      }
      return gold[slot] != null && pred[slot] !== gold[slot];
    });
  });

  if (badSemantics) {
    stats.aiWrongSemantics += 1;
    if (rule.ok) stats.semanticBadRuleOk += 1;
    if (examples.wrongSemantics.length < 12) examples.wrongSemantics.push({ id: rule.id, utterance: rule.utterance, why: 'semantics', gold: testCase.gold.items, ai: ai.prediction?.items });
    continue;
  }
  if (calendarWrong) {
    stats.aiSemanticOnly += 1;
    if (rule.ok) stats.fixableByRule += 1;
    if (examples.semanticOnly.length < 12) examples.semanticOnly.push({ id: rule.id, utterance: rule.utterance, gold: testCase.gold.items, ai: ai.prediction?.items });
    continue;
  }
  if (rule.ok) stats.semanticBadRuleOk += 1;
  else stats.bothBad += 1;
}

console.log(JSON.stringify(stats, null, 2));
console.log('\ncalendar-only misses:');
for (const item of examples.semanticOnly) {
  console.log(`${item.id} ${item.utterance}\n  gold ${JSON.stringify(item.gold)}\n  ai   ${JSON.stringify((item.ai || []).map((x) => ({ name: x.name, date: x.date, start: x.startMinute, duration: x.durationMinutes, weekdays: x.weekdays })))}`);
}
console.log('\nsemantic misses:');
for (const item of examples.wrongSemantics) {
  console.log(`${item.id} ${item.utterance} [${item.why}]\n  gold ${JSON.stringify(item.gold)}\n  ai   ${JSON.stringify((item.ai || []).map((x) => ({ action: x.action, name: x.name, date: x.date })))}`);
}