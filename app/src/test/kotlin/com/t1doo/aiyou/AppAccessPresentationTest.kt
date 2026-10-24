package com.t1doo.aiyou

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.model.ProcessingPolicy
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.ui.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Production Compose and ActivityResult wiring with a synthetic system-intent boundary. No device authentication claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp")
class AppAccessPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: AppAccessController
    private class Storage(enabled: Boolean) : AppAccessPreferenceStorage {
        @Volatile var value = AppAccessPreference(1, enabled)
        @Volatile var loseAck = false
        val writes = AtomicInteger()
        @Synchronized override fun read() = value
        @Synchronized override fun write(expected: AppAccessPreference, enabled: Boolean): AppAccessPreference {
            check(expected == value); writes.incrementAndGet()
            value = AppAccessPreference(value.version + 1, enabled)
            if (loseAck) throw IOException("synthetic private write receipt")
            return value
        }
    }
    private class Registry : ActivityResultRegistry() {
        val codes = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val intent = contract.createIntent(RuntimeEnvironment.getApplication(), input)
            assertEquals("synthetic.system.credential", intent.action)
            codes += requestCode
        }
    }
    private val registry = Registry()
    private val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    private val actions = AtomicReference<AppAccessSettingsActions>()
    private val opens = AtomicInteger()
    private val retires = AtomicInteger()
    private var preferenceWrites: AtomicInteger? = null
    private val boundaryReady = mutableStateOf(true)
    private var credentialAvailable = true
    private val original = "合成受保护原文🙂，完整文字保留。"

    @After fun close() { if (::controller.isInitialized) controller.dispose(); scope.cancel() }

    private fun content(storage: Storage, largeFont: Boolean = false) {
        preferenceWrites = storage.writes
        controller = AppAccessController(storage, scope, {
            if (boundaryReady.value) { retires.incrementAndGet(); true } else false
        }, {})
        val local = LocalRecordUiState(ready = true, opening = false,
            entries = listOf(StoredEntry("one", 1, original, 0L, "UTC", ProcessingPolicy.LOCAL_ONLY)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner,
                LocalAppAccessCredentialIntentFactory provides AppAccessCredentialIntentFactory { _, _ ->
                    if (credentialAvailable) Intent("synthetic.system.credential") else null
                }, LocalAppAccessSettingsObserver provides { actions.set(it) },
                LocalDensity provides if (largeFont) Density(density.density, 2f) else density) {
                AIyouTheme { AppAccessHost(controller) { session ->
                    DisposableEffect(session) { opens.incrementAndGet(); onDispose {} }
                    AIyouApp(previewDestination = Destination.MEMORIES, local = local, currentRecords = { local },
                        appAccessSettings = { AppAccessSettings(controller, session, boundaryReady.value) })
                } }
            }
        }
    }
    private fun phase(expected: AppAccessPhase) {
        compose.waitForIdle()
        val started = System.nanoTime()
        try {
            compose.waitUntil(2_000) { compose.runOnIdle { controller.state.value.phase == expected } }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("App access phase expected=$expected observed=${controller.state.value.phase} writes=${preferenceWrites?.get()} elapsedNanos=${System.nanoTime()-started}", failure)
        }
        val elapsed = System.nanoTime()-started
        assertTrue("App access phase expected=$expected observed=${controller.state.value.phase} writes=${preferenceWrites?.get()} elapsedNanos=$elapsed exceeded 2000ms",
            elapsed in 0L..2_000_000_000L)
        compose.waitForIdle()
        assertEquals(expected, controller.state.value.phase)
    }
    private fun launched(count: Int) {
        compose.waitForIdle(); compose.waitUntil(2_000) { registry.codes.size == count }; compose.waitForIdle()
        assertEquals(AppAccessPhase.AUTHENTICATING, controller.state.value.phase)
    }
    private fun reply(result: Int) {
        compose.runOnIdle { assertTrue(registry.dispatchResult(registry.codes.last(), result, null)) }
        compose.waitForIdle()
    }
    private fun settings() { compose.onNodeWithText("设置").performClick(); compose.onNodeWithTag("app-access-setting").performScrollTo().assertIsDisplayed() }
    private fun noOriginal() { compose.onAllNodes(hasText(original)).assertCountEquals(0); compose.onAllNodes(hasTestTag("entry-one")).assertCountEquals(0) }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun lockedDoubleFontGateKeepsOriginalAbsentUntilCurrentSystemResultAndRejectsEarlierResult() {
        val storage = Storage(true); credentialAvailable = false; content(storage, largeFont = true)
        phase(AppAccessPhase.LOCKED); noOriginal(); assertEquals(0, opens.get())
        compose.onNodeWithTag("app-access-unlock").performScrollTo().assertIsDisplayed().performClick()
        phase(AppAccessPhase.LOCKED)
        compose.onNodeWithTag("app-access-message").performScrollTo().assertTextContains("系统锁屏验证不可用", substring = true)
        assertEquals(0, registry.codes.size); noOriginal()
        compose.runOnIdle { credentialAvailable = true }
        compose.onNodeWithTag("app-access-unlock").performScrollTo().performClick(); launched(1)
        val old = registry.codes.single(); reply(Activity.RESULT_CANCELED)
        phase(AppAccessPhase.LOCKED); noOriginal(); assertEquals(0, opens.get())
        compose.onNodeWithTag("app-access-unlock").performScrollTo().performClick(); launched(2)
        compose.runOnIdle { registry.dispatchResult(old, Activity.RESULT_OK, null) }
        phase(AppAccessPhase.AUTHENTICATING); noOriginal()
        reply(Activity.RESULT_OK); phase(AppAccessPhase.OPEN)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"))
        compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
        assertEquals(1, opens.get()); assertEquals(0, storage.writes.get())
    }

    @Test fun actualSettingsRequireVerificationRespectBusyBoundaryAndRetireCallbacksAcrossPageAndLock() {
        val storage = Storage(false); content(storage); phase(AppAccessPhase.OPEN); settings()
        val retiredPage = actions.get()
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { retiredPage.change(); retiredPage.lock() }
        phase(AppAccessPhase.OPEN); assertEquals(0, registry.codes.size); assertEquals(0, retires.get())
        settings(); compose.runOnIdle { boundaryReady.value = false }
        compose.onNodeWithTag("app-access-change").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { actions.get().change() }; assertEquals(0, registry.codes.size)
        compose.runOnIdle { boundaryReady.value = true }
        val beforeEnable = actions.get()
        compose.onNodeWithTag("app-access-change").performScrollTo().assertIsEnabled().performClick(); launched(1)
        noOriginal(); reply(Activity.RESULT_OK); phase(AppAccessPhase.OPEN)
        assertTrue(storage.value.enabled); assertEquals(1, storage.writes.get())
        compose.runOnIdle { beforeEnable.change() }; assertEquals(1, registry.codes.size)
        settings(); compose.onNodeWithTag("app-access-setting").assertTextEquals("启动应用锁已启用")
        val oldSettings = actions.get()
        compose.onNodeWithTag("app-access-lock").performScrollTo().performClick()
        phase(AppAccessPhase.LOCKED); noOriginal()
        compose.runOnIdle { oldSettings.lock(); oldSettings.change() }; assertEquals(1, registry.codes.size)
        compose.onNodeWithTag("app-access-unlock").performScrollTo().performClick(); launched(2)
        reply(Activity.RESULT_OK); phase(AppAccessPhase.OPEN); settings()
        compose.onNodeWithTag("app-access-change").performScrollTo().performClick(); launched(3)
        reply(Activity.RESULT_CANCELED); phase(AppAccessPhase.LOCKED); noOriginal()
        compose.onNodeWithTag("app-access-return").performScrollTo().performClick(); phase(AppAccessPhase.OPEN)
        assertTrue(storage.value.enabled); assertEquals(1, storage.writes.get())
        settings(); compose.onNodeWithTag("app-access-change").performScrollTo().performClick(); launched(4)
        reply(Activity.RESULT_OK); phase(AppAccessPhase.OPEN)
        assertFalse(storage.value.enabled); assertEquals(2, storage.writes.get())
    }

    @Test fun uncertainPreferenceWriteKeepsActualProtectedTreeAbsentUntilExplicitReadAndNewVerification() {
        val storage = Storage(false); content(storage); phase(AppAccessPhase.OPEN); settings()
        storage.loseAck = true
        compose.onNodeWithTag("app-access-change").performScrollTo().performClick(); launched(1)
        val used = registry.codes.single(); reply(Activity.RESULT_OK); phase(AppAccessPhase.FAILED)
        noOriginal(); assertTrue(storage.value.enabled); assertEquals(1, storage.writes.get())
        compose.onAllNodes(hasTestTag("app-access-return")).assertCountEquals(0)
        compose.runOnIdle { registry.dispatchResult(used, Activity.RESULT_OK, null) }
        phase(AppAccessPhase.FAILED); noOriginal()
        compose.onNodeWithTag("app-access-retry").performScrollTo().performClick(); phase(AppAccessPhase.LOCKED)
        noOriginal(); assertEquals(1, opens.get()); assertEquals(1, storage.writes.get())
        compose.onNodeWithTag("app-access-unlock").performScrollTo().performClick(); launched(2)
        reply(Activity.RESULT_OK); phase(AppAccessPhase.OPEN)
        assertEquals(2, opens.get()); assertEquals(1, storage.writes.get())
    }
}
