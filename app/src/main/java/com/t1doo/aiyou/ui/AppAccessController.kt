package com.t1doo.aiyou.ui

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AppAccessPreference(val version: Long, val enabled: Boolean)
internal interface AppAccessPreferenceStorage {
    fun read(): AppAccessPreference
    /** Compare-and-set and read back. An exception is an uncertain outcome, never a disabled default. */
    fun write(expected: AppAccessPreference, enabled: Boolean): AppAccessPreference
}

internal enum class AppAccessPhase { LOADING, LOCKED, AUTHENTICATING, SAVING, OPENING, OPEN, FAILED }
internal enum class AppAccessPurpose { UNLOCK, ENABLE, DISABLE }
internal class AppAccessAuthentication(val key: String, val purpose: AppAccessPurpose) {
    override fun toString() = "AppAccessAuthentication(redacted)"
}
internal data class AppAccessState(
    val phase: AppAccessPhase = AppAccessPhase.LOADING,
    val preference: AppAccessPreference? = null,
    val session: Long = 0,
    val authentication: AppAccessAuthentication? = null,
    val mayReturn: Boolean = false,
    val message: String? = null,
) {
    val canReadRecords: Boolean get() = phase == AppAccessPhase.OPEN && session > 0
    override fun toString() = "AppAccessState(phase=$phase)"
}

/** Startup/manual access boundary. No original text, credential, Saver or automatic background policy. */
internal class AppAccessController(
    private val storage: AppAccessPreferenceStorage,
    private val scope: CoroutineScope,
    private val retireSession: () -> Boolean,
    private val beforeOpen: suspend () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newKey: () -> String = { "aiyou-app-access-" + UUID.randomUUID() },
) {
    private val guard = Any()
    private val mutable = MutableStateFlow(AppAccessState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var sessions = 0L
    private var claimed: String? = null
    private var disposed = false

    init { load() }

    private fun load() {
        val token = synchronized(guard) {
            if (disposed || mutable.value.session != 0L) return
            claimed = null
            mutable.value = AppAccessState()
            ++generation
        }
        scope.launch {
            try {
                val preference = withContext(io) { storage.read() }
                synchronized(guard) {
                    if (disposed || generation != token) return@launch
                    mutable.value = AppAccessState(if (preference.enabled) AppAccessPhase.LOCKED else AppAccessPhase.OPENING,
                        preference = preference)
                }
                if (!preference.enabled) finishOpen(token, preference)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { fail(token, "启动保护设置暂不可确认，资料尚未打开。请重试读取。") }
        }
    }

    fun retryRead() = synchronized(guard) {
        if (!disposed && mutable.value.phase == AppAccessPhase.FAILED) load()
    }

    fun requestUnlock() = synchronized(guard) {
        val current = mutable.value
        if (disposed || current.phase != AppAccessPhase.LOCKED || current.preference?.enabled != true || current.mayReturn) return
        request(AppAccessPurpose.UNLOCK, current.preference)
    }

    fun requestChange(session: Long, enabled: Boolean) = synchronized(guard) {
        val current = mutable.value
        val preference = current.preference ?: return
        if (disposed || !current.canReadRecords || current.session != session || enabled == preference.enabled) return
        val retired = try { retireSession() } catch (_: Exception) {
            ++generation
            mutable.value = AppAccessState(AppAccessPhase.FAILED, message = "资料会话关闭状态尚未确认，请重试核对。")
            return
        }
        if (!retired) {
            mutable.value = current.copy(message = "请先完成当前保存或关闭编辑，再更改启动应用锁。")
            return
        }
        request(if (enabled) AppAccessPurpose.ENABLE else AppAccessPurpose.DISABLE, preference)
    }

    private fun request(purpose: AppAccessPurpose, preference: AppAccessPreference) {
        ++generation
        claimed = null
        mutable.value = AppAccessState(AppAccessPhase.AUTHENTICATING, preference = preference,
            authentication = AppAccessAuthentication(newKey(), purpose))
    }

    fun claimAuthentication(key: String): Boolean = synchronized(guard) {
        if (disposed || mutable.value.phase != AppAccessPhase.AUTHENTICATING ||
            mutable.value.authentication?.key != key || claimed != null) false
        else { claimed = key; true }
    }

    fun authenticationUnavailable(key: String) = finishAuthentication(key, false,
        "系统锁屏验证不可用。需要设备已有的安全锁屏；应用不会创建或配置密码。")

    fun authenticationResult(key: String, accepted: Boolean) = finishAuthentication(key, accepted,
        "验证已取消，启动保护设置未更改。")

    private fun finishAuthentication(key: String, accepted: Boolean, declinedMessage: String) {
        val work = synchronized(guard) {
            val current = mutable.value
            val authentication = current.authentication ?: return
            val preference = current.preference ?: return
            if (disposed || current.phase != AppAccessPhase.AUTHENTICATING || authentication.key != key || claimed != key) return
            claimed = null
            val token = ++generation
            if (!accepted) {
                mutable.value = AppAccessState(AppAccessPhase.LOCKED, preference = preference,
                    mayReturn = authentication.purpose != AppAccessPurpose.UNLOCK, message = declinedMessage)
                return
            }
            mutable.value = AppAccessState(if (authentication.purpose == AppAccessPurpose.UNLOCK)
                AppAccessPhase.OPENING else AppAccessPhase.SAVING, preference = preference)
            Triple(token, authentication.purpose, preference)
        }
        scope.launch {
            val (token, purpose, previous) = work
            try {
                if (!owned(token)) return@launch
                val confirmed = if (purpose == AppAccessPurpose.UNLOCK) previous else withContext(io) {
                    if (!owned(token)) throw CancellationException("Retired app access operation")
                    val requested = purpose == AppAccessPurpose.ENABLE
                    val receipt = storage.write(previous, requested)
                    check(receipt.enabled == requested && receipt.version == previous.version + 1)
                    check(storage.read() == receipt)
                    receipt
                }
                synchronized(guard) {
                    if (disposed || generation != token) return@launch
                    mutable.value = AppAccessState(AppAccessPhase.OPENING, preference = confirmed)
                }
                finishOpen(token, confirmed)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { fail(work.first, "启动保护状态尚未确认，资料保持关闭。请重新读取核对。") }
        }
    }

    fun returnToPriorAccess() {
        val work = synchronized(guard) {
            val current = mutable.value
            val preference = current.preference ?: return
            if (disposed || current.phase != AppAccessPhase.LOCKED || !current.mayReturn) return
            val token = ++generation
            mutable.value = AppAccessState(AppAccessPhase.OPENING, preference = preference)
            token to preference
        }
        scope.launch {
            try { finishOpen(work.first, work.second) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { fail(work.first, "旧资料会话尚未确认关闭，暂不能重新打开。请重试核对。") }
        }
    }

    fun lock(session: Long) = synchronized(guard) {
        val current = mutable.value
        if (disposed || !current.canReadRecords || current.session != session || current.preference?.enabled != true) return
        val retired = try { retireSession() } catch (_: Exception) {
            ++generation
            mutable.value = AppAccessState(AppAccessPhase.FAILED, message = "资料会话关闭状态尚未确认，请重试核对。")
            return
        }
        if (!retired) {
            mutable.value = current.copy(message = "请先完成当前保存或关闭编辑，再锁定应用。")
            return
        }
        ++generation
        mutable.value = AppAccessState(AppAccessPhase.LOCKED, preference = current.preference)
    }

    private suspend fun finishOpen(token: Long, preference: AppAccessPreference) {
        if (!owned(token)) return
        beforeOpen()
        synchronized(guard) {
            if (disposed || generation != token || mutable.value.phase != AppAccessPhase.OPENING) return
            mutable.value = AppAccessState(AppAccessPhase.OPEN, preference = preference, session = ++sessions)
        }
    }

    private fun owned(token: Long) = synchronized(guard) { !disposed && generation == token }

    private fun fail(token: Long, message: String) = synchronized(guard) {
        if (!disposed && generation == token) mutable.value = AppAccessState(AppAccessPhase.FAILED, message = message)
    }

    fun dispose() = synchronized(guard) {
        if (disposed) return
        disposed = true; ++generation; claimed = null
        mutable.value = AppAccessState(AppAccessPhase.LOCKED)
    }
}
