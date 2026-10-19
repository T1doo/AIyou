package com.t1doo.aiyou

import android.os.Parcel
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.LocalModelConfigurationActionObserver
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual settings UI/controller, synthetic vault port; host simulation, never device/key acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class ModelConnectionSecretsPresentationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun narrowDoubleFontExplicitSaveReplaceAndRevokeKeepCapabilitiesUnknownAndRetireOldActions() {
        val h=Harness();val actions=mutableMapOf<String,()->Unit>();show(h,actions)
        open();input(MATERIAL)
        compose.onNodeWithTag("model-secret-input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        lateinit var oldSave:()->Unit
        compose.runOnIdle { oldSave=actions.getValue("secret-save") }
        click("model-secret-save");compose.onNodeWithTag("model-secret-input").assertDoesNotExist()
        compose.onNodeWithTag("model-config-notice").performScrollTo().assertTextEquals("已加密保存本机密钥引用；能力未验证，尚不可调用。")
        var first:SecretReference?=null
        compose.runOnIdle { first=h.catalog.value.secretRef;assertNotNull(first);assertEquals(2L,h.catalog.value.version)
            oldSave();assertEquals(1,h.vault.prepares);assertEquals("合成当前草稿",h.local.text) }
        open();assertInputEmpty();input(SECOND)
        compose.onNodeWithTag("model-secret-save").performScrollTo().assertHeightIsAtLeast(48.dp).assertTextEquals("替换本机密钥").performClick()
        compose.runOnIdle { assertEquals(3L,h.catalog.value.version);assertNotEquals(first,h.catalog.value.secretRef) }
        open();click("model-secret-revoke")
        lateinit var oldRevoke:()->Unit
        compose.runOnIdle { oldRevoke=actions.getValue("secret-revoke") }
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { oldRevoke();assertEquals(3L,h.catalog.value.version) }
        compose.onNodeWithText("设置").performClick();open();click("model-secret-revoke");click("model-secret-confirm-revoke")
        compose.runOnIdle { assertEquals(4L,h.catalog.value.version);assertNull(h.catalog.value.secretRef)
            assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy);assertEquals(0,h.vault.resolveCalls) }
    }
    @Test fun passwordNeverEntersSavedStateAndRestorationOrOwnerReplacementRetiresInputCallbacks() {
        val h=Harness();val actions=mutableMapOf<String,()->Unit>();var registry:SaveableStateRegistry?=null
        val restoration=StateRestorationTester(compose);show(h,actions,restoration=restoration,registry={registry=it})
        open();input(MATERIAL)
        val oldInput=compose.onNodeWithTag("model-secret-input").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        lateinit var oldSave:()->Unit
        compose.runOnIdle { oldSave=actions.getValue("secret-save")
            assertNoSavedMaterial(checkNotNull(registry)) }
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        compose.runOnIdle { oldInput(AnnotatedString(SECOND));oldSave();assertEquals(0,h.vault.prepares) }
        compose.onNodeWithTag("model-secret-input").assertDoesNotExist();open();assertInputEmpty();input(SECOND)
        val retiredInput=compose.onNodeWithTag("model-secret-input").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        compose.runOnIdle { oldSave=actions.getValue("secret-save");h.replaceVault() }
        compose.onNodeWithTag("model-secret-input").assertDoesNotExist();open();assertInputEmpty()
        compose.runOnIdle { retiredInput(AnnotatedString(MATERIAL));oldSave();assertEquals(0,h.vault.prepares)
            assertNoSavedMaterial(checkNotNull(registry)) }
        assertInputEmpty()
    }
    @Test fun uncertainWriteRetainsOnlySameOpaqueOperationAcrossRecreationAndConflictRequiresReload() {
        val h=Harness();val actions=mutableMapOf<String,()->Unit>();val generation=mutableStateOf(0)
        show(h,actions,generation=generation);open();input(MATERIAL)
        compose.runOnIdle { h.vault.loseNextAcknowledgement=true }
        click("model-secret-save");assertInputEmpty()
        compose.onNodeWithTag("model-secret-input").assertIsNotEnabled()
        compose.onNodeWithTag("model-secret-error").performScrollTo().assertTextEquals("写入结果尚未确认，输入已清空；重试只核对同一次密钥操作。")
        var pending:ModelConnectionSecretMutation?=null
        compose.runOnIdle { pending=h.controller.visible().secretEditor!!.pending;assertNotNull(pending)
            h.controller.close(h.controller.visible().session,retainEditor=true);generation.value++ }
        assertInputEmpty()
        compose.runOnIdle { assertSame(pending,h.controller.visible().secretEditor!!.pending);assertEquals(1,h.vault.prepares) }
        click("model-secret-retry")
        compose.runOnIdle { assertSame(h.vault.applied[0],h.vault.applied[1]);assertEquals(2L,h.catalog.value.version);assertEquals(1,h.vault.prepares) }
        open();input(SECOND)
        compose.runOnIdle { h.catalog.externalEdit() }
        click("model-secret-save");assertInputEmpty()
        compose.onNodeWithTag("model-secret-save").assertIsNotEnabled();compose.onNodeWithTag("model-secret-retry").assertDoesNotExist()
        compose.runOnIdle { assertTrue(h.controller.visible().secretEditor!!.conflict);assertEquals(3L,h.catalog.value.version);assertNull(h.catalog.value.secretRef) }
        click("model-secret-close");open();assertInputEmpty()
        compose.runOnIdle { assertEquals(3L,h.controller.visible().secretEditor!!.expected.version);assertEquals(0,h.vault.resolveCalls) }
    }
    private fun show(h:Harness,actions:MutableMap<String,()->Unit>,generation:MutableState<Int> = mutableStateOf(0),
        restoration:StateRestorationTester?=null,registry:(SaveableStateRegistry?)->Unit={}) {
        val screen:@Composable ()->Unit={
            registry(LocalSaveableStateRegistry.current)
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f),
                LocalModelConfigurationActionObserver provides {name,action->actions[name]=action}) {
                key(generation.value) { AIyouTheme { AIyouApp(local=h.local,modelConnections=h.controller,previewSettings=true) } }
            }
        }
        if(restoration==null)compose.setContent(screen) else restoration.setContent(screen)
    }
    private fun click(tag:String){compose.onNodeWithTag(tag).performScrollTo().performClick()}
    private fun open(){click("model-secret-open-synthetic-config")}
    private fun input(value:String){compose.onNodeWithTag("model-secret-input").performScrollTo().performTextInput(value)}
    private fun assertInputEmpty(){assertTrue("Password input must be empty",
        compose.onNodeWithTag("model-secret-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty())}
    private fun assertNoSavedMaterial(registry:SaveableStateRegistry) {
        val saved=registry.performSave();val parcel=Parcel.obtain()
        try {
            // Inspect the actual Android-saveable map's serialized bytes, including nested containers.
            parcel.writeMap(saved);val bytes=parcel.marshall()
            try { for(value in listOf(MATERIAL,SECOND)) {
                assertFalse("Saved state must not contain input text",saved.toString().contains(value))
                for(encoding in listOf(Charsets.UTF_8,Charsets.UTF_16LE,Charsets.UTF_16BE)) {
                    val needle=value.toByteArray(encoding)
                    assertFalse("Saved state must not contain encoded input",(0..bytes.size-needle.size).any { start ->
                        needle.indices.all { bytes[start+it]==needle[it] } })
                }
            } } finally { bytes.fill(0) }
        } finally { parcel.recycle() }
    }
    private class Catalog:ModelConnectionCatalog {
        var value=ModelConnectionConfiguration("synthetic-config",1,ModelConnectionDetails("合成本机服务","https://example.test/custom/v1/",
            ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION,ModelConfigurationPurpose.ORGANIZATION)))
        override fun configurations()=listOf(value)
        override fun change(request:ModelConnectionChange):ModelConnectionConfiguration? {
            if(request.id!=value.id||request.expectedVersion!=value.version)throw ModelConnectionConflict()
            return checkNotNull(request.details).let { ModelConnectionConfiguration(value.id,value.version+1,it).also { value=it } }
        }
        fun externalEdit(){value=value.copy(version=value.version+1,secretRef=null)}
    }
    /** Never stores any input characters; actual encryption is separately tested on host SQL. */
    private class SyntheticVault(val catalog:Catalog):ConnectionSecretVault {
        private class Prepared(override val expected:ModelConnectionConfiguration,override val reference:SecretReference):PreparedConnectionSecret {
            override fun toString()="SyntheticPrepared(redacted)"
        }
        var prepares=0;var resolveCalls=0;var loseNextAcknowledgement=false
        val applied=mutableListOf<PreparedConnectionSecret>();private var last:Any?=null;private var result:ModelConnectionConfiguration?=null
        override fun prepare(expected:ModelConnectionConfiguration,mutationId:String,ownedCharacters:CharArray):PreparedConnectionSecret=try {
            prepares++;if(expected!=catalog.value)throw ModelConnectionConflict()
            check(ownedCharacters.isNotEmpty());Prepared(expected,SecretReference("vault-v1:"+UUID.randomUUID()))
        } finally { ownedCharacters.fill('\u0000') }
        override fun apply(prepared:PreparedConnectionSecret):ModelConnectionConfiguration {
            applied+=prepared;val p=prepared as Prepared;return mutate(p.expected,p,p.reference)
        }
        override fun revoke(expected:ModelConnectionConfiguration,mutationId:String)=mutate(expected,expected to mutationId,null)
        private fun mutate(expected:ModelConnectionConfiguration,operation:Any,reference:SecretReference?):ModelConnectionConfiguration {
            if(last==operation) { if(catalog.value!=result)throw ModelConnectionConflict();return checkNotNull(result) }
            if(catalog.value!=expected)throw ModelConnectionConflict()
            val next=expected.copy(version=expected.version+1,secretRef=reference);catalog.value=next;result=next;last=operation
            if(loseNextAcknowledgement) { loseNextAcknowledgement=false;throw IOException("Synthetic committed ACK loss") }
            return next
        }
        override fun current(reference:SecretReference)=catalog.value.secretRef==reference
        override fun resolve(reference:SecretReference):OwnedConnectionSecret? { resolveCalls++;error("Settings must never resolve stored material") }
        override fun watch(reference:SecretReference,onRetired:()->Unit):AutoCloseable=error("No sending from settings")
    }
    private class Harness {
        val catalog=Catalog();var vault=SyntheticVault(catalog);private set
        var local by mutableStateOf(LocalRecordUiState(ready=true,opening=false,text="合成当前草稿",modelConnectionOwner=catalog,modelSecretOwner=vault))
        private val noText=object:SourceBoundTextRepository {
            override fun queue(intent:RemoteSendIntent,promptVersion:String,clock:()->Instant):SourceQueueOutcome=error("No model access from settings")
            override fun check(job:SourceBoundTextJob,clock:()->Instant):SendDenial?=error("No model access")
            override fun subscribe(job:SourceBoundTextJob,clock:()->Instant,onInvalidated:(SendDenial)->Unit):SourceBoundTextSubscription=error("No model access")
            override fun admit(job:SourceBoundTextJob,clock:()->Instant):SendDenial?=error("No model access")
            override fun commit(job:SourceBoundTextJob,candidate:DerivedTextCandidate,clock:()->Instant):SourceCommitOutcome=error("No model access")
            override fun cancel(job:SourceBoundTextJob):Unit=error("No model access")
        }
        private val store=object:EntryRepository {
            override val modelConnections:ModelConnectionCatalog=catalog
            override val localModelConnections get()=LocalModelConnectionServices(vault,noText)
            override suspend fun readDraft():TextDraft?=null
            override suspend fun updateDraft(text:String)=TextDraft("synthetic",1,text)
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No record save")
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No record edit")
        }
        val controller=ModelConnectionsController({local},{action->runBlocking {action(store)};true})
        fun replaceVault(){vault=SyntheticVault(catalog);local=local.copy(modelSecretOwner=vault)}
    }
    companion object {
        private const val MATERIAL="SYNTHETIC_ONLY_UI_MATERIAL_789"
        private const val SECOND="SYNTHETIC_ONLY_UI_REPLACEMENT_789"
    }
}
