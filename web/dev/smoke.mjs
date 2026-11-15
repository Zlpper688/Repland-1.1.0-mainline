// End-to-end route check against the in-memory database. Run: node dev/smoke.mjs
import { strict as assert } from 'node:assert';
import { createFakeDatabase } from './fake-db.mjs';
import { application } from '../functions/routes.mjs';
import { earliestPlacementMinute, freeWindows, planDay } from '../functions/planner.mjs';

process.env.REPLAND_ACCESS_CODE = 'repland-test';
process.env.REPLAND_TEAM_CODE = 'repland-smoke-test';
delete process.env.AGNES_API_KEY;

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
    requestId: 'smoke',
  });
  return { status: response.status, data: await response.json() };
}

function pass(label, detail = '') {
  console.log(`ok  ${label}${detail ? ` — ${detail}` : ''}`);
}

const health = await call('/api/health');
assert.equal(health.status, 200);
assert.equal(health.data.ok, true);
assert.equal(health.data.aiConfigured, false);
pass('health');

// 今天已经过去的钟点不是空档：自由落点和点名落点都得从此刻之后开始，空档列表也不能列上午。
const staticFloor = earliestPlacementMinute(19 * 60 + 3);
assert.equal(staticFloor, 19 * 60 + 10);
const freePlan = planDay([{ name: '下午的复习', durationMinutes: 60 }], [], { earliestStartMinute: staticFloor });
assert.equal(freePlan.placements[0].startMinute, staticFloor);
const pastPinnedPlan = planDay(
  [{ name: '早上的复习', durationMinutes: 60, explicitStartMinute: 8 * 60 }],
  [],
  { earliestStartMinute: staticFloor },
);
assert.equal(pastPinnedPlan.placements[0].startMinute, staticFloor);
assert.equal(pastPinnedPlan.placements[0].fromUser, false);
const clippedWindows = freeWindows('2026-11-14', [], { fromMinute: 19 * 60 });
assert.equal(clippedWindows[0].startMinute, 19 * 60);
pass('past hours are not placeable', `${staticFloor}`);

const guest = await call('/api/auth/guest', {
  method: 'POST',
  body: { deviceId: 'device-guest-0001' },
});
assert.equal(guest.status, 200, JSON.stringify(guest.data));
assert.ok(guest.data.token);
assert.ok(guest.data.profile.name.startsWith('朋友'));
token = guest.data.token;
const guestState = await call('/api/bootstrap');
assert.equal(guestState.status, 200);
assert.equal(guestState.data.tasks.length, 0);
const guestAgain = await call('/api/auth/guest', {
  method: 'POST',
  body: { deviceId: 'device-guest-0001' },
});
assert.equal(guestAgain.data.profile.id, guest.data.profile.id);
token = null;
pass('guest session', guest.data.profile.name);

const login = await call('/api/auth/login', {
  method: 'POST',
  body: { code: 'repland-test', name: '小满测试', deviceId: 'device-test-0001' },
});
assert.equal(login.status, 200, JSON.stringify(login.data));
token = login.data.token;
pass('login', login.data.profile.name);

const badLogin = await call('/api/auth/login', {
  method: 'POST',
  body: { code: 'wrong-code', name: '小满测试', deviceId: 'device-test-0001' },
});
assert.equal(badLogin.status, 401);
pass('wrong access code rejected');

const state = (await call('/api/bootstrap')).data;
const tomorrowObj = new Date(`${state.today}T00:00:00Z`);
tomorrowObj.setUTCDate(tomorrowObj.getUTCDate() + 1);
const tomorrowText = tomorrowObj.toISOString().slice(0, 10);
assert.equal(state.tasks.length, 0);
assert.equal(state.today, new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date()));
pass('bootstrap', state.today);

const created = await call('/api/tasks', {
  method: 'POST',
  body: { name: '读《人类简史》', durationMinutes: 45, priority: 'MEDIUM', category: 'LEISURE' },
});
assert.equal(created.status, 200);
const book = created.data.tasks.find((task) => task.name === '读《人类简史》');
assert.ok(book);
assert.equal(book.planned_date, null, 'save-only task must not get a planned date');
pass('manual save-only task');

const scheduledCreate = await call('/api/tasks', {
  method: 'POST',
  body: { name: '整理笔记', durationMinutes: 30, date: tomorrowText, schedule: true },
});
const notes = scheduledCreate.data.tasks.find((task) => task.name === '整理笔记');
assert.equal(notes.planned_date, tomorrowText);
assert.ok(scheduledCreate.data.segments.some((segment) => segment.task_id === notes.id));
pass('scheduled creation keeps a planned date');

const progressed = await call(`/api/tasks/${book.id}`, { method: 'PATCH', body: { progress: 40 } });
const bookProgressed = progressed.data.tasks.find((task) => task.id === book.id);
assert.equal(bookProgressed.status, 'IN_PROGRESS');
assert.equal(bookProgressed.progress, 40);
pass('progress update');

const colloquial = await call('/api/assistant', {
  method: 'POST',
  body: { message: '明天下午三点复习线代，大概一个半小时' },
});
assert.equal(colloquial.data.source, 'local');
const colloquialItem = colloquial.data.proposal.items[0];
assert.equal(colloquialItem.name, '复习线代', JSON.stringify(colloquial.data.proposal));
assert.equal(colloquialItem.durationMinutes, 90);
assert.ok(colloquialItem.placement);
assert.equal(colloquialItem.placement.startMinute, 15 * 60);
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: colloquial.data.proposal.id } });
pass('colloquial chinese time and duration');

const saveOnly = await call('/api/assistant', {
  method: 'POST',
  body: { message: '只记着 周五前交物理实验报告 90分钟' },
});
assert.equal(saveOnly.data.source, 'local');
const saveItem = saveOnly.data.proposal.items[0];
assert.equal(saveItem.wantsSchedule, false);
assert.equal(saveItem.date, null);
assert.equal(saveItem.placement, null);
const saveConfirm = await call('/api/assistant/confirm', {
  method: 'POST',
  body: { proposalId: saveOnly.data.proposal.id },
});
const paper = saveConfirm.data.tasks.find((task) => task.name.includes('物理实验报告'));
assert.ok(paper);
assert.equal(paper.planned_date, null);
assert.equal(saveConfirm.data.segments.filter((segment) => segment.task_id === paper.id).length, 0);
pass('assistant save-only proposal');

const scheduled = await call('/api/assistant', {
  method: 'POST',
  body: { message: '明天下午3点 复习高数 60分钟' },
});
assert.equal(scheduled.data.source, 'local');
const scheduledItem = scheduled.data.proposal.items[0];
assert.ok(scheduledItem.placement, JSON.stringify(scheduled.data.proposal));
assert.equal(scheduledItem.placement.startMinute, 15 * 60);
assert.equal(scheduledItem.date, new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date(Date.now() + 86_400_000)));
const confirmed = await call('/api/assistant/confirm', {
  method: 'POST',
  body: { proposalId: scheduled.data.proposal.id },
});
const math = confirmed.data.tasks.find((task) => task.name.includes('复习高数'));
assert.ok(math);
const mathSegment = confirmed.data.segments.find((segment) => segment.task_id === math.id);
assert.ok(mathSegment);
assert.equal(mathSegment.start_minute, 900);
assert.equal(mathSegment.end_minute, 960);
pass('assistant scheduled proposal', `${mathSegment.on_date} ${mathSegment.start_minute}`);

const undone = await call('/api/undo', { method: 'POST' });
assert.equal(undone.status, 200, JSON.stringify(undone.data));
assert.ok(!undone.data.tasks.some((task) => task.id === math.id));
assert.ok(!undone.data.segments.some((segment) => segment.id === mathSegment.id));
pass('undo last assistant operation');

const again = await call('/api/assistant', {
  method: 'POST',
  body: { message: '明天下午3点 复习高数 60分钟' },
});
const reconfirmed = await call('/api/assistant/confirm', {
  method: 'POST',
  body: { proposalId: again.data.proposal.id },
});
const mathAgain = reconfirmed.data.tasks.find((task) => task.name.includes('复习高数'));
const mathSegmentAgain = reconfirmed.data.segments.find((segment) => segment.task_id === mathAgain.id);
const moved = await call(`/api/segments/${mathSegmentAgain.id}`, {
  method: 'PATCH',
  body: { startMinute: 16 * 60, endMinute: 17 * 60 },
});
assert.equal(moved.status, 200, JSON.stringify(moved.data));
const movedSegment = moved.data.segments.find((segment) => segment.id === mathSegmentAgain.id);
assert.equal(movedSegment.start_minute, 960);
pass('move segment');

const reschedule = await call('/api/assistant', {
  method: 'POST',
  body: { message: '把复习高数改到下午5点' },
});
assert.equal(reschedule.status, 200, JSON.stringify(reschedule.data));
const rescheduleItem = reschedule.data.proposal.items[0];
assert.equal(rescheduleItem.taskId, mathAgain.id, JSON.stringify(reschedule.data.proposal));
assert.equal(rescheduleItem.move, true);
assert.equal(rescheduleItem.placement.startMinute, 17 * 60);
const rescheduleConfirm = await call('/api/assistant/confirm', {
  method: 'POST',
  body: { proposalId: reschedule.data.proposal.id },
});
assert.equal(rescheduleConfirm.status, 200, JSON.stringify(rescheduleConfirm.data));
assert.equal(rescheduleConfirm.data.tasks.filter((task) => task.name.includes('复习高数')).length, 1);
const movedSegments = rescheduleConfirm.data.segments.filter((segment) => segment.task_id === mathAgain.id);
assert.equal(movedSegments.length, 1, 'moving must replace the old slot, not add one');
assert.equal(movedSegments[0].start_minute, 17 * 60);
pass('chat reschedule replaces the existing slot');

const draft = await call('/api/assistant', {
  method: 'POST',
  body: { message: '明天下午三点复习线代 90分钟' },
});
assert.ok(draft.data.proposal);
const revised = await call('/api/assistant', { method: 'POST', body: { message: '改到晚上八点' } });
assert.equal(revised.status, 200, JSON.stringify(revised.data));
assert.ok(revised.data.proposal, JSON.stringify(revised.data));
assert.equal(revised.data.proposal.items[0].name, '复习线代');
assert.equal(revised.data.proposal.items[0].placement.startMinute, 20 * 60);
const staleConfirm = await call('/api/assistant/confirm', { method: 'POST', body: { proposalId: draft.data.proposal.id } });
assert.equal(staleConfirm.status, 409);
const revisedConfirm = await call('/api/assistant/confirm', {
  method: 'POST',
  body: { proposalId: revised.data.proposal.id },
});
const linear = revisedConfirm.data.tasks.filter((task) => task.name.includes('复习线代'));
assert.equal(linear.length, 1);
const linearSegments = revisedConfirm.data.segments.filter((segment) => segment.task_id === linear[0].id);
assert.equal(linearSegments.length, 1);
assert.equal(linearSegments[0].start_minute, 20 * 60);
pass('pending draft revised by just talking');

const dropDraft = await call('/api/assistant', {
  method: 'POST',
  body: { message: '明天上午十点写实验报告 60分钟' },
});
assert.ok(dropDraft.data.proposal);
const dropped = await call('/api/assistant', { method: 'POST', body: { message: '算了' } });
assert.equal(dropped.status, 200);
assert.equal(dropped.data.proposal, null);
assert.equal((await call('/api/bootstrap')).data.pendingProposal, null);
pass('pending draft dropped by talking');

const spreadDateObj = new Date(`${state.today}T00:00:00Z`);
spreadDateObj.setUTCDate(spreadDateObj.getUTCDate() + 2);
const spreadDayText = spreadDateObj.toISOString().slice(0, 10);
for (const [name, start] of [
  ['预习线性代数', '09:00'],
  ['整理实验数据', '10:00'],
]) {
  const made = await call('/api/tasks', { method: 'POST', body: { name, durationMinutes: 60 } });
  const madeTask = made.data.tasks.find((task) => task.name === name);
  const placed = await call(`/api/tasks/${madeTask.id}/schedule`, {
    method: 'POST',
    body: { date: spreadDayText, start },
  });
  assert.equal(placed.status, 200, JSON.stringify(placed.data));
}
const spreadAsk = await call('/api/assistant', { method: 'POST', body: { message: '把后天的事排开点' } });
assert.equal(spreadAsk.status, 200, JSON.stringify(spreadAsk.data));
assert.ok(spreadAsk.data.proposal, JSON.stringify(spreadAsk.data));
assert.equal(spreadAsk.data.proposal.items.length, 1, JSON.stringify(spreadAsk.data.proposal.items));
assert.ok(spreadAsk.data.proposal.items.every((item) => item.move));
const spreadConfirm = await call('/api/assistant/confirm', {
  method: 'POST',
  body: { proposalId: spreadAsk.data.proposal.id },
});
const spreadSegments = spreadConfirm.data.segments
  .filter((segment) => segment.on_date === spreadDayText)
  .sort((a, b) => a.start_minute - b.start_minute);
assert.equal(spreadSegments.length, 2, JSON.stringify(spreadSegments));
assert.ok(spreadSegments[1].start_minute - spreadSegments[0].end_minute >= 30, JSON.stringify(spreadSegments));
assert.equal(spreadConfirm.data.tasks.filter((task) => task.name === '预习线性代数').length, 1);
assert.equal(spreadConfirm.data.tasks.filter((task) => task.name === '整理实验数据').length, 1);
assert.equal(spreadConfirm.data.tasks.filter((task) => task.name.includes('排开')).length, 0);
pass('spread day moves existing slots instead of creating tasks');

const draftSpreadDateObj = new Date(`${state.today}T00:00:00Z`);
draftSpreadDateObj.setUTCDate(draftSpreadDateObj.getUTCDate() + 3);
const draftSpreadDate = draftSpreadDateObj.toISOString().slice(0, 10);
const draftItems = ['读两章论文', '整理问卷'].map((name, index) => {
  const startMinute = 9 * 60 + index * 60;
  return {
    itemId: crypto.randomUUID(),
    kind: 'create',
    action: 'create',
    taskId: crypto.randomUUID(),
    name,
    durationMinutes: 60,
    priority: 'MEDIUM',
    category: 'UNSPECIFIED',
    wantsSchedule: true,
    date: draftSpreadDate,
    startMinute: null,
    placement: { id: crypto.randomUUID(), date: draftSpreadDate, startMinute, endMinute: startMinute + 60, trackId: 'focus', fromUser: false },
    existingTask: false,
    move: false,
    replaceSegmentIds: [],
    moveFrom: null,
  };
});
await db.from('proposals').insert({
  id: crypto.randomUUID(),
  user_id: login.data.profile.id,
  payload: { intent: 'plan', source: 'local', reply: '草案', summary: '草案', items: draftItems, unplaced: [], activeDate: state.today, adjustedPinned: false },
  status: 'pending',
  created_at: new Date().toISOString(),
});
const draftSpreadAsk = await call('/api/assistant', { method: 'POST', body: { message: '大后天排开点' } });
assert.equal(draftSpreadAsk.status, 200, JSON.stringify(draftSpreadAsk.data));
assert.ok(draftSpreadAsk.data.proposal, JSON.stringify(draftSpreadAsk.data));
assert.equal(draftSpreadAsk.data.proposal.items.length, 2, JSON.stringify(draftSpreadAsk.data.proposal.items));
const draftSpreadConfirm = await call('/api/assistant/confirm', {
  method: 'POST',
  body: { proposalId: draftSpreadAsk.data.proposal.id },
});
const draftSpreadSegments = draftSpreadConfirm.data.segments
  .filter((segment) => segment.on_date === draftSpreadDate)
  .sort((a, b) => a.start_minute - b.start_minute);
assert.equal(draftSpreadSegments.length, 2, JSON.stringify(draftSpreadSegments));
assert.ok(draftSpreadSegments[1].start_minute - draftSpreadSegments[0].end_minute >= 30, JSON.stringify(draftSpreadSegments));
pass('spread applies to a waiting draft too');

const plan = await call('/api/plan', { method: 'POST', body: { mode: 'fill', horizonDays: 3 } });
assert.equal(plan.status, 200);
assert.ok(plan.data.planResult.created > 0);
pass('auto plan', plan.data.planResult.message);

const focusStart = await call('/api/focus/start', { method: 'POST', body: { taskId: book.id } });
assert.equal(focusStart.status, 200, JSON.stringify(focusStart.data));
const activeFocus = focusStart.data.focusSessions.find((session) => !session.ended_at);
assert.ok(activeFocus);
const focusEnd = await call('/api/focus/end', {
  method: 'POST',
  body: { sessionId: activeFocus.id, minutes: 25, progress: 65 },
});
assert.equal(focusEnd.status, 200);
const focusedBook = focusEnd.data.tasks.find((task) => task.id === book.id);
assert.equal(focusedBook.actual_minutes, 25);
assert.equal(focusedBook.progress, 65);

const plainFocus = await call('/api/focus/start', { method: 'POST', body: { taskId: book.id } });
const plainSession = plainFocus.data.focusSessions.find((session) => !session.ended_at);
const plainEnd = await call('/api/focus/end', { method: 'POST', body: { sessionId: plainSession.id, minutes: 5 } });
const bookAfterPlain = plainEnd.data.tasks.find((task) => task.id === book.id);
assert.equal(bookAfterPlain.actual_minutes, 30);
assert.equal(bookAfterPlain.progress, 65);
pass('focus session records time without progress change');

const slotTaskCreated = await call('/api/tasks', {
  method: 'POST',
  body: { name: '准备小组展示', durationMinutes: 30, priority: 'HIGH' },
});
const slotTask = slotTaskCreated.data.tasks.find((task) => task.name === '准备小组展示');
const slot = await call(`/api/tasks/${slotTask.id}/schedule`, {
  method: 'POST',
  body: { date: tomorrowText, start: '10:00' },
});
assert.equal(slot.status, 200, JSON.stringify(slot.data));
assert.equal(slot.data.placed.startMinute, 600);
assert.ok(slot.data.segments.some((segment) => segment.task_id === slotTask.id && segment.on_date === tomorrowText));
pass('schedule existing task');

// 确认一份几小时前生成的草案时，落点已经过去：整份不写，并说清要重排。
const staleDate = new Date(`${state.today}T00:00:00Z`);
staleDate.setUTCDate(staleDate.getUTCDate() - 1);
const staleDateText = staleDate.toISOString().slice(0, 10);
const staleProposalId = crypto.randomUUID();
await db.from('proposals').insert({
  id: staleProposalId,
  user_id: login.data.profile.id,
  payload: {
    intent: 'plan',
    source: 'local',
    reply: '草案',
    summary: '草案',
    activeDate: state.today,
    adjustedPinned: false,
    unplaced: [],
    items: [
      {
        itemId: crypto.randomUUID(),
        kind: 'create',
        action: 'create',
        taskId: crypto.randomUUID(),
        name: '过期的复习',
        durationMinutes: 60,
        priority: 'MEDIUM',
        category: 'UNSPECIFIED',
        wantsSchedule: true,
        date: staleDateText,
        startMinute: null,
        placement: { id: crypto.randomUUID(), date: staleDateText, startMinute: 540, endMinute: 600, trackId: 'focus', fromUser: false },
        existingTask: false,
        move: false,
        replaceSegmentIds: [],
        moveFrom: null,
      },
    ],
  },
  status: 'pending',
  created_at: new Date().toISOString(),
});
const expiredConfirm = await call('/api/assistant/confirm', {
  method: 'POST',
  body: { proposalId: staleProposalId },
});
assert.equal(expiredConfirm.status, 200, JSON.stringify(expiredConfirm.data));
assert.equal(expiredConfirm.data.executed?.expired, true, JSON.stringify(expiredConfirm.data));
assert.match(expiredConfirm.data.executed.reply, /已经过了/);
assert.ok(!expiredConfirm.data.tasks.some((task) => task.name === '过期的复习'));
pass('confirming a draft whose slot has passed writes nothing');

const summary = await call('/api/summary');
assert.equal(summary.status, 200);
assert.match(summary.data.text, /过去 30 天/);
assert.ok(summary.data.facts.focusMinutes >= 25);
pass('summary', summary.data.text);

const nextWeek = await call('/api/assistant', {
  method: 'POST',
  body: { message: '下周三 复习线性代数 60分钟' },
});
assert.equal(nextWeek.status, 200);
const nextWeekDate = nextWeek.data.proposal.items[0].date;
const today = new Date(`${state.today}T00:00:00Z`);
const weekday = today.getUTCDay() === 0 ? 7 : today.getUTCDay();
const expected = new Date(today);
expected.setUTCDate(today.getUTCDate() + (((8 - weekday) % 7) || 7) + 2);
assert.equal(nextWeekDate, expected.toISOString().slice(0, 10));
pass('next-week date math', nextWeekDate);
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: nextWeek.data.proposal.id } });

// ---------- assistant continuity and status words ----------
const mealCreated = await call('/api/tasks', { method: 'POST', body: { name: '吃饭', durationMinutes: 30 } });
const meal = mealCreated.data.tasks.find((task) => task.name === '吃饭');
assert.ok(meal);

const localAsk = await call('/api/assistant', { method: 'POST', body: { message: '帮我排在今天中午一点' } });
assert.equal(localAsk.status, 200, JSON.stringify(localAsk.data));
assert.equal(localAsk.data.source, 'clarify');
assert.match(localAsk.data.reply, /13:00/);
assert.match(localAsk.data.reply, /哪个任务/);
const localAnswer = await call('/api/assistant', { method: 'POST', body: { message: '吃饭' } });
assert.ok(localAnswer.data.proposal, JSON.stringify(localAnswer.data));
assert.equal(localAnswer.data.proposal.items[0].taskId, meal.id);
assert.equal(localAnswer.data.proposal.items[0].startMinute, 13 * 60);
assert.ok(localAnswer.data.proposal.items[0].placement, JSON.stringify(localAnswer.data.proposal));
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: localAnswer.data.proposal.id } });
pass('local fallback asks which task, then inherits the 13:00 answer');

const realFetch = globalThis.fetch;
process.env.AGNES_API_KEY = 'smoke-key';
process.env.AGNES_BASE_URL = 'https://agnes.smoke/v1';
let aiReply = '{"say":"好的。","intent":"status","items":[{"name":"吃饭","action":"complete"}]}';
globalThis.fetch = async () =>
  new Response(JSON.stringify({ choices: [{ message: { content: aiReply } }] }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });

const unclear = await call('/api/assistant', { method: 'POST', body: { message: '吃饭，吃一个消失' } });
assert.equal(unclear.status, 200, JSON.stringify(unclear.data));
assert.equal(unclear.data.proposal, null, JSON.stringify(unclear.data));
assert.match(unclear.data.reply, /记为完成/);
const mealAfterUnclear = (await call('/api/bootstrap')).data.tasks.find((task) => task.id === meal.id);
assert.notEqual(mealAfterUnclear.status, 'COMPLETED');
pass('model cannot mark done without the user saying so');

aiReply = '{"say":"好的，帮你安排在今天13:00开始。请问要排哪个任务？","intent":"clarify","items":[]}';
const asked = await call('/api/assistant', { method: 'POST', body: { message: '帮我排在今天中午一点' } });
assert.equal(asked.status, 200, JSON.stringify(asked.data));
assert.equal(asked.data.source, 'clarify');
assert.match(asked.data.reply, /哪个任务/);

delete process.env.AGNES_API_KEY;
delete process.env.AGNES_BASE_URL;
globalThis.fetch = realFetch;

const oneWord = await call('/api/assistant', { method: 'POST', body: { message: '吃饭' } });
assert.equal(oneWord.status, 200, JSON.stringify(oneWord.data));
assert.ok(oneWord.data.proposal, JSON.stringify(oneWord.data));
const carriedItem = oneWord.data.proposal.items[0];
assert.equal(carriedItem.taskId, meal.id);
assert.equal(carriedItem.startMinute, 13 * 60);
assert.ok(carriedItem.placement, JSON.stringify(oneWord.data.proposal));
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: oneWord.data.proposal.id } });
pass('one-word reply inherits the earlier 13:00 question');

const explicitDone = await call('/api/assistant', { method: 'POST', body: { message: '吃饭吃完了' } });
assert.ok(explicitDone.data.proposal, JSON.stringify(explicitDone.data));
assert.equal(explicitDone.data.proposal.items[0].action, 'complete');
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: explicitDone.data.proposal.id } });
pass('explicit completion wording still works');

// ---------- deterministic layer: slots and protocol outrank the model ----------
const tomorrow = new Date(`${state.today}T00:00:00Z`);
tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
const tomorrowStr = tomorrow.toISOString().slice(0, 10);

const rulePlanTurn = await call('/api/assistant', { method: 'POST', body: { message: '明天下午三点写报告两个小时' } });
assert.ok(rulePlanTurn.data.proposal, JSON.stringify(rulePlanTurn.data));
const rulePlanItem = rulePlanTurn.data.proposal.items[0];
assert.equal(rulePlanItem.action, 'create');
assert.equal(rulePlanItem.date, tomorrowStr);
assert.equal(rulePlanItem.startMinute, 900);
assert.equal(rulePlanItem.durationMinutes, 120);
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: rulePlanTurn.data.proposal.id } });
pass('deterministic calendar plan without the model');

const friday = new Date(`${state.today}T00:00:00Z`);
const fridayWeekday = friday.getUTCDay() === 0 ? 7 : friday.getUTCDay();
friday.setUTCDate(friday.getUTCDate() + ((5 - fridayWeekday + 7) % 7));
const fridayStr = friday.toISOString().slice(0, 10);
const dueTurn = await call('/api/assistant', { method: 'POST', body: { message: '周五下午五点前把周报发出去' } });
assert.ok(dueTurn.data.proposal, JSON.stringify(dueTurn.data));
const dueItem = dueTurn.data.proposal.items[0];
assert.equal(dueItem.action, 'create');
assert.equal(dueItem.date, null);
assert.equal(dueItem.dueDate, fridayStr);
assert.match(dueItem.name, /发周报/);
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: dueTurn.data.proposal.id } });
pass('a clock deadline is a due date, not a placed block');

const multiAsk = await call('/api/assistant', { method: 'POST', body: { message: '明天上午十点开会，然后下午三点写报告' } });
assert.equal(multiAsk.data.proposal, null, JSON.stringify(multiAsk.data));
assert.equal(multiAsk.data.source, 'clarify');
pass('two intents hand off instead of silently picking one');

const queryTurn = await call('/api/assistant', { method: 'POST', body: { message: '这周还剩几个小时' } });
assert.equal(queryTurn.data.proposal, null, JSON.stringify(queryTurn.data));
assert.equal(queryTurn.data.source, 'local-query');
pass('a question stays a question offline');

const hardAsk = await call('/api/assistant', { method: 'POST', body: { message: '把那个会挪到四点' } });
assert.equal(hardAsk.data.proposal, null, JSON.stringify(hardAsk.data));
assert.equal(hardAsk.data.source, 'clarify');
assert.match(hardAsk.data.reply, /哪一项|哪个/);
pass('hard boundary asks instead of guessing');

process.env.AGNES_API_KEY = 'smoke-key';
process.env.AGNES_BASE_URL = 'https://agnes.smoke/v1';
aiReply = '{"say":"好的，排到周五十点。","intent":"plan","items":[{"name":"吃饭","action":"create","date":"2026-11-20","start":"10:00","durationMinutes":60}]}';
globalThis.fetch = async () =>
  new Response(JSON.stringify({ choices: [{ message: { content: aiReply } }] }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });
const moving = await call('/api/assistant', { method: 'POST', body: { message: '吃饭改到明天下午三点' } });
assert.ok(moving.data.proposal, JSON.stringify(moving.data));
const movingItem = moving.data.proposal.items[0];
assert.equal(movingItem.action, 'schedule');
assert.equal(movingItem.date, tomorrowStr);
assert.equal(movingItem.startMinute, 900);
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: moving.data.proposal.id } });
pass('model move keeps the sentence date, not its own guess');

aiReply = '{"say":"好的。","intent":"plan","items":[{"name":"吃饭","action":"create","date":"2026-11-16","durationMinutes":60}]}';
const review = await call('/api/assistant', { method: 'POST', body: { message: '吃饭没做完，明天再给我四十分钟' } });
assert.ok(review.data.proposal, JSON.stringify(review.data));
assert.equal(review.data.proposal.items[0].action, 'progress');
assert.equal(review.data.proposal.items.length, 2);
assert.equal(review.data.proposal.items[1].date, tomorrowStr);
assert.equal(review.data.proposal.items[1].durationMinutes, 40);
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: review.data.proposal.id } });
pass('review keeps the block and adds the stated extra time');

const dayAfter = new Date(`${tomorrowStr}T00:00:00Z`);
dayAfter.setUTCDate(dayAfter.getUTCDate() + 1);
const dayAfterStr = dayAfter.toISOString().slice(0, 10);
await call('/api/tasks', { method: 'POST', body: { name: '背单词', durationMinutes: 30 } });
aiReply = `{"say":"好的，后天再排四十分钟。","intent":"plan","items":[{"name":"背单词","action":"schedule","date":"${dayAfterStr}","durationMinutes":40}]}`;
const reviewMove = await call('/api/assistant', { method: 'POST', body: { message: '背单词没背完，后天补四十分钟' } });
assert.ok(reviewMove.data.proposal, JSON.stringify(reviewMove.data));
assert.equal(reviewMove.data.proposal.items[0].action, 'progress');
assert.equal(reviewMove.data.proposal.items.length, 2);
assert.equal(reviewMove.data.proposal.items[1].date, dayAfterStr);
assert.equal(reviewMove.data.proposal.items[1].durationMinutes, 40);
assert.ok(!/推到|延到/.test(reviewMove.data.reply), reviewMove.data.reply);
await call('/api/assistant/cancel', { method: 'POST', body: { proposalId: reviewMove.data.proposal.id } });
pass('an undone report adds time even when the model words it as a move');

delete process.env.AGNES_API_KEY;
delete process.env.AGNES_BASE_URL;
globalThis.fetch = realFetch;

const chatBeforeDelete = (await call('/api/bootstrap')).data.chat;
assert.ok(chatBeforeDelete.length > 0);
const victim = chatBeforeDelete.find((message) => message.role === 'user');
const deletedChat = await call(`/api/chat/${victim.id}`, { method: 'DELETE' });
assert.equal(deletedChat.status, 200, JSON.stringify(deletedChat.data));
assert.ok(!deletedChat.data.chat.some((message) => message.id === victim.id));
assert.ok(
  deletedChat.data.chat.length === chatBeforeDelete.length - 1 ||
    (chatBeforeDelete.length >= 60 && deletedChat.data.chat.length === 60),
  `${chatBeforeDelete.length} -> ${deletedChat.data.chat.length}`,
);
const missingChat = await call('/api/chat/00000000-0000-4000-8000-000000000000', { method: 'DELETE' });
assert.equal(missingChat.status, 404);
pass('chat message delete');

const reset = await call('/api/data', { method: 'DELETE', body: { confirm: 'DELETE' } });
assert.equal(reset.status, 200);
assert.equal(reset.data.tasks.length, 0);
assert.equal(reset.data.segments.length, 0);
pass('data reset');

const logout = await call('/api/logout', { method: 'POST', body: {} });
assert.equal(logout.status, 200);
const afterLogout = await call('/api/bootstrap');
assert.equal(afterLogout.status, 401);
pass('logout invalidates session');

// ---------- shared team backend ----------
const TEAM_CODE = process.env.REPLAND_TEAM_CODE;

async function teamCall(path, { method = 'GET', body, code = TEAM_CODE, key } = {}) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (code) headers['x-team-code'] = code;
  if (key) headers['x-team-key'] = key;
  const response = await handler({
    request: new Request(`http://local/functions/v1/app${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    requestId: 'smoke-team',
  });
  return { status: response.status, data: await response.json().catch(() => null) };
}

const teamToday = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
const teamSeed = {
  team: { name: '测试队' },
  members: [
    { id: 'm-acheng', name: '阿橙', role: '设计', openids: [] },
    { id: 'm-linyi', name: '林一', role: '调研', openids: [] },
  ],
  tasks: [
    { id: 't-research', title: '竞品调研', ownerId: 'm-linyi', hours: 4, deadline: teamToday, deps: [], status: 'TODO', progress: 0, createdAt: new Date().toISOString(), startedAt: null, doneAt: null, unblockedAt: null },
    { id: 't-survey', title: '市场调研问卷', ownerId: 'm-acheng', hours: 3, deadline: teamToday, deps: ['t-research'], status: 'TODO', progress: 0, createdAt: new Date().toISOString(), startedAt: null, doneAt: null, unblockedAt: null },
  ],
  ledger: [],
  pending: [],
  sessions: {},
};

const imported = await teamCall('/api/team/import', { method: 'POST', body: { doc: teamSeed } });
assert.equal(imported.status, 200, JSON.stringify(imported.data));
assert.equal((await teamCall('/api/team/import', { method: 'POST', body: { doc: teamSeed } })).status, 409);
assert.equal((await teamCall('/api/team/board', { code: '' })).status, 401);
assert.equal((await teamCall('/api/team/board', { code: 'wrong-code' })).status, 401);
const board = await teamCall('/api/team/board');
assert.equal(board.status, 200, JSON.stringify(board.data));
assert.equal(board.data.team.name, '测试队');
assert.equal(board.data.tasks.length, 2);
assert.equal(board.data.members.length, 2);
assert.ok(board.data.weekly, 'board carries a weekly report');
pass('team board requires code and reads the shared doc');

const c2cLinyi = { kind: 'c2c', userOpenid: 'u-linyi' };
const c2cAcheng = { kind: 'c2c', userOpenid: 'u-acheng' };
assert.match((await teamCall('/api/team/command', { method: 'POST', body: { ...c2cLinyi, text: '我是 林一' } })).data.reply, /林一/);
assert.match((await teamCall('/api/team/command', { method: 'POST', body: { ...c2cAcheng, text: '我是 阿橙' } })).data.reply, /阿橙/);
const blockedStart = await teamCall('/api/team/command', { method: 'POST', body: { ...c2cAcheng, text: '开始 市场调研问卷' } });
assert.match(blockedStart.data.reply, /还等 竞品调研/, blockedStart.data.reply);
pass('team bind and dependency block');

const createdTeamTask = await teamCall('/api/team/command', {
  method: 'POST',
  body: { ...c2cLinyi, text: '新建 数据清洗 | 给 林一 | 2小时 | 截止 11-30 | 前置 竞品调研' },
});
assert.match(createdTeamTask.data.reply, /建好了：数据清洗/, createdTeamTask.data.reply);
const doneTeamTask = await teamCall('/api/team/command', {
  method: 'POST',
  body: { ...c2cLinyi, text: '完成 竞品调研 已交初稿' },
});
assert.match(doneTeamTask.data.reply, /归档完成/);
assert.equal(doneTeamTask.data.notifications.length, 2, JSON.stringify(doneTeamTask.data.notifications));
assert.ok(doneTeamTask.data.notifications.every((item) => item.priority === 'high'));
assert.ok(doneTeamTask.data.notifications.some((item) => item.openid === 'u-acheng'));
assert.ok(doneTeamTask.data.notifications.some((item) => item.openid === 'u-linyi'));
pass('team create + complete produces unlock notifications');

const queued = await teamCall('/api/team/pending', {
  method: 'POST',
  body: { memberId: 'm-acheng', text: '测试提醒：问卷可以开工了', priority: 'high' },
});
assert.equal(queued.data.ok, true);
const started = await teamCall('/api/team/command', { method: 'POST', body: { ...c2cAcheng, text: '开始 市场调研问卷' } });
assert.match(started.data.reply, /开始「市场调研问卷」/, started.data.reply);
assert.equal(started.data.pending.length, 1);
assert.match(started.data.pending[0], /测试提醒/);
const afterStart = await teamCall('/api/team/board');
assert.equal(afterStart.data.tasks.find((task) => task.id === 't-survey').status, 'DOING');
assert.equal(afterStart.data.pending, 0);
pass('pending notifications pin to the next reply');

const pixel = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const media = await teamCall('/api/team/media', { method: 'POST', body: { base64: pixel, kind: 'test' } });
assert.equal(media.status, 200, JSON.stringify(media.data));
assert.match(media.data.url, /\/functions\/v1\/app\/api\/team\/media\/[0-9a-f-]{36}\.png$/);
const imageResponse = await handler({
  request: new Request(`http://local/functions/v1/app/api/team/media/${media.data.id}.png`),
  requestId: 'smoke-media',
});
assert.equal(imageResponse.status, 200);
assert.equal(imageResponse.headers.get('content-type'), 'image/png');
const imageBytes = new Uint8Array(await imageResponse.arrayBuffer());
assert.deepEqual([...imageBytes.slice(0, 4)], [0x89, 0x50, 0x4e, 0x47]);
pass('media upload serves a public PNG URL');

const weeklyTeam = await teamCall('/api/team/command', { method: 'POST', body: { ...c2cLinyi, text: '周报' } });
assert.ok(weeklyTeam.data.image, JSON.stringify(weeklyTeam.data));
assert.equal(weeklyTeam.data.image.kind, 'weekly');
assert.equal(weeklyTeam.data.image.report.stats.doneCount, 1);
assert.equal(weeklyTeam.data.image.report.stats.activeCount, 2);
assert.equal(weeklyTeam.data.image.report.stats.blockedCount, 0);
const weeklyText = await teamCall('/api/team/command', { method: 'POST', body: { ...c2cLinyi, text: '周报 文字' } });
assert.equal(weeklyText.data.image, null);
pass('weekly report returns image data and a text-only variant');

const teamSnapshot = db.store.snapshot();
assert.equal(teamSnapshot.team_state.length, 1);
assert.ok(teamSnapshot.team_state[0].doc.tasks.some((task) => task.title === '数据清洗'));
assert.ok(teamSnapshot.team_state[0].doc.ledger.length > 0);
pass('team writes live in the database document');

// --- availability: timetable -> shared free windows -> meeting ---
const nextMonday = (() => {
  const base = new Date(`${teamToday}T00:00:00Z`);
  for (let i = 0; i < 8; i += 1) {
    const day = new Date(base.getTime() + i * 86_400_000);
    if (day.getUTCDay() === 1) return day.toISOString().slice(0, 10);
  }
  return teamToday;
})();
const savedA = await teamCall('/api/team/timetable', {
  method: 'POST',
  body: { memberId: 'm-acheng', entries: [{ title: '设计课', weekday: 1, startMinute: 9 * 60, endMinute: 11 * 60 }] },
});
assert.equal(savedA.status, 200, JSON.stringify(savedA.data));
assert.equal(savedA.data.timetable.length, 1);
const savedB = await teamCall('/api/team/timetable', {
  method: 'POST',
  body: { memberId: 'm-linyi', entries: [{ title: '统计', weekday: 1, startMinute: 10 * 60, endMinute: 12 * 60 }] },
});
assert.equal(savedB.status, 200, JSON.stringify(savedB.data));
pass('members upload their own weekly timetable');

const schedule = await teamCall(`/api/team/schedule?days=8&duration=60`);
assert.equal(schedule.status, 200, JSON.stringify(schedule.data));
assert.equal(schedule.data.days.length, 8);
assert.ok(schedule.data.suggestions.length > 0, 'there are common slots to propose');
const monday = schedule.data.days.find((day) => day.date === nextMonday);
assert.ok(monday, 'monday is inside the window');
const achengBusy = monday.members.find((member) => member.id === 'm-acheng').busy;
assert.ok(achengBusy.some((entry) => entry.startMinute === 540 && entry.endMinute === 660));
assert.ok(monday.windows.some((window) => window.startMinute === 720), JSON.stringify(monday.windows));
pass('shared free windows respect every timetable');

const meeting = await teamCall('/api/team/meetings', {
  method: 'POST',
  body: { title: '周会', date: nextMonday, startMinute: 12 * 60, endMinute: 13 * 60, memberIds: ['m-acheng', 'm-linyi'] },
});
assert.equal(meeting.status, 200, JSON.stringify(meeting.data));
assert.equal(meeting.data.meeting.title, '周会');
const conflict = await teamCall('/api/team/meetings', {
  method: 'POST',
  body: { title: '撞车', date: nextMonday, startMinute: 12 * 60 + 30, endMinute: 13 * 60 + 30, memberIds: ['m-linyi'] },
});
assert.equal(conflict.status, 409, JSON.stringify(conflict.data));
assert.match(conflict.data.message, /林一/);
const afterMeeting = await teamCall(`/api/team/schedule?days=8&duration=60`);
const mondayAfter = afterMeeting.data.days.find((day) => day.date === nextMonday);
assert.ok(mondayAfter.members.find((member) => member.id === 'm-linyi').busy.some((entry) => entry.kind === 'meeting'));
assert.ok(!mondayAfter.windows.some((window) => window.startMinute < 13 * 60 && window.endMinute > 12 * 60));
pass('meetings block conflicts and join the busy timeline');

const removedMeeting = await teamCall(`/api/team/meetings/${meeting.data.meeting.id}`, { method: 'DELETE' });
assert.equal(removedMeeting.status, 200);
pass('meeting can be removed');

// --- demo document stays separate from the team the bot writes ---
const mainBefore = await teamCall('/api/team/schedule?days=4&duration=60');
const demo = await teamCall('/api/team/schedule?days=4&duration=60', { code: '', key: 'demo' });
assert.equal(demo.status, 200, JSON.stringify(demo.data));
assert.match(demo.data.teamName, /演示/);
assert.ok(demo.data.members.length >= 5, 'demo seeds the showcase roster');
assert.ok(demo.data.members.every((member) => member.timetable.length > 0), 'demo members have timetables');
const demoSlot = demo.data.suggestions[0];
const demoMeeting = await teamCall('/api/team/meetings', {
  method: 'POST',
  code: '',
  key: 'demo',
  body: {
    title: '演示会',
    date: demoSlot.date,
    startMinute: demoSlot.startMinute,
    endMinute: demoSlot.endMinute,
    memberIds: demo.data.members.map((member) => member.id),
  },
});
assert.equal(demoMeeting.status, 200, JSON.stringify(demoMeeting.data));
const demoAfter = await teamCall('/api/team/schedule?days=4&duration=60', { code: '', key: 'demo' });
assert.ok(demoAfter.data.meetings.some((entry) => entry.title === '演示会'));
const mainAfter = await teamCall('/api/team/schedule?days=4&duration=60');
assert.equal(mainAfter.data.meetings.length, mainBefore.data.meetings.length);
assert.ok(!mainAfter.data.meetings.some((entry) => entry.title === '演示会'));
pass('demo team writes never reach the real team document');

// --- draft lifecycle by conversation (no card click needed) ---
const relogin = await call('/api/auth/login', {
  method: 'POST',
  body: { code: 'repland-test', name: '小满测试', deviceId: 'device-test-0001' },
});
assert.equal(relogin.status, 200, JSON.stringify(relogin.data));
token = relogin.data.token;

const chatConfirmDraft = await call('/api/assistant', {
  method: 'POST',
  body: { message: '大后天上午十点回看线代作业 45分钟' },
});
assert.ok(chatConfirmDraft.data.proposal, JSON.stringify(chatConfirmDraft.data));
const chatConfirmed = await call('/api/assistant', { method: 'POST', body: { message: '确认' } });
assert.equal(chatConfirmed.status, 200, JSON.stringify(chatConfirmed.data));
assert.equal(chatConfirmed.data.proposal, null);
const noteTask = chatConfirmed.data.tasks.find((task) => task.name === '回看线代作业');
assert.ok(noteTask, 'chat confirm must write the draft');
assert.equal(chatConfirmed.data.segments.filter((segment) => segment.task_id === noteTask.id).length, 1);
assert.equal((await call('/api/bootstrap')).data.pendingProposal, null);
pass('draft confirmed by talking');

const chatCancelDraft = await call('/api/assistant', {
  method: 'POST',
  body: { message: '后天下午两点背单词 30分钟' },
});
assert.ok(chatCancelDraft.data.proposal);
const chatCancelled = await call('/api/assistant', { method: 'POST', body: { message: '先不' } });
assert.equal(chatCancelled.status, 200);
assert.equal((await call('/api/bootstrap')).data.tasks.filter((task) => task.name.includes('背单词')).length, 0);
pass('draft cancelled by talking');

const agedDraft = await call('/api/assistant', {
  method: 'POST',
  body: { message: '大后天上午九点做真题 60分钟' },
});
assert.ok(agedDraft.data.proposal);
await db
  .from('proposals')
  .update({ created_at: new Date(Date.now() - 13 * 60 * 60 * 1000).toISOString() })
  .eq('id', agedDraft.data.proposal.id);
assert.equal((await call('/api/bootstrap')).data.pendingProposal, null);
const expiredReply = await call('/api/assistant', { method: 'POST', body: { message: '确认' } });
assert.match(expiredReply.data.reply, /过期/);
assert.equal((await call('/api/bootstrap')).data.tasks.filter((task) => task.name.includes('做真题')).length, 0);
pass('stale draft expires without writing');

console.log('\nAll route checks passed.');

