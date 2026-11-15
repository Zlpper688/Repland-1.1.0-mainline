import { strict as assert } from 'node:assert';
import { createTeamDoc, drainPending, handleTeamCommand, queuePending, weeklyReportData } from '../functions/team.mjs';

const doc = createTeamDoc();
const group = { kind: 'group', groupOpenid: 'g-test', memberOpenid: 'm-member' };
const linyi = { kind: 'c2c', userOpenid: 'u-linyi' };
const acheng = { kind: 'c2c', userOpenid: 'u-acheng' };

function send(chat, text) {
  return handleTeamCommand(doc, chat, text);
}

assert.match(send(linyi, '我是 林一').reply, /林一/);
assert.match(send(acheng, '我是 阿橙').reply, /阿橙/);

const list = send(linyi, '任务');
assert.match(list.reply, /^## 未完成任务/, list.reply);
assert.match(list.reply, /1\. 竞品调研\*\*/, list.reply);
assert.match(list.reply, /可以开始/);
assert.ok(list.buttons.length, '列表应带快捷按钮');

const numbered = send(linyi, '2');
assert.match(numbered.reply, /报名材料初稿/, numbered.reply);

const blocked = send(acheng, '开始 市场调研问卷');
assert.match(blocked.reply, /还等 竞品调研/, blocked.reply);

const created = send(linyi, '新建 数据清洗 | 给 林一 | 2小时 | 截止 11-30 | 前置 竞品调研');
assert.match(created.reply, /建好了：数据清洗/, created.reply);

const done = send(linyi, '完成 竞品调研 已交初稿');
assert.match(done.reply, /归档完成/);
assert.equal(done.notifications.length, 2, JSON.stringify(done.notifications));
assert.ok(done.notifications.some((item) => item.memberId === 'm-acheng'));
assert.ok(done.notifications.some((item) => item.memberId === 'm-linyi'));
assert.ok(done.notifications.every((item) => item.priority === 'high'));
assert.deepEqual(done.notifications.map((item) => item.openid).sort(), ['u-acheng', 'u-linyi']);
assert.match(done.notifications[0].text, /可以开始了/);
assert.match(done.reply, /市场调研问卷/);

const start = send(acheng, '开始 市场调研问卷');
assert.match(start.reply, /开始「市场调研问卷」/, start.reply);

const progress = send(acheng, '进度 市场调研问卷 40%');
assert.match(progress.reply, /40%/);

const detail = send(acheng, '详情 市场调研问卷');
const buttons = detail.buttons.flat();
assert.ok(buttons.some((button) => button.data === '/进度 市场调研问卷 +20%'));
assert.ok(buttons.some((button) => button.data === '/完成 市场调研问卷'));
assert.match(detail.reply, /下游/);

const mine = send(acheng, '我的任务');
assert.match(mine.reply, /进行中 40%/, mine.reply);

const auto = send(acheng, '进度 市场调研问卷');
assert.match(auto.reply, /60%\*\*（自动 \+20%）/, auto.reply);

const plus = send(acheng, '进度 市场调研问卷 +20%');
assert.match(plus.reply, /80%/, plus.reply);

const daily = send(linyi, '日报');
assert.match(daily.reply, /今日日报/);
assert.match(daily.reply, /已完成/);
assert.match(daily.reply, /竞品调研/);
assert.match(daily.reply, /进行中 1/, daily.reply);

const weekly = send(linyi, '周报');
assert.match(weekly.reply, /本周周报/);
assert.ok(weekly.image, JSON.stringify(weekly));
assert.equal(weekly.image.kind, 'weekly');
assert.equal(weekly.image.report.stats.doneCount, 1);
assert.equal(weekly.image.report.stats.doingCount, 1);
assert.ok(weekly.image.report.members.some((member) => member.name === '林一' && member.doneHours === 4));
const weeklyText = send(linyi, '周报 文字');
assert.match(weeklyText.reply, /本周周报/);
assert.equal(weeklyText.image, null);

const blockedAgain = send(acheng, '新建 视觉物料二版 | 给 阿橙 | 前置 视觉物料');
assert.match(blockedAgain.reply, /建好了/);

queuePending(doc, 'm-xiaoman', '「技术方案选型」的前置已完成', 'high');
assert.equal(doc.pending.length, 1);
const queuedSeen = send({ kind: 'c2c', userOpenid: 'u-unbound' }, '任务');
assert.equal(queuedSeen.pending.length, 0);
const xiaoman = { kind: 'c2c', userOpenid: 'u-xiaoman' };
send(xiaoman, '我是 小满');
const drained = send(xiaoman, '任务');
assert.equal(drained.pending.length, 1);
assert.match(drained.pending[0], /技术方案选型/);
assert.equal(drainPending(doc, 'm-xiaoman').length, 0);

const report = weeklyReportData(doc);
assert.equal(report.teamName, '数模赛备赛组');
assert.ok(report.range.from <= report.range.to);
assert.equal(report.stats.activeCount, 9);

const unknown = send(group, '瞎说一句');
assert.match(unknown.reply, /没听懂/);

const help = send(group, '帮助');
assert.match(help.reply, /团队助手/);

console.log('qq-bot engine selfcheck ok');
