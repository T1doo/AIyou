package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Real root/recap route at double font; no repository read, model call or generation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class MemoryToolsReturnPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, supportsFactRecaps = true))
    private var enqueues = 0
    private val recap = FactRecapController({ local.value }, { change -> local.value = change(local.value) },
        { enqueues++; false }, Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC))
    @org.junit.After fun close() = recap.close()
    private fun authorizationOwner() = object : RecordAuthorizationRepository {
        override fun readAuthorization(source: SourceVersion): RecordAuthorizationSnapshot = error("No permission read requested")
        override fun previewAuthorization(expected: RecordAuthorizationSource, purpose: TextPurpose,
            clock: () -> Instant): RecordAuthorizationPreview = error("No permission preview requested")
        override fun changeAuthorization(request: RecordAuthorizationChange,
            clock: () -> Instant): RecordAuthorizationReceipt = error("No permission write requested")
    }

    private fun content(restoration: StateRestorationTester? = null) {
        val screen: @Composable () -> Unit = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme {
                    AIyouApp(previewDestination = Destination.MEMORIES, local = local.value,
                        currentRecords = { local.value }, factRecaps = recap,
                        display = UiPreferencesState(UiPreferences(showCompanion = false, reduceMotion = true), ready = true))
                }
            }
        }
        if (restoration == null) compose.setContent(screen) else restoration.setContent(screen)
    }

    private fun openRecap() {
        compose.showNotebookToolsFor("recap-open")
        compose.onNodeWithTag("recap-open").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertNotNull(local.value.factRecap); assertEquals(0, enqueues) }
    }

    @Test fun returnFromRecapKeepsCallerToolsVisibleAndPanelClosed() {
        content(); openRecap()
        compose.onNodeWithTag("notebook-tab-MEMORIES").assertIsDisplayed().performClick()
        compose.onNodeWithTag("notebook-review-tools").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onAllNodesWithTag("notebook-tools-close").assertCountEquals(0)
        compose.runOnIdle { assertNull(local.value.factRecap); assertEquals(0, enqueues) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("notebook-review-tools").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("notebook-tools-close").assertCountEquals(0)
        compose.runOnIdle { assertNull(local.value.factRecap); assertEquals(0, enqueues) }
    }

    @Test fun readinessChangeAndRecreationDropDisclosureAndNeverReopenOldPanel() {
        val restoration = StateRestorationTester(compose)
        content(restoration)
        compose.showNotebookToolsFor("recap-open")
        compose.onNodeWithTag("notebook-tools-close").assertIsEnabled().performClick()
        compose.runOnIdle { local.value = local.value.copy(ready = false) }
        compose.waitForIdle()
        compose.runOnIdle { local.value = local.value.copy(ready = true) }
        compose.onAllNodesWithTag("notebook-review-tools").assertCountEquals(0)
        compose.onAllNodesWithTag("notebook-tools-close").assertCountEquals(0)
        compose.showNotebookToolsFor("recap-open")
        compose.onNodeWithTag("notebook-tools-close").performClick()
        val retiredToggle = checkNotNull(compose.onNodeWithTag("memory-tools-disclosure").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.runOnIdle { local.value = local.value.copy(recordAuthorizationOwner = authorizationOwner()) }
        compose.onAllNodesWithTag("notebook-review-tools").assertCountEquals(0)
        compose.runOnIdle {
            // A retained framework semantics action is no longer a live UI action.
            // Pinned Compose rejects its click-sound lookup on the detached node.
            val rejected = assertThrows(IllegalStateException::class.java) { retiredToggle() }
            assertTrue(rejected.message.orEmpty().contains("not currently attached"))
        }
        compose.onAllNodesWithTag("notebook-review-tools").assertCountEquals(0)
        compose.showNotebookToolsFor("recap-open")
        compose.onNodeWithTag("notebook-tools-close").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodesWithTag("notebook-review-tools").assertCountEquals(0)
        compose.onAllNodesWithTag("notebook-tools-close").assertCountEquals(0)
        compose.runOnIdle { assertNull(local.value.factRecap); assertEquals(0, enqueues) }
        openRecap()
    }
}
