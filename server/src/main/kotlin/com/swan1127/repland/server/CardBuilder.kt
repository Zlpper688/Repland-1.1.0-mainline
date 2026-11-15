package com.swan1127.repland.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * interactive 卡片构造（T4c，FEISHU_INTEGRATION_NOTES §3/§4）。
 *
 * 按钮 value 严格走 CardActionService 的 value 契约：
 * `{"kind":"confirm_draft|abandon_draft|keep_transfer|reclaim_transfer","teamId","draftId","revision"}`
 * （裁决卡把 objectionId 放进 draftId 槽位；revision 由 App 侧登记草案时给出）。
 */
object CardBuilder {

    /** cardKind → (标题, 模板色, 按钮列表(文案 to 动作 kind)) */
    private val templates = mapOf(
        "draft_confirm" to Triple(
            "待确认草案", "orange",
            listOf("确认生效" to "confirm_draft", "放弃草案" to "abandon_draft"),
        ),
        "transfer_adjudication" to Triple(
            "异议待裁决", "red",
            listOf("维持转派" to "keep_transfer", "收回转派" to "reclaim_transfer"),
        ),
    )

    fun isKnown(cardKind: String): Boolean = cardKind in templates

    /**
     * @return 整张卡片 JSON 字符串（im/v1 messages 的 content）；未知 cardKind 返回 null（调用方按投递失败处理）。
     */
    fun build(cardKind: String, teamId: String, refId: String, revision: Int, message: String): String? {
        val (title, template, actions) = templates[cardKind] ?: return null
        val card = buildJsonObject {
            put("config", buildJsonObject { put("wide_screen_mode", true) })
            put(
                "header",
                buildJsonObject {
                    put("template", template)
                    put(
                        "title",
                        buildJsonObject {
                            put("tag", "plain_text")
                            put("content", "【Repland】$title")
                        },
                    )
                },
            )
            put("elements", buildElements(message, teamId, refId, revision, actions))
        }
        return Json.encodeToString(JsonObject.serializer(), card)
    }

    private fun buildElements(
        message: String,
        teamId: String,
        refId: String,
        revision: Int,
        actions: List<Pair<String, String>>,
    ) = buildJsonArray {
        add(
            buildJsonObject {
                put("tag", "div")
                put("text", buildJsonObject { put("tag", "lark_md"); put("content", message) })
            },
        )
        add(buildJsonObject { put("tag", "hr") })
        if (actions.isNotEmpty()) {
            add(
                buildJsonObject {
                    put("tag", "action")
                    put(
                        "actions",
                        buildJsonArray {
                            actions.forEachIndexed { index, (label, kind) ->
                                add(
                                    buildJsonObject {
                                        put("tag", "button")
                                        put(
                                            "text",
                                            buildJsonObject {
                                                put("tag", "plain_text")
                                                put("content", label)
                                            },
                                        )
                                        put("type", if (index == 0) "primary" else "default")
                                        put(
                                            "value",
                                            buildJsonObject {
                                                put("kind", kind)
                                                put("teamId", teamId)
                                                put("draftId", refId)
                                                put("revision", revision)
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }
}
