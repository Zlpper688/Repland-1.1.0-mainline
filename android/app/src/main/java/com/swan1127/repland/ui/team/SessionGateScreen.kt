package com.swan1127.repland.ui.team

import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.swan1127.repland.data.team.AuthLoginResult
import com.swan1127.repland.data.team.WechatQrStartResult
import com.swan1127.repland.ui.components.DesignButton
import com.swan1127.repland.ui.components.DesignTextButton
import com.swan1127.repland.ui.components.DesignTextField
import com.swan1127.repland.ui.components.LandscapeArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GatePillShape = RoundedCornerShape(24.dp)
private val MainlandPhone = Regex("1[0-9]{10}")

@Composable
fun SessionGateScreen(
    vm: TeamViewModel,
    onOffline: () -> Unit,
    modifier: Modifier = Modifier,
    onWechatProfile: (AuthLoginResult) -> Unit = {},
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var hintOk by remember { mutableStateOf(false) }
    var qrSession by remember { mutableStateOf<WechatQrStartResult?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun showHint(message: String, ok: Boolean = false) {
        hint = message
        hintOk = ok
    }

    fun runRequest(action: suspend () -> Unit) {
        if (busy) return
        focus.clearFocus()
        busy = true
        hint = ""
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showHint("登录暂时不可用，请重试")
            } finally {
                busy = false
            }
        }
    }

    fun requestSms() {
        if (!MainlandPhone.matches(phone)) {
            showHint("请输入正确的大陆 11 位手机号")
            return
        }
        runRequest {
            val result = vm.requestSmsCode(vm.backendBaseUrl, phone)
            if (result.ok) {
                codeSent = true
                code = ""
            }
            showHint(result.message + (result.devCode?.let { "（开发验证码：$it）" } ?: ""), result.ok)
        }
    }

    fun verifySms() {
        if (code.length != 6) {
            showHint("请输入 6 位验证码")
            return
        }
        runRequest {
            val login = vm.verifySmsCode(vm.backendBaseUrl, phone, code, "", "")
            if (!login.ok || !vm.applyLoginToken(login.token)) showHint(login.message)
        }
    }

    fun startWechat() = runRequest {
        qrSession = null
        val result = vm.startWechatQrLogin()
        if (!result.ok || result.pollToken.isBlank() || result.expiresInSeconds <= 0 || !validWechatUrl(result.authUrl)) {
            showHint(result.message)
        } else {
            qrSession = result
        }
    }

    LaunchedEffect(qrSession) {
        val session = qrSession ?: return@LaunchedEffect
        val deadline = android.os.SystemClock.elapsedRealtime() + session.expiresInSeconds * 1_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(2_000)
            val login = vm.pollWechatQrLogin(session.pollToken)
            if (login.pending) continue
            if (login.ok && vm.applyLoginToken(login.token)) {
                onWechatProfile(login)
                return@LaunchedEffect
            }
            showHint(login.message)
            qrSession = null
            return@LaunchedEffect
        }
        showHint("二维码已过期，请刷新")
        qrSession = null
    }

    fun back() {
        if (busy) return
        qrSession = null
        hint = ""
        step = if (step == 2) 1 else 0
    }

    BackHandler(enabled = step != 0) { back() }

    Box(modifier.fillMaxSize()) {
        LandscapeArtwork(Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (step == 0) 120.dp else 56.dp))
            when (step) {
                0 -> {
                    Text("Repland", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("重新规划，让计划落到行动里。", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(40.dp))
                    DesignButton(
                        onClick = { step = 1 },
                        modifier = Modifier.fillMaxWidth().testTag("gate-start"),
                        shape = GatePillShape,
                    ) { Text("开始使用", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.titleMedium) }
                    DesignTextButton(
                        onClick = { step = 2; startWechat() },
                        modifier = Modifier.testTag("gate-qr-start"),
                    ) { Text("微信扫码登录") }
                }
                1 -> {
                    Text("手机号登录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(24.dp))
                    DesignTextField(
                        phone,
                        { input ->
                            val digits = input.filter { it in '0'..'9' }
                            val normalized = if (digits.length == 13) digits.removePrefix("86") else digits
                            if (normalized.take(11) != phone) {
                                phone = normalized.take(11)
                                codeSent = false
                                code = ""
                                hint = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("auth-phone"),
                        label = { Text("手机号（大陆 11 位）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { requestSms() }),
                        enabled = !busy,
                    )
                    if (codeSent) {
                        Spacer(Modifier.height(12.dp))
                        DesignTextField(
                            code, { code = it.filter { char -> char in '0'..'9' }.take(6) },
                            modifier = Modifier.fillMaxWidth().testTag("auth-code"),
                            label = { Text("验证码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { verifySms() }),
                            enabled = !busy,
                        )
                    }
                    LoginHint(hint, hintOk, "auth-hint")
                    Spacer(Modifier.height(16.dp))
                    DesignButton(
                        onClick = { if (codeSent) verifySms() else requestSms() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag(if (codeSent) "auth-sms-verify" else "auth-sms-request"),
                        shape = GatePillShape,
                    ) {
                        val label = when {
                            busy && codeSent -> "请稍候…"
                            busy -> "正在请求验证码…"
                            codeSent -> "登录"
                            else -> "获取验证码"
                        }
                        Text(label, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    if (codeSent) {
                        DesignTextButton(
                            onClick = ::requestSms,
                            enabled = !busy,
                            modifier = Modifier.testTag("auth-sms-resend"),
                        ) { Text("重新获取验证码") }
                    }
                    DesignTextButton(
                        onClick = { step = 2; startWechat() },
                        enabled = !busy,
                        modifier = Modifier.testTag("gate-to-wechat"),
                    ) { Text("微信扫码登录") }
                }
                else -> {
                    Text("微信扫码登录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    if (qrSession != null) {
                        Text("打开手机微信「扫一扫」，扫码后在手机上确认授权。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        WechatQrPage(requireNotNull(qrSession).authUrl, Modifier.fillMaxWidth().height(360.dp)) {
                            qrSession = null
                            showHint("微信二维码加载失败，请重试")
                        }
                        Text("等待授权确认…", style = MaterialTheme.typography.bodyMedium)
                    }
                    LoginHint(hint, false, "auth-wechat-hint")
                    DesignTextButton(
                        onClick = ::startWechat,
                        enabled = !busy,
                        modifier = Modifier.testTag("auth-wechat-start"),
                    ) {
                        Text(if (busy) "正在连接微信…" else if (qrSession == null) "获取二维码" else "刷新二维码")
                    }
                }
            }
            DesignTextButton(onClick = onOffline, modifier = Modifier.testTag("gate-offline")) { Text("先离线使用") }
            if (step != 0) {
                DesignTextButton(
                    onClick = ::back,
                    enabled = !busy,
                    modifier = Modifier.testTag(if (step == 2) "gate-wechat-back" else "gate-back"),
                ) { Text("返回") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoginHint(message: String, success: Boolean, tag: String) {
    if (message.isNotBlank()) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag(tag)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private fun validWechatUrl(url: String): Boolean = runCatching {
    val uri = Uri.parse(url)
    val callback = Uri.parse(uri.getQueryParameter("redirect_uri").orEmpty())
    uri.scheme == "https" && uri.host == "open.weixin.qq.com" && uri.userInfo == null &&
        uri.path == "/connect/qrconnect" && callback.scheme == "https" && callback.host != null &&
        callback.userInfo == null && callback.path.orEmpty().endsWith("/v1/auth/wechat/qr/callback")
}.getOrDefault(false)

@Composable
private fun WechatQrPage(url: String, modifier: Modifier, onLoadFailed: () -> Unit) {
    val callback = Uri.parse(Uri.parse(url).getQueryParameter("redirect_uri").orEmpty())
    androidx.compose.runtime.key(url) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url
                            val destination = target.buildUpon().clearQuery().fragment(null).build()
                            return target.scheme != "https" || target.userInfo != null ||
                                (target.host != "open.weixin.qq.com" && destination != callback)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) onLoadFailed()
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                            if (request.isForMainFrame) onLoadFailed()
                        }
                    }
                    contentDescription = "微信登录二维码"
                    loadUrl(url)
                }
            },
            modifier = modifier.testTag("auth-wechat-qr"),
            onRelease = WebView::destroy,
            update = {},
        )
    }
}
