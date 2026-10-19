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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production settings and controller with synthetic probe ports; host UI, no device/network claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class ModelCapabilitiesPresentationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun explicitFixedSampleReviewShowsExactVersionAndLimitedUsesWithoutAutomaticCalls() {
        val h=Harness();show(h)
        compose.runOnIdle {assertEquals(0,h.calls)}
        click("model-capability-open-synthetic-config")
        compose.onNodeWithText("确认检查固定文本").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle {assertEquals(0,h.calls);assertTrue(h.profiles.values.isEmpty())}
        click("model-capability-run")
        compose.onNodeWithTag("model-capability-state-synthetic-config").performScrollTo().assertTextEquals("文本流：SUPPORTED · 固定样本文本流通过")
        compose.onNodeWithTag("model-capability-reason-synthetic-config").performScrollTo().assertTextEquals("固定样本文本流通过，未验证其他能力。")
        compose.onNodeWithText("检查时间：2026-10-19T10:00:00Z · 连接版本 2").performScrollTo().assertExists()
        compose.runOnIdle {
            assertEquals(1,h.calls);assertEquals(h.config,h.profiles.values.single().connection)
            h.controller.reload(h.controller.visible().session)
            assertEquals(1,h.calls);assertEquals("合成当前草稿",h.local.text);assertEquals(ProcessingPolicy.LOCAL_ONLY,ProcessingDefaults.policy)
        }
    }
    @Test fun unknownFailureAndConfigurationChangesNeverDisplayStaleSupportOrInvokeRetiredReview() {
        val h=Harness();val actions=mutableMapOf<String,()->Unit>();show(h,actions)
        compose.runOnIdle {h.result=TextCapabilityObservation(CapabilityState.UNKNOWN,CapabilityProbeReason.AUTH_REQUIRED,NOW.toEpochMilli())}
        click("model-capability-open-synthetic-config");click("model-capability-run")
        compose.onNodeWithTag("model-capability-state-synthetic-config").performScrollTo().assertTextEquals("文本流：UNKNOWN · 未验证")
        compose.onNodeWithTag("model-capability-reason-synthetic-config").performScrollTo().assertTextEquals("认证未通过，请核对本机密钥与服务权限；能力仍未知。")
        click("model-capability-open-synthetic-config")
        lateinit var retired:()->Unit
        compose.runOnIdle {retired=actions.getValue("capability-run")}
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle {retired();assertEquals(1,h.calls)}
        compose.onNodeWithText("设置").performClick()
        compose.runOnIdle {
            h.config=h.config.copy(version=3,secretRef=SecretReference("synthetic-replaced"))
            h.controller.reload(h.controller.visible().session)
        }
        compose.onNodeWithTag("model-capability-reason-synthetic-config").assertDoesNotExist()
        compose.onNodeWithTag("model-capability-state-synthetic-config").performScrollTo().assertTextEquals("文本流：UNKNOWN · 未验证")
        compose.runOnIdle {retired();assertEquals(1,h.calls)}
    }
    @Test fun uncertainArchiveWriteRetriesOnlySameObservationAndProductionGateStaysDisabled() {
        val h=Harness();show(h)
        compose.runOnIdle {h.profiles.loseAck=true}
        click("model-capability-open-synthetic-config");click("model-capability-run")
        compose.onNodeWithTag("model-capability-error").performScrollTo().assertTextEquals("探测结果写入尚未确认；重试仅核对本次结果，不会再次请求服务。")
        compose.onNodeWithTag("model-capability-run").assertIsNotEnabled()
        click("model-capability-retry")
        compose.runOnIdle {assertEquals(1,h.calls);assertEquals(1,h.profiles.finishes);assertEquals(1,h.profiles.confirms)}
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle {h.enabled=false;h.controller.dispose();h.controller=h.newController()}
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("model-capability-open-synthetic-config").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {assertEquals(1,h.calls)}
    }
    private fun show(h:Harness,actions:MutableMap<String,()->Unit> = mutableMapOf()) {
        compose.setContent {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,2f),
                LocalModelConfigurationActionObserver provides {name,action->actions[name]=action}) {
                AIyouTheme {AIyouApp(local=h.local,modelConnections=h.controller,previewSettings=true)}
            }
        }
    }
    private fun click(tag:String) {compose.onNodeWithTag(tag).performScrollTo().performClick()}
    private class Harness {
        var config=ModelConnectionConfiguration("synthetic-config",2,ModelConnectionDetails("合成连接","https://example.test/custom/v1/",
            ConnectionProtocol.CHAT_COMPLETIONS,"synthetic-model",setOf(ModelConfigurationPurpose.CONVERSATION)),SecretReference("synthetic-reference"))
        val catalog=object:ModelConnectionCatalog {
            override fun configurations()=listOf(config)
            override fun change(request:ModelConnectionChange):ModelConnectionConfiguration?=error("No configuration writes from a probe")
        }
        private val vault=object:ConnectionSecretVault {
            override fun prepare(expected:ModelConnectionConfiguration,mutationId:String,ownedCharacters:CharArray):PreparedConnectionSecret=error("No credential writes from a probe")
            override fun apply(prepared:PreparedConnectionSecret):ModelConnectionConfiguration=error("No credential writes")
            override fun revoke(expected:ModelConnectionConfiguration,mutationId:String):ModelConnectionConfiguration=error("No credential writes")
            override fun current(reference:SecretReference)=config.secretRef==reference
            override fun resolve(reference:SecretReference):OwnedConnectionSecret?=error("UI cannot resolve credentials")
            override fun watch(reference:SecretReference,onRetired:()->Unit):AutoCloseable=error("No network in UI fixture")
        }
        val profiles=Profiles({config})
        var local by mutableStateOf(LocalRecordUiState(ready=true,opening=false,text="合成当前草稿",modelConnectionOwner=catalog,
            modelSecretOwner=vault,modelCapabilityOwner=profiles))
        var calls=0;var enabled=true
        var result=TextCapabilityObservation(CapabilityState.SUPPORTED,CapabilityProbeReason.TEXT_STREAM_VERIFIED,NOW.toEpochMilli())
        private val probe=object:ModelCapabilityProbe {
            override fun newExchange(ticket:ModelCapabilityProbeTicket)=object:ModelCapabilityProbeExchange {
                override fun execute():TextCapabilityObservation {calls++;return result}
                override fun cancel(){}
            }
        }
        private val store=object:EntryRepository {
            override val modelConnections=catalog
            override val modelCapabilities=profiles
            override val localModelConnections get()=LocalModelConnectionServices(vault,object:SourceBoundTextRepository {
                override fun queue(intent:RemoteSendIntent,promptVersion:String,clock:()->Instant):SourceQueueOutcome=error("No source consent")
                override fun check(job:SourceBoundTextJob,clock:()->Instant):SendDenial?=error("No source access")
                override fun subscribe(job:SourceBoundTextJob,clock:()->Instant,onInvalidated:(SendDenial)->Unit):SourceBoundTextSubscription=error("No source access")
                override fun admit(job:SourceBoundTextJob,clock:()->Instant):SendDenial?=error("No source access")
                override fun commit(job:SourceBoundTextJob,candidate:DerivedTextCandidate,clock:()->Instant):SourceCommitOutcome=error("No source access")
                override fun cancel(job:SourceBoundTextJob):Unit=error("No source access")
            })
            override suspend fun readDraft():TextDraft?=error("No draft read")
            override suspend fun updateDraft(text:String):TextDraft=error("No draft write")
            override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String):StoredEntry=error("No source write")
            override suspend fun entries()=emptyList<StoredEntry>()
            override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
            override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long):StoredEntry=error("No source write")
        }
        fun newController()=ModelConnectionsController({local},{action->runBlocking {action(store)};true},probe.takeIf {enabled},Clock.fixed(NOW,ZoneOffset.UTC))
        var controller by mutableStateOf(newController())
    }
    private class Profiles(val config:()->ModelConnectionConfiguration):ModelCapabilityRepository {
        var values=emptyList<ModelCapabilityProfile>();var finishes=0;var confirms=0;var loseAck=false
        private var generation=0L
        override fun profiles()=values.filter {it.connection==config()}
        override fun begin(expected:ModelConnectionConfiguration,probeId:String,startedAtMillis:Long):ModelCapabilityProbeTicket {
            if(expected!=config())throw ModelConnectionConflict()
            val p=ModelCapabilityProfile(expected,probeId,++generation,startedAtMillis,
                TextCapabilityObservation(CapabilityState.UNKNOWN,CapabilityProbeReason.IN_PROGRESS,startedAtMillis))
            values=listOf(p);return object:ModelCapabilityProbeTicket {override val profile=p}
        }
        override fun current(ticket:ModelCapabilityProbeTicket)=ticket.profile.connection==config()&&values.singleOrNull()?.probeId==ticket.profile.probeId
        override fun admit(ticket:ModelCapabilityProbeTicket)=current(ticket)
        override fun watch(ticket:ModelCapabilityProbeTicket,onRetired:()->Unit)=AutoCloseable { }
        override fun finish(ticket:ModelCapabilityProbeTicket,observation:TextCapabilityObservation):ModelCapabilityProfile {
            finishes++;if(!current(ticket))throw ModelConnectionConflict()
            return ticket.profile.copy(observation=observation).also {values=listOf(it)
                if(loseAck){loseAck=false;throw IOException("Synthetic archive ACK loss")}}
        }
        override fun confirm(ticket:ModelCapabilityProbeTicket,observation:TextCapabilityObservation):ModelCapabilityProfile? {
            confirms++;if(!current(ticket))throw ModelConnectionConflict()
            return values.singleOrNull()?.takeIf {it.observation==observation}
        }
    }
    companion object {private val NOW=Instant.parse("2026-10-19T10:00:00Z")}
}
