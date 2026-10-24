package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production detail route/dialog/controller with explicit synthetic authority ports.
 * Production SQL/version/ref/readonly-recovery contracts are exercised separately on host SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class RecordConnectionReviewPresentationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun narrowDoubleFontDirectoryChoiceShowsDestinationVersionReferenceBoundaryAndSeparateConfirmation() {
        val h=Harness();show(h);open()
        click("record-connection-review-config-ORGANIZATION");drain(h)
        compose.onNodeWithTag("record-connection-source-version").performScrollTo().assertTextEquals("原文版本 1 · 授权代次 0")
        compose.onNodeWithTag("record-connection-destination").performScrollTo().assertTextEquals("HTTPS API根路径：https://example.test:8443/custom/v1/")
        compose.onNodeWithText("模型：synthetic-model").performScrollTo().assertExists()
        compose.onNodeWithTag("record-connection-secret-status").performScrollTo().assertTextEquals("本机密钥引用已核验；不展示密钥内容，当前不会发送。")
        compose.onNodeWithTag("record-connection-capability-boundary").performScrollTo().assertTextEquals("固定样本文本流：已通过；不代表问答或整理语义质量。")
        compose.onNodeWithTag("record-connection-interval").performScrollTo().assertTextEquals("有效区间（一小时）：2026-10-19T13:00:00Z 至 2026-10-19T14:00:00Z")
        val confirm=compose.onNodeWithTag("record-connection-confirm").performScrollTo().assertIsDisplayed()
        assertTrue(confirm.getUnclippedBoundsInRoot().height>=48.dp);assertEquals(0,h.changes)
        click("record-connection-confirm");drain(h)
        compose.onNodeWithTag("local-processing-notice").performScrollTo().assertTextEquals("已保存这条记录的连接和单一用途授权；未发送记录，也未创建请求。未改变新草稿的默认处理范围。")
        assertEquals(1,h.changes);assertEquals(TextPurpose.ORGANIZATION,h.actual.consent!!.purpose)
        assertEquals(1L,h.actual.binding.generation);h.noSending()
    }
    @Test fun cancelledRetainedReviewAndChangedConfigurationCannotGrantAnOldConnection() {
        val h=Harness();show(h);open();click("record-connection-review-config-CONVERSATION");drain(h)
        val old=h.actions.get();click("record-connection-cancel")
        click("record-connection-review-config-ORGANIZATION");drain(h)
        compose.runOnIdle {old.confirm();old.cancel();assertTrue(h.queue.isEmpty())};assertEquals(0,h.changes)
        val retained=h.actions.get()
        compose.runOnIdle {h.config=h.config.copy(version=3,secretRef=SecretReference("synthetic-new-reference"));retained.confirm()}
        drain(h)
        compose.onNodeWithTag("local-processing-error").performScrollTo().assertTextEquals("记录或处理范围已变化。请重新读取后确认，旧操作不会覆盖新的选择。")
        assertNull(h.actual.consent);assertEquals(ProcessingPolicy.LOCAL_ONLY,h.actual.policy);h.noSending()
    }
    @Test fun uncertainDecisionRetryOnlyReadsReceiptAndNeverRefreshesIntervalOrSends() {
        val h=Harness();h.loseAck=true;show(h);open();click("record-connection-review-config-CONVERSATION");drain(h)
        click("record-connection-confirm");drain(h)
        val granted=h.actual.consent!!;assertEquals(1,h.changes)
        compose.onNodeWithTag("local-processing-error").performScrollTo().assertTextEquals("处理范围读取或保存状态待确认。重新核对授权仅重读回执；关闭后可重新打开查看实际范围。")
        click("local-processing-retry");drain(h)
        assertEquals(1,h.changes);assertEquals(1,h.confirmations);assertEquals(granted,h.actual.consent)
        compose.onNodeWithTag("local-processing-notice").performScrollTo().assertTextEquals("已保存这条记录的连接和单一用途授权；未发送记录，也未创建请求。未改变新草稿的默认处理范围。");h.noSending()
    }
    private fun click(tag:String){ compose.showOriginalToolsFor(tag);compose.onNodeWithTag(tag).performScrollTo().performClick()}
    private fun drain(h:Harness){compose.runOnIdle {runBlocking {while(true)(h.queue.poll()?:break)(h.store)}};compose.waitForIdle()}
    private fun open(){compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"));compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick();click("original-local-processing");drain(active)}
    private lateinit var active:Harness
    private fun show(h:Harness){active=h;compose.setContent {
        val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,2f),
            LocalProcessingDialogObservation provides LocalProcessingDialogObserver {h.actions.set(it)}) {
            AIyouTheme {Box(Modifier.size(320.dp,640.dp)) {
                AIyouApp(previewDestination=Destination.MEMORIES,local=h.state.value,currentRecords={h.state.value},localProcessing=h.controller)
            }}
        }
    }}
    private class Harness {
        val entry=StoredEntry("one",1,"🙂合成单条原文",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
        var config=ModelConnectionConfiguration("config",2,ModelConnectionDetails("合成目录服务","https://example.test:8443/custom/v1/",
            ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION,ModelConfigurationPurpose.ORGANIZATION)),SecretReference("synthetic-reference"))
        var actual=RecordAuthorizationSnapshot(RecordAuthorizationSource(SourceVersion("one",1),OriginalExports.hash(entry.originalText),0),ProcessingPolicy.LOCAL_ONLY,null,null)
        var changes=0;var confirmations=0;var resolves=0;var loseAck=false
        private var committed:RecordAuthorizationChange?=null
        val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>();val actions=AtomicReference<LocalProcessingDialogActions>()
        val catalog=object:ModelConnectionCatalog {
            override fun configurations()=listOf(config)
            override fun change(request:ModelConnectionChange):ModelConnectionConfiguration?=error("Not an editor")
        }
        val vault=object:ConnectionSecretVault {
            override fun prepare(expected:ModelConnectionConfiguration,mutationId:String,ownedCharacters:CharArray):PreparedConnectionSecret=error("No key input")
            override fun apply(prepared:PreparedConnectionSecret):ModelConnectionConfiguration=error("No key write")
            override fun revoke(expected:ModelConnectionConfiguration,mutationId:String):ModelConnectionConfiguration=error("No key write")
            override fun current(reference:SecretReference)=reference==config.secretRef
            override fun resolve(reference:SecretReference):OwnedConnectionSecret? {resolves++;error("Never resolve from consent")}
            override fun watch(reference:SecretReference,onRetired:()->Unit)=AutoCloseable { }
        }
        val port=object:RecordAuthorizationRepository {
            override val supportsConnectionReview=true
            override fun readAuthorization(source:SourceVersion)=actual
            override fun previewAuthorization(expected:RecordAuthorizationSource,purpose:TextPurpose,clock:()->Instant)=RecordAuthorizationPreview(actual,purpose,actual.consent?.purpose==purpose&&actual.connection==config.textConnection(purpose))
            override fun previewConnectionAuthorization(expected:RecordAuthorizationSource,configuration:ModelConnectionConfiguration,purpose:TextPurpose,clock:()->Instant):RecordAuthorizationPreview {
                if(expected!=actual.binding||configuration!=config)throw RecordAuthorizationConflict()
                return RecordAuthorizationPreview(actual,purpose,false,config,ModelCapabilityProfile(config,"synthetic-check",1,100,
                    TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,101)))
            }
            override fun changeAuthorization(request:RecordAuthorizationChange,clock:()->Instant):RecordAuthorizationReceipt {
                if(request.expected!=actual||request.selectedConfiguration!=config)throw RecordAuthorizationConflict()
                changes++;committed=request;actual=RecordAuthorizationSnapshot(actual.binding.copy(generation=request.committedGeneration),request.targetPolicy,request.resultingConnection,request.grant)
                if(loseAck){loseAck=false;throw IOException("Synthetic committed grant ACK lost")}
                return RecordAuthorizationReceipt(actual,request.committedGeneration,false,true)
            }
            override fun confirmAuthorization(request:RecordAuthorizationChange,clock:()->Instant):RecordAuthorizationReceipt? {
                confirmations++;if(committed!==request)return null
                return RecordAuthorizationReceipt(actual,request.committedGeneration,true,request.grant==actual.consent)
            }
        }
        val local=object:LocalProcessingRepository {
            override fun read(source:SourceVersion)=LocalProcessingSnapshot(actual.binding.source,actual.binding.sourceHash,actual.binding.generation,actual.policy)
            override fun versions()=mapOf("one" to LocalProcessingVersion(actual.binding.source,actual.binding.generation))
            override fun change(request:LocalProcessingChange):LocalProcessingSnapshot=error("Not this scope")
        }
        val store=object:EntryRepository {
            override val modelConnections=catalog
            override val recordAuthorizations=port
            override val localProcessing=local
            override val localModelConnections get()=LocalModelConnectionServices(vault,object:SourceBoundTextRepository {
                override fun queue(intent:RemoteSendIntent,promptVersion:String,clock:()->Instant):SourceQueueOutcome=error("No send")
                override fun check(job:SourceBoundTextJob,clock:()->Instant):SendDenial?=error("No send")
                override fun subscribe(job:SourceBoundTextJob,clock:()->Instant,onInvalidated:(SendDenial)->Unit):SourceBoundTextSubscription=error("No send")
                override fun admit(job:SourceBoundTextJob,clock:()->Instant):SendDenial?=error("No send")
                override fun commit(job:SourceBoundTextJob,candidate:DerivedTextCandidate,clock:()->Instant):SourceCommitOutcome=error("No send")
                override fun cancel(job:SourceBoundTextJob):Unit=error("No send")
            })
            override suspend fun readDraft():TextDraft?=error("No draft")
            override suspend fun updateDraft(text:String):TextDraft=error("No draft")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No save")
            override suspend fun entries()=listOf(StoredEntry(entry.id,entry.currentRevision,entry.originalText,entry.capturedAtMillis,entry.captureTimeZone,actual.policy))
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No revision")
        }
        val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(entry),supportsLocalProcessing=true,
            localProcessingVersions=local.versions(),recordAuthorizationOwner=port,modelConnectionOwner=catalog,modelSecretOwner=vault))
        val controller=LocalProcessingController({state.value},{state.value=it(state.value)},{queue.add(it);true},{},Clock.fixed(NOW,ZoneOffset.UTC))
        fun noSending(){assertEquals(0,resolves);assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy);assertEquals(entry.originalText,state.value.entries.single().originalText)}
    }
    companion object {private val NOW=Instant.parse("2026-10-19T13:00:00Z")}
}
