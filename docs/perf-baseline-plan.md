# 性能基线方案：Macrobenchmark 帧门禁 + JankStats 观测

更新：2026-11-03（Asia/Shanghai）。

> **交付性质说明（重要）**：本文是方案文档 + 可直接粘贴的配置样例，**不是已接入的可跑代码**。
> 下文 `benchmark/` 模块的两个文件是「落地时新建」的目标内容；在该模块真正创建并跑通
> `gradlew :benchmark:connectedCheck` 之前，仓库中不存在 `:benchmark` 模块，CI 不会执行任何
> 性能测试。本文中的代码片段**不参与编译**，API 以实际集成的 androidx.benchmark 版本为准。

## 1. 为什么先用文档交付

- 引入新 Gradle 模块需要真实构建验证（本次工程化任务明确禁止运行任何 Gradle 命令，
  主会话正在跑仪器测试，并发构建会互相干扰）。
- 帧性能基线必须在 **release（非 debuggable）变体** + 真机/模拟器上测量：`debug` 变体
  关闭 R8、开启 Compose 调试特性，帧数据不可信。

## 2. 目标场景与阈值

- **场景**：Timeline（今日时间轴）拖拽滚动——最核心的交互面。
- **指标**：`FrameTimingMetric` 的 **P95 帧时间**（比均值更贴近用户感知的最差体验）。
- **阈值**：
  - **16.7 ms（60fps 帧预算）硬阈值**：超过即断言失败（CI 红灯）；
  - **33 ms（30fps）警报线**：打印严重卡顿告警。
- **分阶段收敛**（防止新门禁上线即长期红）：落地初期若 P95 长期超过 16.7ms，允许临时把
  两条线对调（33ms 作为 CI 硬阈值、16.7ms 打 warn），但必须在任务跟踪里挂着收敛项，
  目标是回到 16.7ms 硬阈值。对调属于阶段性迁移，不算长期放宽。

## 3. 落地步骤（将来执行，按序）

1. 新建 `android/benchmark/src/androidTest/java/com/swan1127/repland/benchmark/` 目录，
   粘贴 §4 的 `build.gradle.kts`（放 `android/benchmark/`）与 §5 的 `FrameTimingBenchmark.kt`。
2. `android/settings.gradle.kts` 追加一行：`include(":benchmark")`。
3. 根 `android/build.gradle.kts` 的 `plugins` 块追加 `alias(libs.plugins.kotlin.android) apply false`，
   并在 `gradle/libs.versions.toml` 的 `[plugins]` 补：
   `kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }`。
4. `android/app/src/main/AndroidManifest.xml` 的 `<application>` 内加一行（`xmlns:tools`
   已声明；非 debuggable 构建必须 profileable 才能采样）：
   ```xml
   <profileable android:shell="true" tools:targetApi="q" />
   ```
5. 确认 `:app` 的 release 签名已配置（`REPLAND_RELEASE_STORE_FILE/_STORE_PASSWORD/
   _KEY_ALIAS/_KEY_PASSWORD`，见 docs/RELEASE.md）——`:app:validateReleaseSigning` 门禁
   会在打包 release 时强制要求；本地跑基线可把四个变量指向 debug keystore。
6. 在一台在线设备/模拟器上运行：`.\gradlew.bat :benchmark:connectedCheck`。
   **必须在 release 变体上跑**（benchmark 模块的 `benchmark` buildType 通过
   `matchingFallbacks` 对到 `:app` 的 `release`），结果才是可信帧数据。
7. 把 P95/P99 结果记入 §7 基线表，作为后续回归对比。

## 4. `benchmark/build.gradle.kts` 全文（可直接粘贴）

```kotlin
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android") // 需先在根 build.gradle.kts + libs.versions.toml 注册，见 §3 步骤 3
}

android {
    namespace = "com.swan1127.repland.benchmark"
    compileSdk = 36

    defaultConfig {
        // Macrobenchmark 的 frame metrics 依赖 API 24+，官方建议 28+；本应用 minSdk 24，
        // 基线模块单独抬高，不影响 :app。
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 帧基线必须在非 debuggable 变体上测量：benchmark 自身变体 "benchmark" 通过
    // matchingFallbacks 对到 :app 的 release。
    testBuildType = "benchmark"
    targetProjectPath = ":app"

    buildTypes {
        create("benchmark") {
            debuggable = false
            // 基线 APK 自身用 debug 签名即可（被测对象是 :app 的 release 构建）。
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4") // 以最新稳定版为准
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
}
```

## 5. `benchmark/src/androidTest/java/com/swan1127/repland/benchmark/FrameTimingBenchmark.kt` 全文（样例）

```kotlin
package com.swan1127.repland.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// 被测包名：release 变体（无 .qa 后缀）。若改测 internal 变体用 com.swan1127.repland.current。
private const val TARGET_PACKAGE = "com.swan1127.repland"

/** 60fps 帧预算：P95 硬阈值，超过即断言失败。 */
private const val P95_HARD_MS = 16.7
/** 30fps 警报线：超过打印严重卡顿告警（分阶段收敛见 docs/perf-baseline-plan.md §2）。 */
private const val P95_WARN_MS = 33.0

@RunWith(AndroidJUnit4::class)
class FrameTimingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun timelineDragScrollP95() {
        val result = benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                // Timeline 是启动首屏（今日页时间轴）。若入口调整，用
                // device.findObject(By.res(...)).click() 显式导航到 Timeline。
            },
        ) {
            // 模拟 Timeline 拖拽滚动：屏幕中部自下而上慢速拖动 8 次，贴近真实滚动。
            val centerX = device.displayWidth / 2
            val top = (device.displayHeight * 0.7).toInt()
            val bottom = (device.displayHeight * 0.3).toInt()
            repeat(8) {
                device.swipe(centerX, top, centerX, bottom, 60) // steps=60 的慢速拖拽
                device.waitForIdle()
            }
        }

        // P95 取值 API 以 androidx.benchmark 1.3.x 为参照（MetricResult#percentiles 为
        // 纳秒数组，P50/P90/P95/P99）；若集成版本不同请按其 API 调整——本文件是文档样例。
        val p95Ms = result.getMetric(FrameTimingMetric()).percentiles[2] / 1_000_000.0

        if (p95Ms > P95_WARN_MS) {
            println("WARN: Timeline 拖拽 P95 帧时间 ${"%.2f".format(p95Ms)} ms，已超过 33ms 严重卡顿线。")
        }
        check(p95Ms <= P95_HARD_MS) {
            "Timeline 拖拽滚动 P95 帧时间 ${"%.2f".format(p95Ms)} ms 超过硬阈值 $P95_HARD_MS ms（60fps 帧预算）。"
        }
    }
}
```

## 6. JankStats 最小集成片段（仅说明，**不修改主代码**）

生产环境持续观测用 JankStats（与 Macrobenchmark 门禁互补：门禁抓回归，JankStats 看真实设备分布）。
依赖（接入时以最新稳定版为准）：`androidx.metrics:metrics-jankstats`。
接入位置：拿到 `Activity` 的 `window` 后（如 `MainActivity` 创建视图后或根 composable 侧效）：

```kotlin
// 仅文档说明片段；实际接入时放入 ui 层，并遵循下面的数据约束。
private val jankListener = JankStats.OnFrameListener { frameData ->
    // frameData.durationMs / frameData.jankDurationMs / frameData.states
    // 只写本地日志或自有遥测管道；禁止外传第三方云（同 docs/crash-reporting-options.md 的隐私约束）。
}

val stats = JankStats.createAndTrack(window, jankListener)
stats.isTrackingEnabled = true // 离开界面/后台时置 false
```

约束：JankStats 数据默认不上报任何第三方云；如需聚合，走与崩溃上报相同的自建端点
（见 docs/crash-reporting-options.md）。

## 7. 基线记录表

| 日期 | 提交 | 设备 | 场景 | P95 (ms) | P99 (ms) | 备注 |
| ---- | ---- | ---- | ---- | -------- | -------- | ---- |
| （待首次 `:benchmark:connectedCheck` 后填写） | | | Timeline 拖拽滚动 | | | |
