import { chunkMarkdown, keyboard as buildKeyboard, stripMarkdown } from './render.mjs';

const BASE = process.env.QQ_API_BASE || 'https://api.bot.qq.com';
const TOKEN_URLS = ['https://bots.qq.com/app/getAppAccessToken', `${BASE}/app/getAppAccessToken`];
const INTENTS_GROUP_AND_C2C = 1 << 25;
const MAX_CHUNK = 1200;
const MAX_MARKDOWN = 2200;

export class QQBot {
  constructor({ appId, appSecret, onMessage }) {
    if (!appId || !appSecret) throw new Error('缺少 QQ_APP_ID / QQ_APP_SECRET');
    this.appId = appId;
    this.appSecret = appSecret;
    this.onMessage = onMessage;
    this.token = null;
    this.tokenExpiresAt = 0;
    this.ws = null;
    this.seq = 0;
    this.msgSeq = 0;
    this.heartbeat = null;
    this.sessionId = null;
    this.lastSeq = null;
    this.stopped = false;
    this.reconnecting = false;
    this.markdownEnabled = process.env.QQ_MARKDOWN !== 'off';
    this.buttonsEnabled = process.env.QQ_BUTTONS !== 'off';
    this.markdownBlocked = false;
  }

  async accessToken() {
    if (this.token && Date.now() < this.tokenExpiresAt - 60_000) return this.token;
    let lastError = null;
    for (const url of TOKEN_URLS) {
      try {
        const response = await fetch(url, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ appId: this.appId, clientSecret: this.appSecret }),
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok || !data.access_token) {
          lastError = new Error(`取 access_token 失败：${response.status} ${data.code ?? ''} ${data.message ?? ''}`.trim());
          continue;
        }
        this.token = data.access_token;
        this.tokenExpiresAt = Date.now() + Number(data.expires_in || 7200) * 1000;
        return this.token;
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError || new Error('取 access_token 失败');
  }

  async api(path, { method = 'GET', body } = {}) {
    const token = await this.accessToken();
    const response = await fetch(`${BASE}${path}`, {
      method,
      headers: {
        authorization: `QQBot ${token}`,
        'content-type': 'application/json',
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = { raw: text };
    }
    if (!response.ok) {
      const detail = data?.err_code ? `${data.err_code} ${data.message ?? ''}`.trim() : `HTTP ${response.status}`;
      throw new Error(`QQ API ${method} ${path} 失败：${detail}`);
    }
    return data;
  }

  async start() {
    this.stopped = false;
    const token = await this.accessToken();
    const gateway = await this.api('/gateway');
    const url = gateway?.url;
    if (!url) throw new Error('取网关地址失败');
    this.ws = new WebSocket(url);
    this.ws.addEventListener('open', () => this.identify(token));
    this.ws.addEventListener('message', (event) => this.dispatch(event.data));
    this.ws.addEventListener('close', (event) => {
      if (!this.reconnecting) this.reconnect(event.code);
    });
    this.ws.addEventListener('error', () => {});
  }

  identify(token) {
    this.send({
      op: 2,
      d: {
        token: `QQBot ${token}`,
        intents: INTENTS_GROUP_AND_C2C,
        shard: [0, 1],
        properties: { $os: 'windows', $browser: 'repland-qq-bot', $device: 'repland-qq-bot' },
      },
    });
  }

  send(payload) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(payload));
  }

  dispatch(raw) {
    let payload = null;
    try {
      payload = JSON.parse(typeof raw === 'string' ? raw : String(raw));
    } catch {
      return;
    }
    if (typeof payload.s === 'number') this.lastSeq = payload.s;
    switch (payload.op) {
      case 10: {
        const interval = payload.d?.heartbeat_interval || 45_000;
        clearInterval(this.heartbeat);
        this.heartbeat = setInterval(() => this.send({ op: 1, d: this.lastSeq }), interval);
        break;
      }
      case 0: {
        if (payload.t === 'READY') {
          this.sessionId = payload.d?.session_id || null;
          console.log(`QQ 机器人已连接${this.sessionId ? `（session ${this.sessionId.slice(0, 8)}…）` : ''}`);
          return;
        }
        if (payload.t === 'GROUP_AT_MESSAGE_CREATE') {
          this.handleEvent({
            kind: 'group',
            groupOpenid: payload.d.group_openid,
            memberOpenid: payload.d.author?.member_openid,
            messageId: payload.d.id,
            text: payload.d.content ?? '',
            raw: payload.d,
          });
          return;
        }
        if (payload.t === 'C2C_MESSAGE_CREATE') {
          this.handleEvent({
            kind: 'c2c',
            userOpenid: payload.d.author?.user_openid,
            messageId: payload.d.id,
            text: payload.d.content ?? '',
            raw: payload.d,
          });
        }
        break;
      }
      case 7: {
        this.reconnect('server-reconnect');
        break;
      }
      case 9: {
        this.token = null;
        this.reconnect('invalid-session');
        break;
      }
      default:
        break;
    }
  }

  async handleEvent(message) {
    try {
      await this.onMessage(message, this);
    } catch (error) {
      console.error(`处理消息失败：${error.message}`);
      await this.reply(message, `出错了：${error.message}`).catch(() => {});
    }
  }

  async reply(message, payload) {
    const chat = message.kind === 'group'
      ? { kind: 'group', groupOpenid: message.groupOpenid }
      : { kind: 'c2c', userOpenid: message.userOpenid };
    await this.sendRich(chat, payload, { msgId: message.messageId });
  }

  async sendProactive(chat, payload) {
    await this.sendRich(chat, payload, {});
  }

  /** Rich media: upload by public URL first, then send msg_type 7 with file_info. */
  async sendMedia(chat, url, { msgId } = {}) {
    const uploadPath = chat.kind === 'group'
      ? `/v2/groups/${chat.groupOpenid}/files`
      : `/v2/users/${chat.userOpenid}/files`;
    const media = await this.api(uploadPath, {
      method: 'POST',
      body: { file_type: 1, url, srv_send_msg: false },
    });
    const fileInfo = media?.file_info;
    if (!fileInfo) throw new Error('QQ 上传图片没有返回 file_info');
    await this.post(chat, { msg_type: 7, media: { file_info: fileInfo } }, msgId);
  }

  /** markdown + 指令按钮优先，失败逐级降级：去按钮 → 纯文本。 */
  async sendRich(chat, payload, { msgId }) {
    const rich = typeof payload === 'string' ? { markdown: payload } : payload || {};
    const markdown = String(rich.markdown || '').trim();
    const plain = String(rich.plain || stripMarkdown(markdown)).trim();
    const wantKeyboard = Boolean(rich.buttons?.length) && this.buttonsEnabled;

    if (markdown && this.markdownEnabled && !this.markdownBlocked) {
      const chunks = chunkMarkdown(markdown, MAX_MARKDOWN);
      let index = 0;
      while (index < chunks.length) {
        const useKeyboard = wantKeyboard && index === 0;
        let error = null;
        try {
          const body = { msg_type: 2, markdown: { content: chunks[index] } };
          if (useKeyboard) body.keyboard = buildKeyboard(rich.buttons);
          await this.post(chat, body, msgId);
          index += 1;
          continue;
        } catch (raised) {
          error = raised;
        }
        if (useKeyboard) {
          try {
            await this.post(chat, { msg_type: 2, markdown: { content: chunks[index] } }, msgId);
            index += 1;
            continue;
          } catch (raised) {
            error = raised;
          }
        }
        console.log(`markdown 发送失败，降级为纯文本：${error.message}`);
        if (/40034127|markdown.*权限/i.test(error.message)) this.markdownBlocked = true;
        await this.sendPlain(chat, plain, msgId);
        return;
      }
      return;
    }
    await this.sendPlain(chat, plain, msgId);
  }

  async sendPlain(chat, text, msgId) {
    for (const chunk of splitText(text)) {
      await this.post(chat, { msg_type: 0, content: chunk }, msgId);
    }
  }

  async post(chat, body, msgId) {
    if (msgId) {
      this.msgSeq += 1;
      body.msg_id = msgId;
      body.msg_seq = this.msgSeq;
    }
    const path = chat.kind === 'group'
      ? `/v2/groups/${chat.groupOpenid}/messages`
      : `/v2/users/${chat.userOpenid}/messages`;
    return this.api(path, { method: 'POST', body });
  }

  reconnect(reason) {
    if (this.stopped || this.reconnecting) return;
    this.reconnecting = true;
    clearInterval(this.heartbeat);
    this.heartbeat = null;
    try {
      this.ws?.close();
    } catch {
      // ignore
    }
    console.log(`连接断开（${reason}），5 秒后重连`);
    setTimeout(() => {
      if (this.stopped) return;
      this.start()
        .then(() => {
          this.reconnecting = false;
        })
        .catch((error) => {
          this.reconnecting = false;
          console.error(`重连失败：${error.message}`);
          this.reconnect('retry');
        });
    }, 5000);
  }

  stop() {
    this.stopped = true;
    clearInterval(this.heartbeat);
    this.ws?.close();
  }
}

function splitText(text) {
  const value = String(text ?? '').trim();
  if (!value) return ['（空）'];
  if (value.length <= MAX_CHUNK) return [value];
  const chunks = [];
  let rest = value;
  while (rest.length > MAX_CHUNK) {
    const cut = rest.lastIndexOf('\n', MAX_CHUNK);
    const index = cut > MAX_CHUNK / 2 ? cut : MAX_CHUNK;
    chunks.push(rest.slice(0, index));
    rest = rest.slice(index);
  }
  if (rest) chunks.push(rest);
  return chunks.slice(0, 4);
}
