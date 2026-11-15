package com.swan1127.repland.server

import kotlinx.serialization.Serializable

/**
 * App → 后端的通知投递请求（T4a）。
 *
 * 语义边界（FEISHU_INTEGRATION_NOTES §2/§3）：
 * - 后端按「账号 token → 队员」鉴权，open_id 绑定关系完全由后端持有，App 不接触飞书凭证；
 * - channel 只消费 PUSH_AND_FEISHU，APP_ONLY 由请求方（App）自行不发。
 */
@Serializable
data class NotifyRequest(
    val teamId: String,
    val teamName: String,
    /** 受众成员的 memberId（App 侧已把「队长」解析为队长 memberId） */
    val audienceId: String,
    val audienceName: String,
    /** "APP_ONLY" | "PUSH_AND_FEISHU"（§16 通知分级） */
    val channel: String,
    val message: String,
    /** 幂等键：App 端通知 id，防重复投递 */
    val notificationId: String,
    /** T4c：卡片元数据（null = 纯文本）。refId 在裁决卡承载 objectionId（value 契约见 CardActionService） */
    val card: NotifyCard? = null,
)

/**
 * 卡片消息请求（T4c，FEISHU_INTEGRATION_NOTES §3/§4）：
 * 后端按 cardKind 选模板构造 interactive 卡片，按钮 value 携带
 * `{"kind":动作,"teamId","draftId":refId,"revision"}`，回调经 CardActionService 落确认台账。
 */
@Serializable
data class NotifyCard(
    /** draft_confirm | transfer_adjudication */
    val cardKind: String,
    /** 草案 id；裁决类承载 objectionId */
    val refId: String,
    val revision: Int = 1,
)

/** 队员的账号绑定：账号 token 即身份，open_id 即飞书投递目标。 */
@Serializable
data class AccountBinding(
    val accountToken: String,
    val teamId: String,
    val memberId: String,
    val memberName: String,
    /** 空串 = 尚未绑定飞书（自动降级为仅 App 通知，不报错） */
    val openId: String = "",
    /** 队长标记（§10 队长默认全权限）：卡片裁决类回调按此校验，权限控制台同步后细化 */
    val isCaptain: Boolean = false,
)

@Serializable
data class NotifyResponse(
    val status: String,
    val detail: String = "",
    val feishuDisabled: Boolean = false,
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val feishuConfigured: Boolean,
    val boundAccounts: Int,
)

/** GET /v1/me：App 配置页「测试连接」用（T4b），校验账号 token 并回显绑定状态。 */
@Serializable
data class MeResponse(
    val teamId: String,
    val memberId: String,
    val memberName: String,
    val isCaptain: Boolean,
    /** false = 尚未绑定飞书 open_id（通知仅走 App） */
    val feishuBound: Boolean,
)

/** 卡片确认记录（T4b）：飞书卡片回调等价于一次显式确认操作，记账后由 App 增量拉取。 */
@Serializable
data class ConfirmationRecord(
    /** 全局单调递增序号，App 端增量拉取游标 */
    val seq: Long,
    val teamId: String,
    /** confirm_draft | abandon_draft | keep_transfer | reclaim_transfer */
    val kind: String,
    val draftId: String,
    val revision: Int,
    val operatorMemberId: String,
    val operatorName: String,
    val operatorOpenId: String,
    /** ISO-8601 */
    val decidedAt: String,
)

@Serializable
data class ConfirmationsResponse(
    val items: List<ConfirmationRecord>,
    /** 下次拉取传回 since 即可取增量 */
    val cursor: Long,
)

/** 台账条目副本请求（T4c，§5：只做副本不做同步，fire-and-forget，副本落后不影响 App）。 */
@Serializable
data class LedgerCopyRequest(
    val teamId: String,
    val teamName: String,
    /** 幂等键：App 端台账条目 id */
    val entryId: String,
    /** LedgerKind name（§5 副本表「类型」列） */
    val kind: String,
    /** 事项（App 侧 evidence 原文） */
    val evidence: String,
    val memberName: String,
    /** epoch 毫秒（§5「时间」列；App 侧本地时区换算） */
    val occurredAtMs: Long,
    /** 证据链接（§5 externalUrl 列；缺省不写该字段） */
    val externalUrl: String? = null,
    val amountCents: Long? = null,
    val isBaseline: Boolean = false,
)

@Serializable
data class LedgerCopyResponse(
    val status: String,
    val detail: String = "",
)

/** 会议写飞书日历请求（T4c，§6：SCHEDULER 候选 → 队长确认 → 后端建日程）。 */
@Serializable
data class MeetingCreateRequest(
    val teamId: String,
    val teamName: String,
    /** 事项（日历标题 = 队伍名 + 事项） */
    val title: String,
    val startAtMs: Long,
    val endAtMs: Long,
    /** App 侧 memberId 列表，后端换算 open_id 附参会人 */
    val attendeeMemberIds: List<String> = emptyList(),
    /** 日历提醒提前分钟数；旧客户端不传时按个人端默认 10 分钟 */
    val reminderMinutes: Int? = null,
)

@Serializable
data class MeetingCreateResponse(
    val status: String,
    val detail: String = "",
    val eventId: String = "",
)
