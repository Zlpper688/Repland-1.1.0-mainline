/** QQ 富文本渲染：markdown 正文 + 内嵌键盘（指令按钮），并保底生成纯文本。 */

export function progressBar(percent, cells = 10) {
  const value = Math.max(0, Math.min(100, Number(percent) || 0));
  const filled = Math.round((value / 100) * cells);
  return `${'▰'.repeat(filled)}${'▱'.repeat(cells - filled)}`;
}

/** 降级用：把 markdown 抹成 QQ 纯文本消息也能读的样子。 */
export function stripMarkdown(text) {
  return String(text ?? '')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/__(.+?)__/g, '$1')
    .replace(/~~(.+?)~~/g, '$1')
    .replace(/\[(.+?)\]\((.+?)\)/g, '$1（$2）')
    .replace(/^>\s?/gm, '')
    .replace(/^\s*---\s*$/gm, '────────')
    .trim();
}

/**
 * 指令按钮：点击后在输入框插入 @机器人 + data（单聊可直接发送）。
 * rows 形如 [[{ label: '开始', data: '/开始 竞品调研' }]]，每行最多 4 个。
 */
export function keyboard(rows) {
  return {
    content: {
      rows: rows.slice(0, 5).map((row, rowIndex) => ({
        buttons: row.slice(0, 4).map((button, column) => ({
          id: `r${rowIndex + 1}b${column + 1}`,
          render_data: {
            label: button.label,
            visited_label: button.visited || button.label,
            style: button.style ?? 1,
          },
          action: {
            type: 2,
            permission: { type: 2 },
            data: button.data,
            unsupport_tips: 'QQ 版本过低，请升级后再用按钮',
            reply: false,
            enter: false,
          },
        })),
      })),
    },
  };
}

export function chunkMarkdown(text, limit) {
  const value = String(text ?? '').trim();
  if (!value) return [];
  if (value.length <= limit) return [value];
  const chunks = [];
  let rest = value;
  while (rest.length > limit) {
    const window = rest.slice(0, limit);
    const paragraph = window.lastIndexOf('\n\n');
    const line = window.lastIndexOf('\n');
    const cut = paragraph > limit / 2 ? paragraph : line > limit / 2 ? line : limit;
    chunks.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) chunks.push(rest);
  return chunks.slice(0, 6);
}
