package com.swan1127.repland.data

import android.content.Context

/**
 * 用户自己写的「关于我」自述：第一人称、用户资产，可随时改、随时清空。
 *
 * 与记忆画像刻意分开：画像是从隐式证据（专注/完成/顺延）推出来的信号，这段是用户亲口写的，
 * 进提示词时排在推测信号前面、并标明出处。它不是排期输入，不落 Room、不改时长优先级，
 * 只帮助手理解「你是谁、你在乎什么」。空串表示用户还没写，提示词里就不出现这一段。
 */
class PersonaDocStore(context: Context) {
    private val prefs = context.getSharedPreferences("persona_doc", Context.MODE_PRIVATE)

    fun load(): String = prefs.getString(KEY_DOC, "").orEmpty()

    fun save(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) prefs.edit().remove(KEY_DOC).apply()
        else prefs.edit().putString(KEY_DOC, trimmed).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_DOC).apply()
    }

    private companion object {
        const val KEY_DOC = "doc"
    }
}