package com.t1doo.aiyou

import android.app.Application
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.t1doo.aiyou.ui.FileAppAccessPreference
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual production Activity startup under Robolectric; no device/Keystore/native execution claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppAccessStartupPresentationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val seed = object : ExternalResource() {
        override fun before() {
            val app: Application = RuntimeEnvironment.getApplication()
            val storage = FileAppAccessPreference(File(app.filesDir, "access/startup"))
            storage.write(storage.read(), true)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    @Test fun productionLockedStartupAndRecreationNeverOpenRecordsOrExposeTheirSurface() {
        compose.waitUntil(2_000) { compose.onAllNodes(hasText("应用已锁定")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("app-access-gate").assertIsDisplayed()
        compose.onNodeWithText("应用已锁定").assertIsDisplayed()
        compose.onAllNodes(hasText("设置")).assertCountEquals(0)
        compose.runOnIdle {
            assertNull(compose.activity.admittedRecords())
            assertFalse(File(compose.activity.noBackupFilesDir, "records").exists())
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("app-access-unlock").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertNull(compose.activity.admittedRecords())
            assertFalse(File(compose.activity.noBackupFilesDir, "records").exists())
        }
    }
}
