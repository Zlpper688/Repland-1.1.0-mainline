package com.swan1127.repland.ui.agent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 回复正文的卡片堆，个人群聊与团队助手共用同一套质感：
 * 短回复按段落拆成几张小卡；长回复先折起来（六行预览 + 底部渐隐 + 展开全文），
 * 点开是无框大字的全屏阅读器。长按出锚定菜单：复制/回复/喜欢/不喜欢——
 * 喜欢与不喜欢是隐式记忆证据，这里只把信号交出去，落库由调用方负责。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReplyBubbleBody(
    text: String,
    typing: Boolean,
    feedback: Boolean?,
    onQuote: (String) -> Unit,
    onFeedback: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var readerOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val blocks = remember(text) {
        text.split(Regex("\n\\s*\n")).map(String::trim).filter(String::isNotEmpty)
    }
    val lines = remember(text) { text.lines() }
    val foldable = !typing && (lines.size > 6 || text.length > 220)
    val preview = remember(text) { lines.take(6).joinToString("\n") }
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow

    Box(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                typing -> ReplyCard {
                    Text(
                        "$text▍",
                        modifier = Modifier.padding(12.dp, 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                foldable -> {
                    Surface(
                        color = cardColor,
                        shape = RoundedCornerShape(6.dp, 16.dp, 16.dp, 16.dp),
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .combinedClickable(
                                onClick = { readerOpen = true },
                                onLongClick = { menuOpen = true },
                            ),
                    ) {
                        Box {
                            Text(
                                preview,
                                modifier = Modifier.padding(12.dp, 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, cardColor))),
                            )
                        }
                    }
                    Text(
                        "展开全文",
                        modifier = Modifier
                            .align(Alignment.End)
                            .combinedClickable(onClick = { readerOpen = true }, onLongClick = { menuOpen = true })
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("reply-expand"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                else -> (blocks.ifEmpty { listOf(text) }).forEach { block ->
                    ReplyCard(
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true },
                        ),
                    ) {
                        Text(
                            block,
                            modifier = Modifier.padding(12.dp, 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (feedback != null) {
                Text(
                    if (feedback) "已记下：你喜欢这条回答" else "已记下：你不喜欢这条回答",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("回复") },
                onClick = {
                    onQuote(text.take(18))
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("喜欢") },
                onClick = {
                    onFeedback(true)
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("不喜欢") },
                onClick = {
                    onFeedback(false)
                    menuOpen = false
                },
            )
        }
    }

    if (readerOpen) {
        Dialog(
            onDismissRequest = { readerOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxSize().testTag("reply-reader"),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "全文",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = { readerOpen = false }, modifier = Modifier.testTag("reply-reader-close")) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplyCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(6.dp, 16.dp, 16.dp, 16.dp),
        modifier = Modifier.widthIn(max = 320.dp).then(modifier),
    ) {
        Box { content() }
    }
}