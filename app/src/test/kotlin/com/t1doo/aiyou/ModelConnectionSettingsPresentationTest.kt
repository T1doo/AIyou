package com.t1doo.aiyou

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.LocalModelConfigurationActionObserver
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real production settings route/controller; synthetic catalog port, not Android SQLCipher/device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class ModelConnectionSettingsPresentationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun sameOwnerCompositionReplacementRetainsFormAndUnconfirmedRequestButRetiresOldActions() {
        val h=Harness();val generation=mutableStateOf(0);val actions=mutableMapOf<String,()->Unit>()
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f),
                LocalModelConfigurationActionObserver provides { name,action->actions[name]=action }) {
                key(generation.value) { AIyouTheme { AIyouApp(local=h.local,modelConnections=h.controller,previewSettings=true) } }
            }
        }
        create("合成重建输入");lateinit var retired:()->Unit
        compose.runOnIdle { retired=actions.getValue("save");h.controller.close(h.controller.visible().session,retainEditor=true);generation.value++ }
        compose.onNodeWithTag("model-config-name").performScrollTo().assertTextContains("合成重建输入")
        compose.runOnIdle { retired();assertEquals(0,h.catalog.writes);h.catalog.loseAcknowledgement=true }
        compose.onNodeWithTag("model-config-save").performScrollTo().performClick()
        compose.onNodeWithTag("model-config-error").performScrollTo().assertTextEquals("保存结果尚未确认，输入已保留；重试仅核对这一次修改。")
        var request:ModelConnectionChange?=null
        compose.runOnIdle { request=h.controller.visible().editor!!.pending;h.controller.close(h.controller.visible().session,retainEditor=true);generation.value++ }
        compose.onNodeWithTag("model-config-name").performScrollTo().assertIsNotEnabled().assertTextContains("合成重建输入")
        compose.runOnIdle { assertSame(request,h.controller.visible().editor!!.pending);assertEquals(1,h.catalog.writes);h.catalog.loseAcknowledgement=false }
        compose.onNodeWithTag("model-config-retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,h.catalog.writes);assertEquals(1L,h.catalog.values.single().version) }
        compose.onNodeWithText("编辑此配置").performScrollTo().performClick()
        compose.runOnIdle { retired=actions.getValue("save") }
        compose.onNodeWithText("查看虚构示例").performScrollTo().performClick()
        compose.onNodeWithTag("model-config-save").assertDoesNotExist()
        compose.runOnIdle { retired();assertEquals(1,h.catalog.writes) }
        compose.onNodeWithText("退出虚构示例").performScrollTo().performClick()
        compose.onNodeWithText("编辑此配置").performScrollTo().performClick()
        compose.onNodeWithTag("model-config-name").performScrollTo().assertTextContains("合成重建输入")
        compose.runOnIdle { retired();assertEquals(1,h.catalog.writes);assertEquals(1L,h.catalog.values.single().version) }
    }
    @Test fun narrowDoubleFontConfiguresBothProtocolsAndRetiredPageActionsCannotSaveOrDelete() {
        val h=Harness();val actions=mutableMapOf<String,()->Unit>()
        show(h,actions)
        compose.onNodeWithText("设置").performClick();create("合成服务")
        compose.onNodeWithTag("model-config-protocol-ANTHROPIC_MESSAGES").performScrollTo().performClick()
        lateinit var retired:()->Unit
        compose.runOnIdle { retired=actions.getValue("save") }
        compose.onNodeWithTag("model-config-save").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("model-config-notice").performScrollTo().assertTextEquals("已保存本机配置；未设置密钥，尚不可调用。")
        compose.runOnIdle { assertEquals(ConnectionProtocol.ANTHROPIC_MESSAGES,h.catalog.values.single().details.protocol)
            retired();assertEquals(1,h.catalog.writes);assertEquals("合成当前草稿",h.local.text) }
        compose.onNodeWithText("编辑此配置").performScrollTo().performClick()
        compose.onNodeWithTag("model-config-model").performScrollTo().performTextClearance()
        compose.onNodeWithTag("model-config-model").performTextInput("synthetic-second-model")
        compose.onNodeWithTag("model-config-protocol-CHAT_COMPLETIONS").performScrollTo().performClick()
        compose.onNodeWithTag("model-config-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2L,h.catalog.values.single().version);assertEquals(ConnectionProtocol.CHAT_COMPLETIONS,h.catalog.values.single().details.protocol) }
        compose.onNodeWithText("编辑此配置").performScrollTo().performClick()
        compose.onNodeWithText("删除配置",useUnmergedTree=true).performScrollTo().performClick()
        compose.runOnIdle { retired=actions.getValue("delete") }
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { retired();assertEquals(2,h.catalog.writes);assertEquals(1,h.catalog.values.size) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("synthetic-second-model",substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("编辑此配置").performScrollTo().performClick()
        compose.onNodeWithText("删除配置",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithTag("model-config-confirm-delete").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(h.catalog.values.isEmpty());assertEquals(3,h.catalog.writes);assertEquals("合成当前草稿",h.local.text) }
    }
    @Test fun invalidRootAndUncertainSaveKeepInputAndRetryOnlyTheSameConfiguration() {
        val h=Harness();show(h,mutableMapOf());compose.onNodeWithText("设置").performClick();create("合成尚未确认")
        compose.onNodeWithTag("model-config-root").performScrollTo().performTextClearance()
        compose.onNodeWithTag("model-config-root").performTextInput("https://example.test/v1/?key=synthetic")
        compose.onNodeWithTag("model-config-save").performScrollTo().performClick()
        compose.onNodeWithTag("model-config-error").performScrollTo().assertTextEquals("请填写名称、HTTPS API根路径、模型ID及计划用途；根路径不能含凭据、查询参数或最终操作名。")
        compose.runOnIdle { assertEquals(0,h.catalog.writes) }
        compose.onNodeWithTag("model-config-root").performScrollTo().performTextClearance()
        compose.onNodeWithTag("model-config-root").performTextInput("https://example.test/custom/v1/")
        compose.runOnIdle { h.catalog.loseAcknowledgement=true }
        compose.onNodeWithTag("model-config-save").performScrollTo().performClick()
        compose.onNodeWithTag("model-config-error").performScrollTo().assertTextEquals("保存结果尚未确认，输入已保留；重试仅核对这一次修改。")
        compose.onNodeWithTag("model-config-name").performScrollTo().assertIsNotEnabled().assertTextContains("合成尚未确认")
        compose.runOnIdle { h.catalog.loseAcknowledgement=false }
        compose.onNodeWithTag("model-config-retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,h.catalog.writes);assertEquals(1L,h.catalog.values.single().version);assertEquals(2,h.catalog.attempts) }
        compose.onNodeWithTag("model-config-notice").performScrollTo().assertTextEquals("已保存本机配置；未设置密钥，尚不可调用。")
    }
    private fun show(h:Harness,actions:MutableMap<String,()->Unit>) {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f),
                LocalModelConfigurationActionObserver provides { name,action->actions[name]=action }) {
                AIyouTheme { AIyouApp(local=h.local,modelConnections=h.controller) }
            }
        }
    }
    private fun create(name:String) {
        compose.onNodeWithTag("model-config-add").performScrollTo().performClick()
        compose.onNodeWithTag("model-config-name").performScrollTo().performTextInput(name)
        compose.onNodeWithTag("model-config-root").performScrollTo().performTextInput("https://example.test/custom/v1/")
        compose.onNodeWithTag("model-config-model").performScrollTo().performTextInput("synthetic-model")
    }
    private class Catalog:ModelConnectionCatalog {
        var values=emptyList<ModelConnectionConfiguration>();var writes=0;var attempts=0;var loseAcknowledgement=false
        private var last:ModelConnectionChange?=null
        override fun configurations()=values
        override fun change(request:ModelConnectionChange):ModelConnectionConfiguration? {
            attempts++
            if(last===request)return values.singleOrNull()
            val old=values.singleOrNull();if((old?.version?:0)!=request.expectedVersion)throw ModelConnectionConflict()
            val saved=request.details?.let { ModelConnectionConfiguration(request.id,request.expectedVersion+1,it) }
            values=listOfNotNull(saved);last=request;writes++
            if(loseAcknowledgement)throw IOException("Synthetic ACK loss")
            return saved
        }
    }
    private class Harness {
        val catalog=Catalog()
        val local=LocalRecordUiState(ready=true,opening=false,text="合成当前草稿",modelConnectionOwner=catalog)
        private val store=object:EntryRepository {
            override val modelConnections:ModelConnectionCatalog=catalog
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String)=TextDraft("local",1,text)
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No record write")
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No record write")
        }
        val controller=ModelConnectionsController({local},{action->runBlocking { action(store) };true})
    }
}
