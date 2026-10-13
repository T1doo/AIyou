package com.t1doo.aiyou.ai

import com.t1doo.aiyou.domain.AuthorizedResultReceipt
import com.t1doo.aiyou.domain.SendDenial
import com.t1doo.aiyou.model.SecretReference
import java.io.Closeable

/** Resolution is external to the authority lock. A returned value transfers ownership. */
fun interface SecretResolver {
    fun resolve(reference: SecretReference): ResolvedSecret?
}

/** A short-lived owned value, never a configuration field or a printable diagnostic. */
class ResolvedSecret private constructor(ownedCharArray: CharArray) : Closeable {
    private var characters: CharArray? = ownedCharArray

    val isClosed: Boolean get() = synchronized(this) { characters == null }

    @Synchronized internal fun headerValue(): String? {
        val value = characters ?: return null
        if (value.size !in 1..8192 || value.any { it.code !in 33..126 }) return null
        return String(value)
    }

    @Synchronized override fun close() {
        characters?.fill('\u0000')
        characters = null
    }

    override fun toString() = "ResolvedSecret(<redacted>)"

    companion object {
        /** Transfers this array; the caller must not mutate or reuse it after this call. */
        fun fromChars(ownedCharArray: CharArray): ResolvedSecret = ResolvedSecret(ownedCharArray)
    }
}

data class GuardedSendOutcome(
    val terminal: StreamEvent,
    val wireOutcome: HttpOutcome? = null,
    val denial: SendDenial? = null,
    val receipt: AuthorizedResultReceipt? = null,
    val resultDenial: SendDenial? = null,
)

interface GuardedStreamingExchange {
    /** Wire completion is preview only unless the result also carries a current receipt. */
    fun execute(onEvent: (StreamEvent) -> Unit = {}): GuardedSendOutcome
    fun cancel()
}
