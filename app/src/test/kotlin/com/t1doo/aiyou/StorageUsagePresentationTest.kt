package com.t1doo.aiyou

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.StorageUsageReader
import com.t1doo.aiyou.model.LocalStorageUsage
import com.t1doo.aiyou.records.LocalRecordUiState
import com.t1doo.aiyou.ui.StorageUsageController
import com.t1doo.aiyou.ui.LocalStorageUsageQueryObserver
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual settings route with synthetic metadata; no Android storage/device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class StorageUsagePresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitQueryAndRefreshShowCategoriesAndRetiredActualButtonCannotQueryNextVisit() {
        var reads = 0
        var measured = LocalStorageUsage(13, 17, 19, 23)
        var actualQuery: (() -> Unit)? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { StorageUsageController(scope, StorageUsageReader { reads++; measured }, Dispatchers.Unconfined) }
            CompositionLocalProvider(LocalStorageUsageQueryObserver provides { actualQuery = it }) {
                AIyouTheme { AIyouApp(local = LocalRecordUiState(ready = true, opening = false, text = "合成保留草稿"),
                    storageUsage = controller) }
            }
        }
        compose.onNodeWithText("设置").performClick()
        val query = compose.onNodeWithTag("storage-usage-query").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithTag("storage-usage-total").assertDoesNotExist()
        query.performClick()
        compose.onNodeWithText("文件大小合计：72 B").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("数据库与写入日志：13 B").assertExists()
        compose.onNodeWithText("加密附件：17 B").assertExists()
        compose.onNodeWithText("加密编辑草稿：19 B").assertExists()
        compose.onNodeWithText("其他记录文件：23 B").assertExists()
        compose.runOnIdle { assertEquals(1, reads); measured = LocalStorageUsage(31) }
        compose.onNodeWithText("刷新占用").performScrollTo().performClick()
        compose.onNodeWithText("文件大小合计：31 B").performScrollTo().assertIsDisplayed()
        // Capture the very handler supplied to the real Button, not an unattached Modifier node.
        val retired = compose.runOnIdle { checkNotNull(actualQuery) }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithContentDescription("此刻，想记下什么").assertTextEquals("合成保留草稿")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("storage-usage-query").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, reads); retired() }
        compose.onNodeWithTag("storage-usage-total").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, reads) }
        compose.onNodeWithText("查询占用").performClick()
        compose.onNodeWithText("文件大小合计：31 B").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(3, reads) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun narrowDoubleFontFailureNeverShowsZeroAndExplicitRetryCanConfirmEmptyFiles() {
        var fail = true
        var reads = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { StorageUsageController(scope, StorageUsageReader {
                reads++
                if (fail) throw IOException("synthetic-private-path")
                LocalStorageUsage()
            }, Dispatchers.Unconfined) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AIyouTheme { AIyouApp(previewSettings = true, storageUsage = controller) }
            }
        }
        val query = compose.onNodeWithTag("storage-usage-query").performScrollTo().assertIsDisplayed()
        val bounds = query.getUnclippedBoundsInRoot()
        assertTrue(bounds.width >= 48.dp && bounds.height >= 48.dp)
        query.performClick()
        compose.onNodeWithTag("storage-usage-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("storage-usage-total").assertDoesNotExist()
        compose.onNodeWithText("synthetic-private-path", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reads); fail = false }
        compose.onNodeWithText("重试查询占用").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("文件大小合计：0 B").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("storage-usage-error").assertDoesNotExist()
        compose.onNodeWithText("不含应用安装包、密钥和你另存的导出文件；不代表设备剩余空间。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("刷新占用").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(2, reads) }
    }
}
