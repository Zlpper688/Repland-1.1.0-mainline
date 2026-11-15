// Online acceptance for the deployed site: the rule floor and the review
// protocol must hold on the production functions, with the model live.
import { LiveClient } from './live.mjs';

const client = new LiveClient();
await client.login();
await client.reset();

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok });
  console.log(`${ok ? 'ok ' : 'FAIL'}  ${name}${ok ? '' : ` -> ${JSON.stringify(detail).slice(0, 300)}`}`);
}

const hard = await client.ask('把那个会挪到四点');
check('pronoun asks instead of guessing', !hard.data?.proposal, hard.data);

const query = await client.ask('这周还剩几个小时');
check('a question stays a question', !query.data?.proposal && Boolean(query.data?.reply), query.data);

const multi = await client.ask('明天上午十点开会，然后下午三点写报告');
const multiItems = multi.data?.proposal?.items || [];
check(
  'two intents never collapse to one silent block',
  !multi.data?.proposal || multiItems.length === 2,
  multi.data,
);
if (multi.data?.proposal) await client.request('/api/assistant/cancel', { method: 'POST', body: { proposalId: multi.data.proposal.id } });

await client.seed({ name: '吃饭', date: '2026-11-15', startMinute: 720, durationMinutes: 30 });
const move = await client.ask('吃饭改到明天下午三点');
const moveItem = move.data?.proposal?.items?.[0];
check(
  'a move keeps the sentence date on the deployed route',
  movementOk(moveItem),
  move.data,
);
if (move.data?.proposal) await client.request('/api/assistant/cancel', { method: 'POST', body: { proposalId: move.data.proposal.id } });

await client.seed({ name: '背单词', date: '2026-11-15', startMinute: 420, durationMinutes: 30 });
const review = await client.ask('背单词没背完，后天补四十分钟');
const reviewItems = review.data?.proposal?.items || [];
const progress = reviewItems.find((item) => item.action === 'progress');
const extra = reviewItems.find((item) => item.date === '2026-11-17' && item.durationMinutes === 40);
check(
  'an undone report keeps the block and adds the stated time',
  Boolean(progress) && Boolean(extra) && !/推到|延到/.test(review.data?.reply || ''),
  review.data,
);
if (review.data?.proposal) await client.request('/api/assistant/cancel', { method: 'POST', body: { proposalId: review.data.proposal.id } });

function movementOk(item) {
  if (!item) return false;
  const date = item.date ?? item.placement?.date;
  const move = item.move === true || item.action === 'schedule';
  return move && date === '2026-11-16' && (item.startMinute === 900 || item.placement?.startMinute === 900);
}

const failed = results.filter((row) => !row.ok);
console.log(failed.length ? `${failed.length} online checks failed` : 'all online checks passed');
process.exit(failed.length ? 1 : 0);
