package com.t1doo.aiyou.records

import com.t1doo.aiyou.ai.ResolvedSecret
import com.t1doo.aiyou.ai.SecretResolver
import com.t1doo.aiyou.domain.ConnectionSecretVault
import com.t1doo.aiyou.model.SecretReference

/** Local owned-memory bridge; no key entry, client creation, request or fallback resolver. */
internal class LocalConnectionSecretResolver(private val vault: ConnectionSecretVault) : SecretResolver {
    override fun isCurrent(reference: SecretReference) = vault.current(reference)
    override fun watch(reference: SecretReference,onRetired: ()->Unit)=vault.watch(reference,onRetired)
    override fun resolve(reference: SecretReference): ResolvedSecret? {
        if(!vault.current(reference)) return null
        val owned=vault.resolve(reference)?:return null
        val chars=try { owned.copyCharacters() } finally { owned.close() }
        return try {
            if(!vault.current(reference)) null else ResolvedSecret.fromChars(chars.copyOf())
        } finally { chars.fill('\u0000') }
    }
}
