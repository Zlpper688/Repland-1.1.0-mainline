# Repland 1.1.0 QA 交付

Android 包名为 `com.swan1127.repland.qa`，应用版本为 `1.1.0-qa`，版本码为 `1110`。它与原内测版 `com.swan1127.repland.current` 可以并存，各自保存应用数据。

## 本次变更

- Windows 启动脚本冷启动模拟器，等待 Android 就绪后打开 Repland，并在窗口离开屏幕时尝试恢复显示。
- 手机号登录显示验证码请求状态和连接错误。登录页可以重试或转入离线模式。
- 微信登录页使用微信开放平台的扫码授权页面。服务端收到 OAuth 回调后签发 Repland 会话，客户端同步授权返回的昵称与头像。微信登录接口未提供好友列表。

## 安装与构建

从 GitHub 发布草稿下载 QA APK 后，可装到 API 24 或更新版本的 Android 设备。Windows 模拟器建议使用 API 36。

在仓库根目录构建 APK 和运行单元测试：

```powershell
.\android\gradlew.bat -p android :app:assembleDebug :app:testDebugUnitTest :app:verifyOfflineMvpBoundary
.\android\gradlew.bat -p server test
```

Windows 启动脚本读取 `ANDROID_HOME` 或用户目录中的 Android SDK。使用 Android Studio Device Manager 创建 `Repland_1_1_0_API_36`，或者传入已有的模拟器名称。

```powershell
.\tools\Launch-Repland-QA.ps1
.\tools\Launch-Repland-QA.ps1 -Avd Pixel_6 -ApkPath 'C:\Downloads\Repland-1.1.0-qa.apk'
```

## 验证范围

- Android JVM 单元测试、离线边界检查和 Ktor 测试。
- API 36 模拟器上的启动、测试验证码登录、后端不可达提示和离线入口。
- 使用模拟微信接口验证二维码会话、OAuth 回调、轮询凭证和资料返回。

正式短信端点在本次验证环境中遇到 TLS 握手失败。真实微信扫码需要审核通过的网站应用和匹配的 HTTPS 回调，配置见[服务端部署说明](../server/DEPLOY.md)。
