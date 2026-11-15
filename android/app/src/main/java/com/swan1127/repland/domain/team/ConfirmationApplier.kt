package com.swan1127.repland.domain.team

/**
 * 卡片确认记录并入本地主副本（T4c，FEISHU_INTEGRATION_NOTES §4/§7 纪律）：
 * 飞书卡片回调只等价一次显式确认操作 —— 这里按记录逐条调用与 UI 完全相同的
 * [TeamCollabState.commitDraft] / [TeamCollabState.resolveObjection]，不直接改业务状态；
 * 本地没有对应对象（副本落后、草案已被 UI 确认或已失效）时只留提示，不报错不阻塞。
 */
object ConfirmationApplier {

    /** 动作 kind（后端 CardActionService 契约）。 */
    const val KIND_CONFIRM_DRAFT = "confirm_draft"
    const val KIND_ABANDON_DRAFT = "abandon_draft"
    const val KIND_KEEP_TRANSFER = "keep_transfer"
    const val KIND_RECLAIM_TRANSFER = "reclaim_transfer"

    /** 卡片确认记录（后端 /v1/confirmations 条目的域内形状）。 */
    data class Record(
        val seq: Long,
        val kind: String,
        /** 草案 id；裁决类承载 objectionId（对齐后端 value 契约 draftId 槽位） */
        val refId: String,
        val revision: Int,
        val operatorMemberId: String,
        val operatorName: String,
        val decidedAt: String,
    )

    data class Report(val changed: Boolean, val notes: List<String>)

    /**
     * @param pendingDrafts 待确认草案登记（draftId → 草案）；确认/放弃命中即移除。
     */
    fun apply(
        state: TeamCollabState,
        records: List<Record>,
        pendingDrafts: MutableMap<String, TeamPlanDraft>,
    ): Report {
        val notes = mutableListOf<String>()
        var changed = false
        for (record in records) {
            when (record.kind) {
                KIND_CONFIRM_DRAFT -> {
                    val draft = pendingDrafts.remove(record.refId)
                    if (draft == null) {
                        notes += "「${record.operatorName}」的飞书确认已消费（本地无待确认草案或已生效）"
                    } else {
                        runCatching {
                            state.commitDraft(record.operatorMemberId, draft, draft.placements.map { it.taskId }.toSet())
                            changed = true
                            notes += "「${record.operatorName}」在飞书确认草案生效（${draft.placements.size} 段安排）"
                        }.onFailure { failure ->
                            pendingDrafts[record.refId] = draft // 恢复占位：被拒的确认不影响 UI 侧确认
                            notes += "飞书确认草案被拒绝：${failure.message}"
                        }
                    }
                }
                KIND_ABANDON_DRAFT -> {
                    if (pendingDrafts.remove(record.refId) != null) {
                        notes += "「${record.operatorName}」在飞书放弃草案，本地待确认草案已移除"
                    }
                }
                KIND_KEEP_TRANSFER, KIND_RECLAIM_TRANSFER -> {
                    val keep = record.kind == KIND_KEEP_TRANSFER
                    val objection = state.objections.firstOrNull { it.objectionId == record.refId }
                    if (objection == null || objection.resolution != ObjectionResolution.PENDING) {
                        notes += "「${record.operatorName}」的飞书裁决已消费（本地异议不存在或已裁决）"
                    } else {
                        runCatching {
                            state.resolveObjection(record.operatorMemberId, record.refId, keep)
                            changed = true
                            notes += "「${record.operatorName}」在飞书${if (keep) "维持转派" else "收回转派"}"
                        }.onFailure { failure ->
                            notes += "飞书异议裁决被拒绝：${failure.message}"
                        }
                    }
                }
                else -> notes += "未知的确认记录类型：${record.kind}"
            }
        }
        return Report(changed, notes)
    }
}
