package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production routes/controller/dialog; controlled presentation port. SQLite/JCA has separate regressions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
class SourcePreferencePresentationTest {
    @get:Rule val compose=createComposeRule()
    private val entry=StoredEntry("one",1,"🙂合成原文：我平时更喜欢淡茶。只是普通口味自述。",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val source=SourceVersion(entry.id,1)
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,entries=listOf(entry),supportsSourcePreferences=true))
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
    private var saved:SourcePreferenceSnapshot?=null
    private var creates=0;private var writes=0
    private var failRead=false
    private var registry:SaveableStateRegistry?=null
    private val port=object:SourcePreferenceRepository {
        override fun preview(source:SourceVersion):SourceMemoryPreview {check(!failRead && source==this@SourcePreferencePresentationTest.source);return SourceMemoryPreview(source,OriginalExports.hash(entry.originalText),0,entry.originalText,55,"UTC",ProcessingPolicy.LOCAL_ONLY,1)}
        override fun preferences(source:SourceVersion)=listOfNotNull(saved)
        override fun captureSource(source:SourceVersion):PreferenceSourcePage {
            val preview=preview(source);val values=listOfNotNull(saved)
            return object:PreferenceSourcePage {override val preview=preview;override val items=values;override val topics=values}
        }
        override fun resolveSource(page:PreferenceSourcePage,publish:()->Unit):Boolean {
            if(failRead || page.items.map {it.rowVersion}!=listOfNotNull(saved).map {it.rowVersion})return false
            publish();return true
        }
        override fun acceptedForTopic(expected:SourcePreferenceSnapshot):SourcePreferenceSnapshot? {check(saved!!.same(expected));return saved?.takeIf {it.review!!.review==PreferenceReview.ACCEPTED}}
        override fun resolveEndpoint(expected:SourcePreferenceSnapshot,endpoint:PreferenceEndpoint,publish:(SourcePreferenceSnapshot?)->Unit)=error("No cross-row endpoints in this fixture")
        override fun create(request:PreferenceCreate):SourcePreferenceSnapshot {
            check(saved==null);PreferenceRules.validate(request.content,preview(request.preview.source));creates++;writes++
            return SourcePreferenceSnapshot(request.id,source,request.preview.sourceHash,0,1,PreferenceAvailability.AVAILABLE,request.requestedAtMillis,55,"UTC",ProcessingPolicy.LOCAL_ONLY,request.topicId,request.topicLabel,
                listOf(PreferenceContentVersion(1,request.content,request.requestedAtMillis)),listOf(PreferenceCheckpoint(1,PreferenceReview.PROPOSED,null,request.requestedAtMillis))).also {saved=it}
        }
        override fun mutate(request:PreferenceMutation):SourcePreferenceSnapshot {
            val old=checkNotNull(saved);check(old.same(request.expected) && request.replacing==null);val latest=old.review!!
            val next=PreferenceRules.decision(latest.review,request.action);writes++
            val contents=if(request.action==PreferenceAction.CORRECT) old.contents+PreferenceContentVersion(old.current!!.revision+1,request.content!!,request.requestedAtMillis) else old.contents
            val reviews=if(request.action==PreferenceAction.CORRECT) old.reviews.dropLast(1)+latest.copy(review=PreferenceReview.SUPERSEDED,priorReview=latest.review)+PreferenceCheckpoint(contents.size,PreferenceReview.PROPOSED,null,request.requestedAtMillis)
                else old.reviews.dropLast(1)+latest.copy(review=next,reviewedAtMillis=request.requestedAtMillis)
            return SourcePreferenceSnapshot(old.id,source,old.sourceHash,0,old.rowVersion+1,old.availability,old.createdAtMillis,55,"UTC",ProcessingPolicy.LOCAL_ONLY,old.topicId,old.topicLabel,contents,reviews).also {saved=it}
        }
        override fun capture(section:PreferenceReview,cursor:PreferenceCursor?):PreferencePage {
            check(!failRead);val values=listOfNotNull(saved).filter {it.review!!.review==section}
            return object:PreferencePage {override val section=section;override val cursor=cursor;override val next:PreferenceCursor?=null;override val items=values}
        }
        override fun resolve(page:PreferencePage,item:SourcePreferenceSnapshot?,publish:()->Unit):Boolean {if(failRead)return false;publish();return true}
    }
    private val owner=object:EntryRepository {
        override val sourcePreferences=port
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("No draft operation")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("No draft operation")
        override suspend fun entries()=state.value.entries
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("No source write")
    }
    private val controller=SourcePreferenceController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC))
    @After fun dispose()=controller.dispose()
    @Composable private fun Screen() {
        val density=LocalDensity.current;val currentRegistry=LocalSaveableStateRegistry.current;SideEffect {registry=currentRegistry}
        CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {AIyouTheme {Box(Modifier.size(320.dp,640.dp)) {
            AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},sourcePreferences=controller)
        }}}
    }
    private fun click(tag:String) {compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()}
    private fun drain() {compose.runOnIdle {runBlocking {while(true)(queue.poll()?:break)(owner)}};compose.waitForIdle()}
    private fun reader() {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"));compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick()}
    private fun open() {click("original-source-preferences");drain()}
    private fun form() {
        compose.onNodeWithTag("preference-quote").performScrollTo().performTextReplacement("我平时更喜欢淡茶")
        compose.onNodeWithTag("preference-topic-label").performScrollTo().performTextReplacement("合成口味")
        click("preference-affirm")
    }
    private fun confirm() {click("preference-confirm");drain()}
    private fun accept() {click("preference-accept:"+saved!!.id);drain();confirm()}
    @Test fun doubleFontPendingSeparateAcceptanceCorrectionSourceJumpAndRevokeCancellationReachProductionRoutes() {
        compose.setContent {Screen()};reader();open()
        for (tag in listOf("preference-save","preference-close")) { val node=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed();assertTrue(node.getUnclippedBoundsInRoot().height>=48.dp) }
        form();click("preference-save")
        click("preference-cancel");assertEquals(0,writes)
        click("preference-save");confirm();assertEquals(1,creates);assertEquals(PreferenceReview.PROPOSED,saved!!.review!!.review)
        compose.onNodeWithTag("preference-state:"+saved!!.id).performScrollTo().assertTextEquals("待确认自述")
        accept();assertEquals(PreferenceReview.ACCEPTED,saved!!.review!!.review)
        click("preference-correct:"+saved!!.id)
        compose.onNodeWithTag("preference-from").performScrollTo().performTextReplacement("2026-10-21")
        compose.onNodeWithTag("preference-zone").performScrollTo().performTextReplacement("UTC")
        click("preference-affirm");click("preference-save");confirm()
        assertEquals(PreferenceReview.PROPOSED,saved!!.review!!.review);assertEquals(PreferenceReview.ACCEPTED,saved!!.reviews.first().priorReview)
        accept();click("preference-source:"+saved!!.id);drain()
        val text=compose.onNodeWithTag("original-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertEquals(entry.originalText,text.text);assertTrue(text.spanStyles.isNotEmpty());assertNull(state.value.sourcePreferences)
        open();val version=saved!!.rowVersion;click("preference-revoke:"+saved!!.id);drain();click("preference-cancel");assertEquals(version,saved!!.rowVersion)
        click("preference-revoke:"+saved!!.id);drain();confirm();assertEquals(PreferenceReview.REVOKED,saved!!.review!!.review)
        click("preference-close");compose.onNodeWithText("返回列表").assertIsDisplayed().performClick();compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("preferences-open"));compose.onNodeWithTag("preferences-open").performClick();drain()
        click("preference-section-ACCEPTED");drain();assertTrue(state.value.sourcePreferences!!.items.isEmpty())
        assertEquals(listOf(entry),state.value.entries);assertTrue(state.value.tasks.items.isEmpty())
    }
    @Test fun failedGlobalReadHidesCountAndNeverPresentsAnUnverifiedEmptyPage() {
        compose.setContent {Screen()}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("preferences-open"));compose.onNodeWithTag("preferences-open").performClick();drain()
        compose.onNodeWithTag("preferences-count").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {failRead=true}
        compose.onNodeWithText("仅刷新实际状态，不重复原请求").performScrollTo().performClick();drain()
        compose.onNodeWithTag("preferences-error").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("preferences-count").assertDoesNotExist()
        assertEquals(0,writes);assertNull(state.value.sourcePreferences!!.notice)
    }
    @Test fun privateFormRestorationAndClosedConfirmationHaveNoRetainedWriteAuthority() {
        val restoration=StateRestorationTester(compose);restoration.setContent {Screen()};reader();open();form();click("preference-save")
        val old=state.value.sourcePreferences!!;val confirmation=old.confirmation!!
        compose.runOnIdle {val raw=checkNotNull(registry).performSave().toString();assertFalse(raw.contains("合成口味"));assertFalse(raw.contains("我平时更喜欢淡茶"))}
        restoration.emulateSavedInstanceStateRestore();compose.waitForIdle()
        compose.runOnIdle {controller.confirm(old.session,confirmation)};drain();assertEquals(0,writes);assertNull(state.value.sourcePreferences)
    }
}
