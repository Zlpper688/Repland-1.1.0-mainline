import { apiBase, teamCommand, teamHealth, uploadImage } from './api.mjs';
import { deliverNotifications } from './notify.mjs';
import { QQBot } from './qq.mjs';
import { stripMarkdown } from './render.mjs';
import { renderWeeklyCard } from './weekly-card.mjs';

const appId = process.env.QQ_APP_ID;
const appSecret = process.env.QQ_APP_SECRET;

if (!appId || !appSecret) {
  console.error('先创建 qq-bot/.env（从 .env.example 复制），填好 QQ_APP_ID 和 QQ_APP_SECRET。');
  process.exit(1);
}

function chatOf(message) {
  return message.kind === 'group'
    ? { kind: 'group', groupOpenid: message.groupOpenid, memberOpenid: message.memberOpenid }
    : { kind: 'c2c', userOpenid: message.userOpenid };
}

const bot = new QQBot({
  appId,
  appSecret,
  onMessage: async (message, client) => {
    const chat = chatOf(message);
    const result = await teamCommand(chat, message.text);
    const delivery = await deliverNotifications(client, result.notifications || [], chat);

    const pendingBlock = result.pending?.length
      ? `> 待通知：${result.pending.map((text) => stripMarkdown(text).replace(/\s*\n\s*/g, ' · ')).join('；')}`
      : '';
    const markdown = [pendingBlock, result.reply, delivery].filter(Boolean).join('\n\n');
    await client.reply(message, { markdown, buttons: result.buttons });

    if (result.image) {
      try {
        const url = await uploadImage(renderWeeklyCard(result.image.report), result.image.kind);
        await client.sendMedia(chat, url, { msgId: message.messageId });
      } catch (error) {
        console.log(`周报图片发送失败：${error.message}；文字版已发出`);
      }
    }

    console.log(
      `[${chat.kind}:${chat.groupOpenid || chat.userOpenid}] ${message.text.slice(0, 40)} → ${stripMarkdown(result.reply).replace(/\s+/g, ' ').slice(0, 60)}`,
    );
  },
});

console.log(`Repland QQ 机器人启动中（共享后端 ${apiBase()}）`);
try {
  const health = await teamHealth();
  console.log(`共享后端正常（今天 ${health.today}）`);
} catch (error) {
  console.error(`共享后端不可用：${error.message}`);
}
try {
  await bot.start();
} catch (error) {
  console.error(`启动失败：${error.message}`);
  process.exitCode = 1;
}

process.on('SIGINT', () => {
  bot.stop();
  process.exit(0);
});
