package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.ProcessingDefaults
import com.t1doo.aiyou.model.ProcessingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProcessingRuleTest {
    @Test
    fun newInstallationDefaultsToLocalOnly() {
        assertEquals(ProcessingPolicy.LOCAL_ONLY, ProcessingDefaults.policy)
        assertFalse(mayProcessRemotely(ProcessingDefaults.policy, true))
    }

    @Test
    fun restrictiveRecordPoliciesOverrideRemoteConsent() {
        for (policy in listOf(ProcessingPolicy.AI_NONE, ProcessingPolicy.LOCAL_ONLY)) {
            assertFalse(mayProcessRemotely(policy, false))
            assertFalse(mayProcessRemotely(policy, true))
        }
    }

    @Test
    fun remoteAllowedStillRequiresMatchingCurrentConsent() {
        assertFalse(mayProcessRemotely(ProcessingPolicy.REMOTE_ALLOWED, false))
        assertTrue(mayProcessRemotely(ProcessingPolicy.REMOTE_ALLOWED, true))
    }
}
