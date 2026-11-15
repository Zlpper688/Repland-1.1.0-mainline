// Adversarial routine checks: the model is mocked, the planner is real.
// Run: node dev/routine-smoke.mjs
import { strict as assert } from 'node:assert';
import { createFakeDatabase } from './fake-db.mjs';

process.env.REPLAND_ACCESS_CODE = 'repland-test';
process.env.AGNES_API_KEY = 'routine-test-key';
process.env.AGNES_BASE_URL = 'http://mock.agnes/v1';

let aiPayload = { say: '好。', intent: 'plan', items: [] };
let aiCalls = 0;
globalThis.fetch = async () => {
  aiCalls += 1;
  return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify(aiPayload) } }] }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });
};

const { application } = await import('../functions/routes.mjs');

const db = createFakeDatabase();
const handler = application({ db });
let token = null;

async function call(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (token) headers['x-repland-token'] = token;
  const response = await handler({
    request: new Request(`http://local/functions/v1/app${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    requestId: 'routine-smoke',
  });
  return { status: response.status, data: await response.json() };
}

function pass(label, detail = '') {
  console.log(`ok  ${label}${detail ? ` — ${detail}` : ''}`);
}

async function ask(message, payload) {
  aiPayload = payload;
  return call('/api/assistant', { method: 'POST', body: { message } });
}

const login = await call('/api/auth/login', {
  method: 'POST',
  body: { code: 'repland-test', name: '日常测试', deviceId: 'device-routine-0001' },
});
assert.equal(login.status, 200, JSON.stringify(login.data));
token = login.data.token;
let state = (await call('/api/bootstrap')).data;
const today = state.today;

const ALL_DAYS = [1, 2, 3, 4, 5, 6, 7];
const routine = (name, extra = {}) => ({ name, action: 'routine', ...extra });

// 1. The exact sentence that failed: daily + four hours + "看着空档".
const game = await ask('给我把玩王者荣耀这件事排成每天四个小时看着空挡和合适时间放', {
  say: '好，我按每天的空档来排。',
  intent: 'plan',
  items: [routine('玩王者荣耀', { durationMinutes: 240, weekdays: ALL_DAYS, date: today })],
});
assert.equal(game.status, 200, JSON.stringify(game.data));
assert.ok(game.data.proposal, JSON.stringify(game.data));
const gameItem = game.data.proposal.items[0];
assert.equal(gameItem.kind, 'routine');
assert.equal(gameItem.autoPicked, true, JSON.stringify(gameItem));
assert.equal(gameItem.startMinute, 18 * 60);
assert.equal(gameItem.endMinute, 22 * 60);
assert.equal(gameItem.date, null, 'a daily routine must not collapse to one day');
assert.equal(gameItem.weekdays.length, 7);
const gameDone = await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: game.data.proposal.id } });
const gameBlocks = gameDone.data.fixedBlocks.filter((block) => block.title === '玩王者荣耀');
assert.equal(gameBlocks.length, 7, JSON.stringify(gameBlocks));
assert.ok(gameBlocks.every((block) => block.weekday != null && block.specific_date == null));
assert.ok(gameBlocks.every((block) => block.start_minute === 18 * 60 && block.end_minute === 22 * 60));
assert.equal(gameDone.data.tasks.length, 0, 'routines never create tasks');
pass('delegated daily 4h routine picks the evening window', '18:00-22:00 × 7');

// 2. A spoken preference steers the picker.
const meditate = await ask('再给我每天排四个小时冥想，早上找空档', {
  say: '好，早上排。',
  intent: 'plan',
  items: [routine('冥想', { durationMinutes: 240, weekdays: ALL_DAYS })],
});
assert.ok(meditate.data.proposal, JSON.stringify(meditate.data));
assert.equal(meditate.data.proposal.items[0].startMinute, 8 * 60);
assert.equal(meditate.data.proposal.items[0].endMinute, 12 * 60);
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: meditate.data.proposal.id } });
pass('morning preference picks 08:00-12:00');

// 3. A model slot the user never asked for must yield to the facts.
const run = await ask('每天跑步一小时，挑个合适的时间', {
  say: '好。',
  intent: 'plan',
  items: [routine('跑步', { durationMinutes: 60, weekdays: ALL_DAYS, start: '19:00', end: '20:00' })],
});
assert.ok(run.data.proposal, JSON.stringify(run.data));
assert.equal(run.data.proposal.items[0].autoPicked, true, '19:00 collides with the game block');
assert.equal(run.data.proposal.items[0].startMinute, 17 * 60);
assert.equal(run.data.proposal.items[0].endMinute, 18 * 60);
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: run.data.proposal.id } });
pass('unpinned model slot that collides is re-picked from free gaps');

// 4. Reversed model clocks are recovered, not echoed back as an error.
const basket = await ask('周末打篮球两小时，看着空档', {
  say: '好。',
  intent: 'plan',
  items: [routine('打篮球', { durationMinutes: 120, weekdays: [6, 7], start: '22:00', end: '20:00' })],
});
assert.ok(basket.data.proposal, JSON.stringify(basket.data));
assert.equal(basket.data.proposal.items[0].autoPicked, true);
assert.ok(basket.data.proposal.items[0].endMinute - basket.data.proposal.items[0].startMinute === 120);
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: basket.data.proposal.id } });
pass('reversed model times fall back to a free slot');

// 5. Dropped weekdays must not turn "每天" into one day.
const words = await ask('每天背单词一小时，看着空档放', {
  say: '好。',
  intent: 'plan',
  items: [routine('背单词', { durationMinutes: 60 })],
});
assert.ok(words.data.proposal, JSON.stringify(words.data));
assert.equal(words.data.proposal.items[0].weekdays.length, 7);
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: words.data.proposal.id } });
const wordBlocks = (await call('/api/bootstrap')).data.fixedBlocks.filter((block) => block.title === '背单词');
assert.equal(wordBlocks.length, 7);
pass('weekdays recovered from the sentence', '每天 → 7 rows');

// 6. remove + create for the same name is one atomic replacement.
const swapped = await ask('把玩王者荣耀改成每天两小时，看着空档排', {
  say: '好，换成两小时。',
  intent: 'plan',
  items: [
    routine('玩王者荣耀', { remove: true }),
    routine('玩王者荣耀', { durationMinutes: 120, weekdays: ALL_DAYS }),
  ],
});
assert.ok(swapped.data.proposal, JSON.stringify(swapped.data));
const swappedItem = swapped.data.proposal.items[0];
assert.equal(swapped.data.proposal.items.length, 1, 'remove+create must merge into one item');
assert.equal(swappedItem.replace, true);
assert.equal(swappedItem.blockIds.length, 7);
assert.equal(swappedItem.weekdays.length, 7);
const swappedDone = await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: swapped.data.proposal.id } });
const swappedBlocks = swappedDone.data.fixedBlocks.filter((block) => block.title === '玩王者荣耀');
assert.equal(swappedBlocks.length, 7, JSON.stringify(swappedBlocks));
assert.ok(swappedBlocks.every((block) => block.end_minute - block.start_minute === 120));
const undoneSwap = await call('/api/undo', { method: 'POST' });
const restoredGame = undoneSwap.data.fixedBlocks.filter((block) => block.title === '玩王者荣耀');
assert.equal(restoredGame.length, 7);
assert.ok(restoredGame.every((block) => block.start_minute === 18 * 60 && block.end_minute === 22 * 60), 'undo restores the old routine');
pass('routine edit is one replace and undo restores it');

// 7. A confused remove in an edit sentence still lands as an edit.
const converted = await ask('把玩王者荣耀改成每天一小时，看着空档', {
  say: '好。',
  intent: 'plan',
  items: [routine('玩王者荣耀', { remove: true, weekdays: ALL_DAYS })],
});
assert.ok(converted.data.proposal, JSON.stringify(converted.data));
const convertedItem = converted.data.proposal.items[0];
assert.equal(convertedItem.remove, false);
assert.equal(convertedItem.replace, true);
assert.equal(convertedItem.blockIds.length, 7);
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: converted.data.proposal.id } });
const convertedBlocks = (await call('/api/bootstrap')).data.fixedBlocks.filter((block) => block.title === '玩王者荣耀');
assert.equal(convertedBlocks.length, 7);
assert.ok(convertedBlocks.every((block) => block.end_minute - block.start_minute === 60));
await call('/api/undo', { method: 'POST' });
pass('remove-only edit sentence becomes a replace, not a dead end');

// 8. Cancelling something that does not exist answers with what does exist.
const missCancel = await ask('取消每天的游泳安排', {
  say: '好。',
  intent: 'plan',
  items: [routine('游泳', { remove: true })],
});
assert.equal(missCancel.status, 200, JSON.stringify(missCancel.data));
assert.equal(missCancel.data.proposal, null);
assert.match(missCancel.data.reply, /没找到「游泳」/);
assert.match(missCancel.data.reply, /玩王者荣耀/);
pass('cancel no-match lists the real routines');

// 9. Cancelling something that does exist removes every weekday row.
const cancelRun = await ask('取消每天跑步的固定安排', {
  say: '好。',
  intent: 'plan',
  items: [routine('跑步', { remove: true })],
});
assert.ok(cancelRun.data.proposal, JSON.stringify(cancelRun.data));
assert.equal(cancelRun.data.proposal.items[0].remove, true);
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: cancelRun.data.proposal.id } });
assert.equal((await call('/api/bootstrap')).data.fixedBlocks.filter((block) => block.title === '跑步').length, 0);
pass('cancel removes the routine rows');

// 10. No common window: answer with the longest one instead of a raw failure.
const stretch = await ask('每天拉伸十二个小时，看着空档', {
  say: '好。',
  intent: 'plan',
  items: [routine('拉伸', { durationMinutes: 720, weekdays: ALL_DAYS })],
});
assert.equal(stretch.data.proposal, null);
assert.match(stretch.data.reply, /没有共同的长空档/);
assert.match(stretch.data.reply, /最长的一段是 \d{2}:\d{2}–\d{2}:\d{2}/);
pass('impossible duration asks with the longest shared gap');

// 11. A pinned start that overflows midnight asks for the concrete fix.
const lateGame = await ask('每天拉伸晚上10点开始，四小时', {
  say: '好。',
  intent: 'plan',
  items: [routine('拉伸', { durationMinutes: 240 })],
});
assert.equal(lateGame.data.proposal, null, JSON.stringify(lateGame.data));
assert.match(lateGame.data.reply, /次日 02:00/);
assert.match(lateGame.data.reply, /缩短到 120 分钟/);
pass('overflowing pinned start asks instead of failing');

// 12. A reversed pinned pair is a real question, not a silent guess.
const backwards = await ask('把打篮球改成周六22点到18点', {
  say: '好。',
  intent: 'plan',
  items: [routine('打篮球', { durationMinutes: 120, weekdays: [6] })],
});
assert.equal(backwards.data.proposal, null);
assert.match(backwards.data.reply, /倒着的/);
assert.match(backwards.data.reply, /18:00–22:00/);
pass('reversed pinned clocks ask one clear question');

// 13. A pinned pair is used exactly as spoken.
const badminton = await ask('每周六下午1点到2点打羽毛球', {
  say: '好。',
  intent: 'plan',
  items: [routine('打羽毛球', { weekdays: [6] })],
});
assert.ok(badminton.data.proposal, JSON.stringify(badminton.data));
const badmintonItem = badminton.data.proposal.items[0];
assert.equal(badmintonItem.autoPicked, false);
assert.equal(badmintonItem.startMinute, 13 * 60);
assert.equal(badmintonItem.endMinute, 14 * 60);
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: badminton.data.proposal.id } });
const badmintonBlocks = (await call('/api/bootstrap')).data.fixedBlocks.filter((block) => block.title === '打羽毛球');
assert.equal(badmintonBlocks.length, 1);
assert.equal(badmintonBlocks[0].weekday, 6);
pass('pinned pair keeps the spoken clock and weekday');

// 14. Repeating the same request is idempotent.
const repeat = await ask('给我把玩王者荣耀这件事排成每天四个小时看着空挡和合适时间放', {
  say: '好，我按每天的空档来排。',
  intent: 'plan',
  items: [routine('玩王者荣耀', { durationMinutes: 240, weekdays: ALL_DAYS, date: today })],
});
assert.equal(repeat.data.proposal, null, JSON.stringify(repeat.data));
assert.match(repeat.data.reply, /已经是固定安排了/);
pass('same routine again reports it already exists');

// 15. 「八点二十分」里的分钟是钟点，不是 20 分钟的时长。
const reading = await ask('每天八点二十分读英语两小时', {
  say: '好。',
  intent: 'plan',
  items: [routine('读英语', { weekdays: ALL_DAYS })],
});
assert.ok(reading.data.proposal, JSON.stringify(reading.data));
const readingItem = reading.data.proposal.items[0];
assert.equal(readingItem.startMinute, 8 * 60 + 20, JSON.stringify(readingItem));
assert.equal(readingItem.endMinute, 10 * 60 + 20, JSON.stringify(readingItem));
assert.equal(readingItem.autoPicked, false);
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: reading.data.proposal.id } });
pass('minutes inside a clock are not read as a duration', '08:20-10:20');

// 16. A spoken duration overrides a wrong model field.
const writing = await ask('每天写一小时字，看着空档', {
  say: '好。',
  intent: 'plan',
  items: [routine('练字', { durationMinutes: 30, weekdays: ALL_DAYS })],
});
assert.ok(writing.data.proposal, JSON.stringify(writing.data));
const writingItem = writing.data.proposal.items[0];
assert.equal(writingItem.endMinute - writingItem.startMinute, 60, JSON.stringify(writingItem));
await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: writing.data.proposal.id } });
pass('spoken duration beats a wrong model duration', '一 小时 → 60');

// 17. 23:00 到 01:00 是跨夜，不是把钟点说反了；问句必须给出拆分的选项。
const nightly = await ask('每天晚上11点到凌晨1点打游戏', {
  say: '好。',
  intent: 'plan',
  items: [routine('打游戏', { weekdays: ALL_DAYS })],
});
assert.equal(nightly.data.proposal, null, JSON.stringify(nightly.data));
assert.match(nightly.data.reply, /跨过午夜/);
assert.match(nightly.data.reply, /23:00–24:00/);
assert.match(nightly.data.reply, /00:00–01:00/);
assert.doesNotMatch(nightly.data.reply, /要按 01:00–23:00/);
pass('a cross-midnight pair asks how to split it');

assert.ok(aiCalls >= 17, `model was actually consulted (${aiCalls} calls)`);
console.log('\nAll routine checks passed.');