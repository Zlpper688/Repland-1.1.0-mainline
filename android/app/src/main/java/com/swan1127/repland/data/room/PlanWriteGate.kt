package com.swan1127.repland.data.room

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 计划只有一个写入序列：手动 UI、对话工具、自动引擎都在这里排队，事务只保证原子性，
 * 这把闸保证「读快照→求解→落库」之间不会被另一个写者穿插覆盖。
 */
class PlanWriteGate {
    private val mutex = Mutex()

    suspend fun <T> serialize(action: suspend () -> T): T = mutex.withLock { action() }
}
