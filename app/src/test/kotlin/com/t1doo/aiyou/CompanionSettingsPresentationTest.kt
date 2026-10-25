package com.t1doo.aiyou

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.designsystem.MascotState
import com.t1doo.aiyou.designsystem.CompanionPose
import com.t1doo.aiyou.designsystem.MascotAssetProvider
import com.t1doo.aiyou.designsystem.LuluMascotAssets
import com.t1doo.aiyou.designsystem.NeutralMascotAssets
import com.t1doo.aiyou.ui.*
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.model.ProcessingPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production routes with synthetic record state, actual Compose semantics; not TalkBack/device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class CompanionSettingsPresentationTest {
    @get:Rule val compose = createComposeRule(effectContext = object : androidx.compose.ui.MotionDurationScale {
        override val scaleFactor = 1f
    })

    @Test @Config(qualifiers = "w320dp-h640dp")
    fun explicitDoubleFontInteractionsCancelAndRetireWithoutWritingOrReopeningThePanel() {
        val display = mutableStateOf(UiPreferencesState(UiPreferences(reduceMotion = true), ready = true))
        val local = LocalRecordUiState(ready = true, opening = false, text = "合成互动前草稿")
        var writes = 0
        var observed = CompanionInteraction.Snapshot()
        var motion = 0f
        var callbacks: CompanionDialogActions? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f),
                LocalCompanionInteractionObserver provides { state, value, actions -> observed = state; motion = value; callbacks = actions }) {
                AIyouTheme { AIyouApp(local = local, display = display.value, onSave = { writes++ }) }
            }
        }
        compose.onNodeWithContentDescription("打开陪伴伙伴").performClick()
        compose.onNodeWithTag("companion-artwork-fallback").assertDoesNotExist()
        compose.onNodeWithTag("companion-greet").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("你好，慢慢记录就好。").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0f, motion, 0f) }
        compose.onNodeWithTag("companion-quiet").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(CompanionPose.Idle, observed.pose); assertEquals(CompanionPose.Idle, observed.feedback) }
        for ((tag, text, pose) in listOf(
            Triple("companion-think", "慢慢想，不着急。这只是陪伴动作。", CompanionPose.Thinking),
            Triple("companion-yawn", "打个哈欠，歇一小会儿。", CompanionPose.Yawning))) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
                .assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(pose, observed.feedback); assertEquals(0f, motion, 0f); assertEquals(0, writes) }
            compose.onNodeWithTag("companion-quiet").performScrollTo().performClick()
            compose.runOnIdle { assertNull(observed.run); assertEquals(CompanionPose.Idle, observed.feedback) }
        }
        compose.onNodeWithTag("companion-encourage").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("按自己的节奏来，小小一步也很好。").performScrollTo().assertIsDisplayed()
        val retired = checkNotNull(callbacks)
        compose.onNodeWithText("关闭陪伴面板").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("打开陪伴伙伴").performClick()
        compose.runOnIdle {
            retired.greet(); retired.encourage(); retired.think(); retired.yawn(); retired.quiet(); retired.close()
            assertEquals(CompanionPose.Idle, observed.feedback); assertEquals(0, writes)
        }
        compose.onNodeWithText("关闭陪伴面板").assertIsDisplayed()
        val hidden = checkNotNull(callbacks)
        compose.runOnIdle { display.value = display.value.copy(preferences = display.value.preferences.copy(showCompanion = false)) }
        compose.onNodeWithText("关闭陪伴面板").assertDoesNotExist()
        compose.runOnIdle { hidden.greet(); hidden.encourage(); hidden.think(); hidden.yawn(); hidden.close() }
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertDoesNotExist()
        compose.runOnIdle { display.value = display.value.copy(preferences = display.value.preferences.copy(showCompanion = true)) }
        compose.onNodeWithText("关闭陪伴面板").assertDoesNotExist()
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals(local.text)
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun thinkingAndYawningUseDistinctLocalArtworkAndCancelWithoutRecordSideEffects() {
        var observed = CompanionInteraction.Snapshot()
        var motion = 0f
        var actions: CompanionDialogActions? = null
        var writes = 0
        val display = mutableStateOf(UiPreferencesState(UiPreferences(reduceMotion = true), ready = true))
        val local = LocalRecordUiState(ready = true, opening = false, text = "合成动作前草稿")
        val output = java.io.File("build/reports/companion-motion/extra-poses").apply { mkdirs() }
        val trace = org.json.JSONArray()
        val runs = java.util.IdentityHashMap<CompanionInteraction.Run, Int>()
        var phase = "open"
        var composition = 0
        fun observe(point: String, buttons: org.json.JSONObject? = null) {
            check(trace.length() < 128)
            trace.put(org.json.JSONObject().put("phase", phase).put("point", point)
                .put("clock_ms", compose.mainClock.currentTime).put("pose", observed.pose.name)
                .put("feedback", observed.feedback.name).put("foreground", observed.foreground)
                .put("retired", observed.retired).put("motion", motion)
                .put("run", observed.run?.let { runs.getOrPut(it) { runs.size + 1 } } ?: org.json.JSONObject.NULL)
                .put("buttons", buttons ?: org.json.JSONObject.NULL))
            java.io.File(output, "admission.json").writeText(org.json.JSONObject()
                .put("source_sha", System.getenv("GITHUB_SHA") ?: "local-unbound")
                .put("observations", trace).toString(2))
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCompanionInteractionObserver provides { state, value, callbacks ->
                observed = state; motion = value; actions = callbacks
                observe("composition-${++composition}")
            }) { AIyouTheme { AIyouApp(local = local, display = display.value, onSave = { writes++ }) } }
        }
        fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithContentDescription("打开陪伴伙伴").performClick()
        repeat(2) { frame() }
        val images = mutableListOf<android.graphics.Bitmap>()
        val starts = listOf("companion-greet", "companion-encourage", "companion-think", "companion-yawn")
        fun buttons(): org.json.JSONObject {
            val result = org.json.JSONObject()
            for (tag in starts + "companion-quiet") {
                val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
                val values = org.json.JSONArray()
                nodes.forEach { node -> values.put(org.json.JSONObject()
                    .put("disabled", node.config.contains(SemanticsProperties.Disabled))
                    .put("left", node.boundsInRoot.left).put("top", node.boundsInRoot.top)
                    .put("right", node.boundsInRoot.right).put("bottom", node.boundsInRoot.bottom)) }
                result.put(tag, org.json.JSONObject().put("count", nodes.size).put("nodes", values))
            }
            return result
        }
        fun consumed(expected: CompanionPose) {
            var ready = false
            var frames = 0
            while (!ready && frames < 4) {
                frame(); frames++
                val sample = buttons()
                val unique = (starts + "companion-quiet").all { sample.getJSONObject(it).getInt("count") == 1 }
                val startDisabled = unique && starts.all {
                    sample.getJSONObject(it).getJSONArray("nodes").getJSONObject(0).getBoolean("disabled") == (expected != CompanionPose.Idle)
                }
                val quietEnabled = unique && !sample.getJSONObject("companion-quiet").getJSONArray("nodes").getJSONObject(0).getBoolean("disabled")
                compose.runOnIdle {
                    observe("publication-$frames", sample)
                    ready = observed.foreground && !observed.retired && observed.pose == expected && observed.feedback == expected &&
                        (if (expected == CompanionPose.Idle) observed.run == null else observed.run != null) && startDisabled && quietEnabled
                }
            }
            compose.runOnIdle {
                assertTrue("Real action was not consumed within four frames: $trace", ready)
                assertEquals(expected, observed.pose); assertEquals(expected, observed.feedback)
                assertEquals(0f, motion, 0f); assertEquals(0, writes)
            }
        }
        for ((tag, pose) in listOf("companion-think" to CompanionPose.Thinking, "companion-yawn" to CompanionPose.Yawning)) {
            phase = tag
            compose.onNodeWithTag(tag).performScrollTo()
            frame() // Finish the scroll/layout separately from action publication.
            val before = buttons()
            compose.runOnIdle { observe("before-click", before) }
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
            val after = buttons()
            compose.runOnIdle { observe("after-click", after) }
            consumed(pose)
            compose.runOnIdle {
                assertEquals(pose, observed.pose); assertNotNull(observed.run); assertEquals(0f, motion, 0f)
                assertEquals(0, writes)
            }
            compose.onNodeWithTag("companion-artwork-fallback").assertDoesNotExist()
            compose.onNodeWithTag("companion-interaction-artwork").performScrollTo()
            frame()
            val capturedRun = observed.run
            compose.runOnIdle { assertNotNull(capturedRun); assertEquals(pose, observed.pose); assertEquals(0f, motion, 0f); observe("before-capture") }
            val bitmap = compose.onNodeWithTag("companion-interaction-artwork").captureToImage().asAndroidBitmap()
            compose.runOnIdle { assertSame(capturedRun, observed.run); assertEquals(pose, observed.pose); assertEquals(0f, motion, 0f); observe("after-capture") }
            images += bitmap
            java.io.File(output, "$tag.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            phase = "$tag-quiet"
            compose.onNodeWithTag("companion-quiet").performScrollTo()
            frame()
            val quietBefore = buttons()
            compose.runOnIdle { observe("before-click", quietBefore) }
            compose.onNodeWithTag("companion-quiet").assertIsDisplayed().performClick()
            val quietAfter = buttons()
            compose.runOnIdle { observe("after-click", quietAfter) }
            consumed(CompanionPose.Idle)
            compose.runOnIdle { assertNull(observed.run); assertEquals(CompanionPose.Idle, observed.pose); assertEquals(0f, motion, 0f) }
        }
        assertFalse("Two explicit poses must render different bundled artwork", images[0].sameAs(images[1]))
        val retired = checkNotNull(actions)
        phase = "close"
        fun closingSample(): org.json.JSONObject {
            val sample = buttons()
            val closeNodes = compose.onAllNodesWithText("关闭陪伴面板").fetchSemanticsNodes()
            val bounds = org.json.JSONArray()
            closeNodes.forEach { node -> bounds.put(org.json.JSONObject()
                .put("disabled", node.config.contains(SemanticsProperties.Disabled))
                .put("left", node.boundsInRoot.left).put("top", node.boundsInRoot.top)
                .put("right", node.boundsInRoot.right).put("bottom", node.boundsInRoot.bottom)) }
            return sample.put("close_nodes", closeNodes.size).put("close_bounds", bounds)
                .put("artwork_nodes", compose.onAllNodesWithTag("companion-interaction-artwork").fetchSemanticsNodes().size)
        }
        val closeBefore = closingSample()
        compose.runOnIdle { observe("before-click", closeBefore) }
        compose.onNodeWithText("关闭陪伴面板").assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).performClick()
        val closeAfter = closingSample()
        compose.runOnIdle { observe("after-click", closeAfter) }
        var removed = false
        var closingFrames = 0
        while (!removed && closingFrames < 4) {
            frame(); closingFrames++
            val sample = closingSample()
            removed = sample.getInt("close_nodes") == 0 && sample.getInt("artwork_nodes") == 0 &&
                (starts + "companion-quiet").all { sample.getJSONObject(it).getInt("count") == 0 }
            compose.runOnIdle { observe("publication-$closingFrames", sample) }
        }
        assertTrue("Real close was not consumed within four frames: $trace", removed)
        compose.onNodeWithText("关闭陪伴面板").assertDoesNotExist()
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals(local.text)
        compose.runOnIdle { observe("before-retired-callbacks"); retired.think(); retired.yawn(); assertEquals(0, writes); observe("after-retired-callbacks") }
        // Do not poll for recovery after a retired callback: it must leave the closed tree absent.
        compose.onNodeWithText("关闭陪伴面板").assertDoesNotExist()
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals(local.text)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun finiteInteractionMotionStopsOnReductionAndBackgroundWithoutAutomaticReplay() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        val visibleOwner = mutableStateOf<androidx.lifecycle.LifecycleOwner>(owner)
        val artwork = mutableStateOf<MascotAssetProvider>(LuluMascotAssets)
        val allowed = mutableStateOf(true)
        var watchQuiet = false
        val quietMotion = mutableListOf<Float>()
        var closes = 0
        var observed = CompanionInteraction.Snapshot()
        var motion = 0f
        var callbacks: CompanionDialogActions? = null
        val frames = org.json.JSONArray()
        val output = java.io.File("build/reports/companion-motion/interactions").apply { mkdirs() }
        val observations = org.json.JSONArray()
        val runs = java.util.IdentityHashMap<CompanionInteraction.Run, Int>()
        val owners = java.util.IdentityHashMap<androidx.lifecycle.LifecycleOwner, Int>()
        var phase = "setup"
        var button = org.json.JSONObject()
        var lastObservation = org.json.JSONObject()
        var consumedOwner: androidx.lifecycle.LifecycleOwner? = null
        var consumedOwnerState: androidx.lifecycle.Lifecycle.State? = null
        var consumedAllowed: Boolean? = null
        var consumedAssets: MascotAssetProvider? = null
        fun assetName(value: MascotAssetProvider?) = when (value) {
            LuluMascotAssets -> "Lulu"
            NeutralMascotAssets -> "Neutral"
            null -> "unconsumed"
            else -> "other"
        }
        fun observe(point: String) {
            check(observations.length() < 512) { "Bounded interaction trace exceeded" }
            val run = observed.run
            val runId = run?.let { runs.getOrPut(it) { runs.size + 1 } }
            lastObservation = org.json.JSONObject().put("point", point).put("phase", phase)
                .put("virtual_time_ms", compose.mainClock.currentTime)
                .put("owner", visibleOwner.value.lifecycle.currentState.name).put("allowed", allowed.value)
                .put("owner_id", owners.getOrPut(visibleOwner.value) { owners.size + 1 })
                .put("consumed_owner_id", consumedOwner?.let { owners.getOrPut(it) { owners.size + 1 } } ?: org.json.JSONObject.NULL)
                .put("consumed_owner_state", consumedOwnerState?.name ?: org.json.JSONObject.NULL)
                .put("consumed_allowed", consumedAllowed ?: org.json.JSONObject.NULL)
                .put("requested_assets", assetName(artwork.value)).put("consumed_assets", assetName(consumedAssets))
                .put("run", runId ?: org.json.JSONObject.NULL).put("pose", observed.pose.name)
                .put("feedback", observed.feedback.name).put("foreground", observed.foreground)
                .put("retired", observed.retired).put("motion", motion).put("button", button)
            observations.put(lastObservation)
            java.io.File(output, "motion-probe.json").writeText(org.json.JSONObject()
                .put("source_sha", System.getenv("GITHUB_SHA") ?: "local-unbound")
                .put("method", "finiteInteractionMotionStopsOnReductionAndBackgroundWithoutAutomaticReplay")
                .put("kind", "Read-only controlled-clock state observations; not device evidence")
                .put("observations", observations).toString(2))
        }
        val actionTags = listOf("companion-greet", "companion-encourage", "companion-quiet")
        fun sampleButtons(): org.json.JSONObject {
            val result = org.json.JSONObject()
            for (tag in actionTags) {
                val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
                val samples = org.json.JSONArray()
                for (node in nodes) {
                    val bounds = node.boundsInRoot
                    samples.put(org.json.JSONObject().put("disabled", node.config.contains(SemanticsProperties.Disabled))
                        .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom))
                }
                result.put(tag, org.json.JSONObject().put("count", nodes.size).put("nodes", samples))
            }
            return result
        }
        fun buttonsMatch(samples: org.json.JSONObject, enabled: Boolean): Boolean = actionTags.all { tag ->
            val sample = samples.getJSONObject(tag)
            sample.getInt("count") == 1 && sample.getJSONArray("nodes").getJSONObject(0).getBoolean("disabled") != enabled
        }
        fun publishHostState(label: String, change: () -> Unit, tag: String? = null,
                             buttonsEnabled: Boolean = true, consumed: () -> Boolean) {
            val before = sampleButtons()
            compose.runOnIdle { phase = label; button = org.json.JSONObject().put("semantics", before); observe("before"); change() }
            var published = false
            var count = 0
            while (!published && count < 4) {
                compose.waitForIdle() // Deliver host snapshot/lifecycle notifications before advancing Compose.
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); count++
                val samples = sampleButtons()
                val matches = if (tag == null) -1 else compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
                compose.runOnIdle {
                    button = org.json.JSONObject().put("semantics", samples).put("expected_enabled", buttonsEnabled)
                        .put("publication_tag", tag ?: org.json.JSONObject.NULL).put("matches", matches)
                    observe("publicationFrame-$count")
                    // Outer SideEffect consumption alone does not prove AlertDialog button semantics were published.
                    if (consumed() && buttonsMatch(samples, buttonsEnabled) && (tag == null || matches == 1)) {
                        observe("consumed"); published = true; observe("settled")
                    }
                }
            }
            compose.runOnIdle {
                if (!published) observe("unconsumed")
                assertTrue("Host state was not consumed within four publication frames: $lastObservation", published)
            }
        }
        fun clickAndCheckFirstFrame(label: String, tag: String, expected: CompanionPose) {
            compose.runOnIdle { phase = label; button = org.json.JSONObject().put("tag", tag); observe("request") }
            val target = compose.onNodeWithTag(tag).performScrollTo()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.runOnIdle { observe("scrollStable") } // Layout frame before input, not the action's first frame.
            target.assertIsDisplayed().assertIsEnabled()
            val node = target.fetchSemanticsNode()
            compose.runOnIdle {
                val bounds = node.boundsInRoot
                button = org.json.JSONObject().put("tag", tag).put("displayed_assertion", true)
                    .put("enabled", !node.config.contains(SemanticsProperties.Disabled))
                    .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom)
                observe("before")
            }
            target.performClick()
            compose.runOnIdle { observe("afterClick") }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                observe("firstFrame")
                assertEquals("First frame feedback: $lastObservation", expected, observed.feedback)
                assertEquals("First frame pose: $lastObservation", expected, observed.pose)
                if (expected == CompanionPose.Idle) assertNull("First frame cancelled run: $lastObservation", observed.run)
                else assertNotNull("First frame active run: $lastObservation", observed.run)
            }
        }
        fun pixels(name: String? = null): IntArray {
            fun requireRequestedPose() {
                val expected = when (name) {
                    "01-greeting" -> CompanionPose.Greeting
                    "02-encouraging" -> CompanionPose.Encouraging
                    else -> CompanionPose.Idle
                }
                assertEquals("Requested capture $name: $lastObservation", expected, observed.pose)
                if (expected != CompanionPose.Idle) assertNotNull("Requested capture $name must have an active run: $lastObservation", observed.run)
            }
            if (name != null) compose.runOnIdle { observe("capture-before:$name"); requireRequestedPose() }
            val bitmap = compose.onNodeWithTag("companion-interaction-artwork").performScrollTo().assertIsDisplayed()
                .captureToImage().asAndroidBitmap()
            if (name != null) {
                compose.runOnIdle { observe("capture-after:$name"); requireRequestedPose() }
                java.io.File(output, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                frames.put(org.json.JSONObject().put("file", "$name.png").put("pose", observed.pose.name)
                    .put("requested_motion", motion).put("virtual_time_ms", compose.mainClock.currentTime))
                java.io.File(output, "frames.json").writeText(org.json.JSONObject()
                    .put("source_sha", System.getenv("GITHUB_SHA") ?: "local-unbound")
                    .put("kind", "Actual controlled-clock native Compose captures; independent poses, not continuous animation or device performance")
                    .put("frames", frames).toString(2))
            }
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val renderedOwner = visibleOwner.value
            val renderedAllowed = allowed.value
            val renderedAssets = artwork.value
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides renderedOwner,
                LocalCompanionInteractionObserver provides { state, value, actions ->
                    // Called by the real CompanionDialog SideEffect; capture its exact input values, not current host holders.
                    consumedOwner = renderedOwner; consumedOwnerState = renderedOwner.lifecycle.currentState
                    consumedAllowed = renderedAllowed; consumedAssets = renderedAssets
                    observed = state; motion = value; callbacks = actions
                    observe("composition")
                    if (watchQuiet && state.run == null) quietMotion += value
                }) {
                AIyouTheme { CompanionDialog(onClose = { closes++ }, motionAllowed = renderedAllowed, assets = renderedAssets) }
            }
        }
        publishHostState("initial-owner", { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }) {
            consumedOwner === owner && consumedOwnerState == androidx.lifecycle.Lifecycle.State.RESUMED && observed.foreground &&
                consumedAllowed == true && consumedAssets === LuluMascotAssets && observed.run == null && motion == 0f
        }
        val idlePixels = pixels("00-idle")
        clickAndCheckFirstFrame("greeting-before-reduction", "companion-greet", CompanionPose.Greeting)
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { observe("64ms") }
        compose.onNodeWithTag("companion-artwork-fallback").assertDoesNotExist()
        val greetingPixels = pixels("01-greeting")
        assertFalse("Explicit greeting renders a distinct actual pose", idlePixels.contentEquals(greetingPixels))
        compose.runOnIdle { assertTrue(kotlin.math.abs(motion) > .01f) }
        publishHostState("motion-disabled", { allowed.value = false }) {
            consumedAllowed == false && observed.run == null && motion == 0f
        }
        compose.runOnIdle { assertEquals(0f, motion, 0f) }
        publishHostState("motion-enabled", { allowed.value = true }) {
            consumedAllowed == true && observed.run == null && motion == 0f
        }
        compose.runOnIdle { assertEquals(0f, motion, 0f); assertNull(observed.run) }
        clickAndCheckFirstFrame("encouragement-before-quiet", "companion-encourage", CompanionPose.Encouraging)
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { observe("96ms") }
        compose.onNodeWithTag("companion-artwork-fallback").assertDoesNotExist()
        assertFalse("Encouragement renders its own actual pose", greetingPixels.contentEquals(pixels("02-encouraging")))
        compose.runOnIdle { assertTrue("Active encouragement before quiet: $lastObservation", motion > 0f); watchQuiet = true }
        clickAndCheckFirstFrame("quiet-cancel", "companion-quiet", CompanionPose.Idle)
        compose.runOnIdle {
            assertNull(observed.run); assertEquals(CompanionPose.Idle, observed.pose)
            assertTrue("Observe the first quiet composition before relying on a later animation reset", quietMotion.isNotEmpty())
            assertTrue("Every quiet composition must suppress retained translation/scale input", quietMotion.all { it == 0f })
            assertEquals(0f, motion, 0f); watchQuiet = false
        }
        assertArrayEquals("Quiet cancellation must restore the unshifted, unscaled idle raster", idlePixels, pixels("02b-quiet-still"))
        clickAndCheckFirstFrame("encouragement-before-background", "companion-encourage", CompanionPose.Encouraging)
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            observe("96ms"); assertTrue("Active encouragement before background: $lastObservation", motion > 0f)
        }
        publishHostState("background", { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }, buttonsEnabled = false) {
            consumedOwner === owner && consumedOwnerState == androidx.lifecycle.Lifecycle.State.STARTED &&
                !observed.foreground && observed.run == null && motion == 0f
        }
        assertArrayEquals("Background cancels back to the quiet raster", idlePixels, pixels("03-background-still"))
        compose.onNodeWithTag("companion-greet").assertIsNotEnabled()
        compose.runOnIdle {
            phase = "background-rejected-callback"; observe("before")
            checkNotNull(callbacks).encourage(); observe("afterCallback")
            assertEquals(CompanionPose.Idle, observed.pose); assertEquals(0f, motion, 0f)
        }
        publishHostState("foreground-return", { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }) {
            consumedOwner === owner && consumedOwnerState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                observed.foreground && observed.run == null && motion == 0f
        }
        compose.runOnIdle { assertNull(observed.run); assertEquals(0f, motion, 0f) }
        clickAndCheckFirstFrame("encouragement-natural-finish", "companion-encourage", CompanionPose.Encouraging)
        // The helper consumed the first frame: retain the original total of 40 frames, not 41.
        repeat(39) { frame ->
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            if (frame == 5) compose.runOnIdle { observe("96ms"); assertTrue("Natural action is moving: $lastObservation", motion > 0f) }
        }
        compose.runOnIdle { observe("settled"); assertNull(observed.run); assertEquals(CompanionPose.Idle, observed.pose); assertEquals(0f, motion, .001f) }
        val oldOwnerCallbacks = checkNotNull(callbacks)
        val replacement = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        publishHostState("owner-replacement", {
            replacement.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED; visibleOwner.value = replacement
        }) {
            consumedOwner === replacement && consumedOwnerState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                observed.foreground && observed.run == null && observed.feedback == CompanionPose.Idle && motion == 0f
        }
        val beforeOldCallbacks = sampleButtons()
        val callbackTime = compose.mainClock.currentTime
        compose.runOnIdle {
            phase = "retired-owner-callbacks"; button = org.json.JSONObject().put("semantics", beforeOldCallbacks); observe("before")
            assertTrue("New owner's buttons must already be enabled before old callbacks: $lastObservation", buttonsMatch(beforeOldCallbacks, true))
            oldOwnerCallbacks.greet(); oldOwnerCallbacks.encourage(); oldOwnerCallbacks.close()
        }
        // Read actual semantics immediately, with no frame advance or recovery polling after the retired callbacks.
        val afterOldCallbacks = sampleButtons()
        compose.runOnIdle {
            button = org.json.JSONObject().put("semantics", afterOldCallbacks)
            observe("afterCallbacks")
            assertEquals("Do not wait for recovery after old callbacks", callbackTime, compose.mainClock.currentTime)
            assertEquals(CompanionPose.Idle, observed.pose); assertEquals(0, closes)
            assertTrue("Old callbacks must not disable the new owner's actual buttons: $lastObservation", buttonsMatch(afterOldCallbacks, true))
        }
        compose.onNodeWithTag("companion-greet").assertIsEnabled()
        compose.runOnIdle { assertNull(observed.run); assertEquals(CompanionPose.Idle, observed.pose) }
        publishHostState("fallback-asset", { artwork.value = NeutralMascotAssets }, "companion-artwork-fallback") {
            consumedAssets === NeutralMascotAssets && observed.run == null && observed.pose == CompanionPose.Idle && motion == 0f
        }
        compose.onNodeWithTag("companion-artwork-fallback").performScrollTo().assertIsDisplayed()
        val staticFallback = pixels()
        clickAndCheckFirstFrame("fallback-greeting", "companion-greet", CompanionPose.Greeting)
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { observe("64ms"); assertTrue(kotlin.math.abs(motion) > .01f) }
        assertArrayEquals("Unavailable pose must keep the actual fallback pixels static", staticFallback, pixels())
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun captureDefaultHasExplicitLocalChoicesAndRetiredOrUnconfirmedActionsCannotChangeIt() {
        val display = mutableStateOf(UiPreferencesState(UiPreferences(false, true), ready = true))
        val local = LocalRecordUiState(ready = true, opening = false, text = "合成已有草稿")
        val actions = mutableMapOf<ProcessingPolicy, () -> Unit>()
        var changes = 0
        var retries = 0
        compose.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalCaptureDefaultActionObserver provides { policy, action -> actions[policy] = action }) {
                AIyouTheme { AIyouApp(local = local, display = display.value,
                    onDisplayChange = { changes++; display.value = display.value.copy(preferences = it) },
                    onRetryDisplay = { retries++ }) }
            }
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("capture-default-LOCAL_ONLY").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("capture-default-AI_NONE").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        val retired = checkNotNull(actions[ProcessingPolicy.LOCAL_ONLY])
        compose.runOnIdle { assertEquals(1, changes); assertEquals("合成已有草稿", local.text) }
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { retired(); assertEquals(1, changes) }
        compose.onNodeWithText("设置").performClick()
        compose.runOnIdle { display.value = display.value.copy(saveUnconfirmed = true, error = "设置保存尚未确认") }
        compose.onNodeWithTag("capture-default-LOCAL_ONLY").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { actions.getValue(ProcessingPolicy.LOCAL_ONLY)(); assertEquals(1, changes) }
        compose.onNodeWithText("重试核对默认范围").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.runOnIdle { display.value = UiPreferencesState(error = "设置暂不可读取") }
        compose.onNodeWithTag("capture-default-read-failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("capture-default-AI_NONE").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals("合成已有草稿", local.text); assertEquals(1, changes) }
    }

    @Test fun companionIsLocalOptionalAndSettingsReturnPreservesCapture() {
        val display = mutableStateOf(UiPreferencesState(UiPreferences(), ready = true))
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = "合成保留草稿"))
        var changes = 0
        var saves = 0
        compose.setContent { AIyouTheme { AIyouApp(local = local.value, display = display.value,
            onDisplayChange = { changes++; display.value = display.value.copy(preferences = it) }, onSave = { saves++ }) } }
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertIsDisplayed().performClick()
        compose.onNodeWithText("打个招呼").performClick()
        compose.onNodeWithText("你好，慢慢记录就好。").assertIsDisplayed()
        compose.onNodeWithText("关闭陪伴面板").performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals("合成保留草稿", local.value.text) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("companion-switch").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, changes); assertFalse(display.value.preferences.showCompanion) }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertDoesNotExist()
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals("合成保留草稿")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowDoubleFontSettingsHaveOneSwitchTargetAndReachableLicenseAndRetry() {
        var changes = 0
        var retries = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { AIyouApp(previewSettings = true,
                    display = UiPreferencesState(UiPreferences(), ready = true, error = "合成设置失败"),
                    onDisplayChange = { changes++ }, onRetryDisplay = { retries++ }) }
            }
        }
        val intro = compose.onNodeWithText("一只安静的水豚，陪你记下一点生活。", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        val introLayouts = mutableListOf<TextLayoutResult>()
        intro.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(introLayouts)) }
        val introLayout = introLayouts.single()
        assertEquals("一只安静的水豚，陪你记下一点生活。", introLayout.layoutInput.text.text)
        assertEquals(2f, introLayout.layoutInput.density.fontScale, 0f)
        assertFalse("Full-width balanced intro must not clip: ${introLayout.size} / ${introLayout.layoutInput.constraints}", introLayout.hasVisualOverflow)
        val finalLine = introLayout.layoutInput.text.text.substring(introLayout.getLineStart(introLayout.lineCount - 1),
            introLayout.getLineEnd(introLayout.lineCount - 1, visibleEnd = true)).trim().removeSuffix("。")
        assertTrue("The unchanged intro must not strand one final character: $finalLine", finalLine.codePointCount(0, finalLine.length) >= 2)
        val companion = compose.onNodeWithTag("companion-switch").performScrollTo().assertIsDisplayed()
        val title = compose.onNodeWithText("显示陪伴伙伴", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val titleLayout = layouts.single()
        assertEquals("Large-font switch title must not leave its final character on a separate line", 1, titleLayout.lineCount)
        assertFalse("Title size=${titleLayout.size}, paragraph=${titleLayout.multiParagraph.width}x${titleLayout.multiParagraph.height}, " +
            "constraints=${titleLayout.layoutInput.constraints}, density=${titleLayout.layoutInput.density.density}, " +
            "fontScale=${titleLayout.layoutInput.density.fontScale}, lineCount=${titleLayout.lineCount}, " +
            "widthOverflow=${titleLayout.didOverflowWidth}, heightOverflow=${titleLayout.didOverflowHeight}",
            titleLayout.hasVisualOverflow)
        val titleBounds = title.getUnclippedBoundsInRoot()
        val companionBounds = companion.getUnclippedBoundsInRoot()
        assertTrue(titleBounds.left >= companionBounds.left && titleBounds.right <= companionBounds.right &&
            titleBounds.top >= companionBounds.top && titleBounds.bottom <= companionBounds.bottom)
        val toggle = compose.onNodeWithTag("motion-switch").performScrollTo().assertIsDisplayed()
        val bounds = toggle.getUnclippedBoundsInRoot()
        assertTrue(bounds.height >= 48.dp && bounds.width >= 48.dp)
        assertEquals(ToggleableState.Off, toggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        // Child Switch has no independent click action; the entire labeled row owns the toggle.
        compose.onAllNodes(hasAnyAncestor(hasTestTag("motion-switch")) and hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        toggle.performClick()
        compose.runOnIdle { assertEquals(1, changes) }
        compose.onNodeWithText("重试设置").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("开源许可与声明").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("开源许可与声明 · 离线文本").assertExists()
        compose.onNodeWithText("返回设置").performClick()
        compose.onNodeWithTag("settings-scroll").assertExists()
    }

    @Test fun eachProductionDestinationReturnsFromSettingsWithoutChangingSelection() {
        compose.setContent { AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false),
            display = UiPreferencesState(UiPreferences(), ready = true)) } }
        for (destination in Destination.entries) {
            compose.onNode(hasText(destination.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("返回").performClick()
            compose.onNode(hasText(destination.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).assertIsSelected()
            compose.onNodeWithContentDescription("打开陪伴伙伴").assertIsDisplayed()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun shortDoubleFontViewportKeepsDialogCloseAndProductionSaveReachable() {
        var saves = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false, text = "合成短屏草稿"),
                    display = UiPreferencesState(UiPreferences(reduceMotion = true), ready = true), onSave = { saves++ }) }
            }
        }
        compose.onNodeWithContentDescription("打开陪伴伙伴").performClick()
        compose.onNodeWithText("打个招呼").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("关闭陪伴面板").assertIsDisplayed().performClick()
        val save = compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val bounds = save.getUnclippedBoundsInRoot()
        assertTrue(bounds.width >= 48.dp && bounds.height >= 48.dp)
        save.performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals("合成短屏草稿")
    }

    @Test fun loadingPreferencesAndFailedSaveDoNotPublishCompanionSuccess() {
        val display = mutableStateOf(UiPreferencesState())
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, error = "合成保存失败"))
        compose.setContent { AIyouTheme { AIyouApp(local = local.value, display = display.value) } }
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertDoesNotExist()
        compose.runOnIdle { display.value = UiPreferencesState(UiPreferences(reduceMotion = true), ready = true) }
        compose.onNodeWithContentDescription("打开陪伴伙伴").assertIsDisplayed()
        compose.onAllNodesWithText("已保存在本机").assertCountEquals(0)
        compose.onNodeWithText("合成保存失败").assertExists()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun oneNodEndsAndReducedMotionOrRemountCannotReplayIt() {
        val pulse = mutableStateOf(0L)
        val allowed = mutableStateOf(true)
        val visible = mutableStateOf(true)
        var observed = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCompanionObserver provides { _, value -> observed = value }) {
                AIyouTheme { if (visible.value) CompanionEntry(MascotState.Saved, pulse.value, allowed.value, {}) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val frames = org.json.JSONArray()
        val output = java.io.File("build/reports/companion-motion").apply { mkdirs() }
        fun frame(name: String): IntArray {
            val bitmap = compose.onNodeWithTag("companion-entry").captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Actual native artwork must render, not an empty surface", pixels.toSet().size > 16)
            java.io.File(output, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            frames.put(org.json.JSONObject().put("file", "$name.png")
                .put("virtual_time_ms", compose.mainClock.currentTime).put("observed_nod", observed)
                .put("width", bitmap.width).put("height", bitmap.height))
            java.io.File(output, "frames.json").writeText(org.json.JSONObject()
                .put("source_sha", System.getenv("GITHUB_SHA") ?: "local-unbound")
                .put("kind", "Actual Robolectric native graphics captures at controlled Compose clock times; not device video or performance")
                .put("frames", frames).toString(2))
            return pixels
        }
        val still = frame("00-still")
        compose.runOnIdle { pulse.value++ }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertTrue("One finite nod begins", observed > 0f) }
        val moving = frame("01-moving")
        assertFalse("Rendered movement must differ from still artwork", still.contentEquals(moving))
        repeat(30) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertArrayEquals(still, frame("02-settled"))
        compose.runOnIdle { assertEquals(0f, observed, .001f); allowed.value = false; pulse.value++ }
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertArrayEquals(still, frame("03-reduced-motion"))
        compose.runOnIdle { assertEquals(0f, observed, .001f); allowed.value = true }
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertEquals(0f, observed, .001f); visible.value = false }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { visible.value = true }
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertEquals(0f, observed, .001f) }
    }

    @Test fun successfulReadbackAcknowledgementDoesNotReplayOnSettingsReturn() {
        val local = mutableStateOf(LocalRecordUiState(ready = true, opening = false, text = "合成草稿"))
        var observed = MascotState.Idle
        var acknowledgements = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCompanionObserver provides { state, _ ->
                if (state == MascotState.Saved && observed != state) acknowledgements++
                observed = state
            }) {
                AIyouTheme { AIyouApp(local = local.value,
                    display = UiPreferencesState(UiPreferences(reduceMotion = true), ready = true)) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(MascotState.Idle, observed)
            local.value = local.value.copy(text = "", saveNotice = "已保存在本机", saveFeedbackId = 1)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertEquals(MascotState.Saved, observed) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { assertEquals(MascotState.Idle, observed); assertEquals(1, acknowledgements) }
    }

    @Test fun actualMotionGateTracksLifecycleSystemScalePowerSaverAndUserPreference() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        val reduced = mutableStateOf(false)
        var allowed = false
        fun scale(value: Float) {
            org.robolectric.util.ReflectionHelpers.callStaticMethod<Any>(android.animation.ValueAnimator::class.java,
                "setDurationScale", org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, value))
            android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, value)
        }
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED; scale(1f) }
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                allowed = companionMotionAllowed(reduced.value)
            }
        }
        compose.runOnIdle { assertTrue(allowed); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.runOnIdle { assertFalse(allowed); owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.runOnIdle { assertTrue(allowed); scale(0f) }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(allowed); scale(1f) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(allowed)
            org.robolectric.Shadows.shadowOf(power).setIsPowerSaveMode(true)
            context.sendBroadcast(android.content.Intent(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(allowed)
            org.robolectric.Shadows.shadowOf(power).setIsPowerSaveMode(false)
            context.sendBroadcast(android.content.Intent(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(allowed); reduced.value = true }
        compose.runOnIdle { assertFalse(allowed) }
    }
}
