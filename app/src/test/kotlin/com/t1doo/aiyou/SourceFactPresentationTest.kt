package com.t1doo.aiyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.GraphicsMode
import com.t1doo.aiyou.designsystem.AIyouTheme
import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import com.t1doo.aiyou.records.*
import com.t1doo.aiyou.ui.Destination
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceFactPresentationTest {
    @get:Rule val compose=createComposeRule()
    private val entry=StoredEntry("one",1,"🙂合成原文：我的合成杯子是绿色。只是普通物品自述。",55,"UTC",ProcessingPolicy.LOCAL_ONLY)
    private val source=SourceVersion(entry.id,1)
    private val state=mutableStateOf(LocalRecordUiState(ready=true,opening=false,text="合成未提交草稿",entries=listOf(entry),supportsSourceFacts=true))
    private val queue=ConcurrentLinkedQueue<suspend(EntryRepository)->Unit>()
    private var saved:SourceFactSnapshot?=null
    private var creates=0;private var writes=0
    private var failRead=false;private var loseAck=false
    private var retiredQueries=0
    private var registry:SaveableStateRegistry?=null
    private val formMeasure=AtomicReference<Pair<Long,FactLabelMeasureTrace>>()
    private val measureObserver:(Long,FactLabelMeasureTrace)->Unit={session,trace->formMeasure.set(session to trace)}
    private val port=object:SourceFactRepository {
        override fun preview(source:SourceVersion,checkCurrent:()->Unit):SourceMemoryPreview {check(!failRead && source==this@SourceFactPresentationTest.source);return SourceMemoryPreview(source,OriginalExports.hash(entry.originalText),0,entry.originalText,55,"UTC",ProcessingPolicy.LOCAL_ONLY,1)}
        override fun facts(source:SourceVersion,checkCurrent:()->Unit)=listOfNotNull(saved)
        override fun captureSource(source:SourceVersion,checkCurrent:()->Unit):FactSourcePage {
            val preview=preview(source);val values=listOfNotNull(saved)
            return object:FactSourcePage {override val preview=preview;override val items=values;override val topics=values}
        }
        override fun resolveSource(page:FactSourcePage,checkCurrent:()->Unit,publish:()->Unit):Boolean {
            checkCurrent();if(failRead || page.items.map {it.rowVersion}!=listOfNotNull(saved).map {it.rowVersion})return false
            publish();return true
        }
        override fun acceptedForTopic(expected:SourceFactSnapshot,checkCurrent:()->Unit):SourceFactSnapshot? {check(saved!!.same(expected));return saved?.takeIf {it.review!!.review==FactReview.ACCEPTED}}
        override fun resolveEndpoint(expected:SourceFactSnapshot,endpoint:FactEndpoint,checkCurrent:()->Unit,publish:(SourceFactSnapshot?)->Unit)=error("No cross-row endpoints in this fixture")
        override fun create(request:FactCreate,checkCurrent:()->Unit):SourceFactSnapshot {
            checkCurrent()
            saved?.let { check(it.id==request.id && it.current!!.content==request.content);return it }
            FactRules.validate(request.content,preview(request.preview.source));creates++;writes++
            return SourceFactSnapshot(request.id,source,request.preview.sourceHash,0,1,FactAvailability.AVAILABLE,request.requestedAtMillis,55,"UTC",ProcessingPolicy.LOCAL_ONLY,request.topicId,request.topicLabel,
                listOf(FactContentVersion(1,request.content,request.requestedAtMillis)),listOf(FactCheckpoint(1,FactReview.PROPOSED,null,request.requestedAtMillis))).also {saved=it;if(loseAck){loseAck=false;throw FactUnavailable()}}
        }
        override fun mutate(request:FactMutation,checkCurrent:()->Unit):SourceFactSnapshot {
            checkCurrent();val old=checkNotNull(saved);check(old.same(request.expected) && request.replacing==null);val latest=old.review!!
            val next=FactRules.decision(latest.review,request.action);writes++
            val contents=if(request.action==FactAction.CORRECT) old.contents+FactContentVersion(old.current!!.revision+1,request.content!!,request.requestedAtMillis) else old.contents
            val reviews=if(request.action==FactAction.CORRECT) old.reviews.dropLast(1)+latest.copy(review=FactReview.SUPERSEDED,priorReview=latest.review)+FactCheckpoint(contents.size,FactReview.PROPOSED,null,request.requestedAtMillis)
                else old.reviews.dropLast(1)+latest.copy(review=next,reviewedAtMillis=request.requestedAtMillis)
            return SourceFactSnapshot(old.id,source,old.sourceHash,0,old.rowVersion+1,old.availability,old.createdAtMillis,55,"UTC",ProcessingPolicy.LOCAL_ONLY,old.topicId,old.topicLabel,contents,reviews).also {saved=it}
        }
        override fun capture(section:FactReview,cursor:FactCursor?,checkCurrent:()->Unit):FactPage {
            check(!failRead);val values=listOfNotNull(saved).filter {it.review!!.review==section}
            return object:FactPage {override val section=section;override val cursor=cursor;override val next:FactCursor?=null;override val items=values}
        }
        override fun searchAccepted(keyword:String,cursor:FactCursor?,checkCurrent:()->Unit):FactPage {
            check(!failRead);FactKeywords.validate(keyword)
            val values=listOfNotNull(saved).filter {it.review!!.review==FactReview.ACCEPTED && (it.topicLabel!!.contains(keyword) || it.current!!.content.reference.quote.contains(keyword))}
            return object:FactPage {override val section=FactReview.ACCEPTED;override val keyword=keyword;override val cursor=cursor;override val next:FactCursor?=null;override val items=values}
        }
        override fun resolve(page:FactPage,item:SourceFactSnapshot?,checkCurrent:()->Unit,publish:()->Unit):Boolean {if(failRead)return false;publish();return true}
    }
    private val owner=object:EntryRepository {
        override val sourceFacts=port
        override val unifiedKeywords get()=queryPort
        override suspend fun readDraft()=null
        override suspend fun updateDraft(text:String)=error("No draft operation")
        override suspend fun saveDraft(expected:TextDraft,capturedAtMillis:Long,captureTimeZone:String)=error("No draft operation")
        override suspend fun entries()=state.value.entries
        override suspend fun revisions(entryId:String)=emptyList<OriginalRevision>()
        override suspend fun revise(entryId:String,expectedRevision:Int,text:String,createdAtMillis:Long)=error("No source write")
    }
    private class QueryProof(override val keyword:String,override val scope:UnifiedKeywordScope,override val groups:List<UnifiedKeywordGroup>,override val bindings:List<UnifiedKeywordBinding>,val row:SourceFactSnapshot?):UnifiedKeywordSnapshot
    private val queryPort=object:UnifiedKeywordRepository {
        override suspend fun search(keyword:String,scope:UnifiedKeywordScope):UnifiedKeywordSnapshot {
            val b=UnifiedKeywordBinding(source,OriginalExports.hash(entry.originalText),0,55,"UTC",ProcessingPolicy.LOCAL_ONLY)
            val row=saved?.takeIf{it.review!!.review==FactReview.ACCEPTED && (it.topicLabel!!.contains(keyword)||it.current!!.content.reference.quote.contains(keyword))}
            val values=if(scope==UnifiedKeywordScope.ALL||scope==UnifiedKeywordScope.FACT)listOfNotNull(row?.let{UnifiedKeywordEvidence.Fact(b,it)})else emptyList()
            return QueryProof(keyword,scope,if(values.isEmpty())emptyList()else listOf(UnifiedKeywordGroup(b,values)),listOf(b),saved)
        }
        override suspend fun resolve(snapshot:UnifiedKeywordSnapshot,item:UnifiedKeywordEvidence?,admit:()->Unit):Boolean {
            val p=snapshot as? QueryProof?:return false
            if(p.row?.same(saved?:return false)!=true || item!=null && p.groups.flatMap{it.items}.none{it===item})return false
            admit();return true
        }
    }
    private val unified=UnifiedKeywordController({state.value},{state.value=it(state.value)},{queue.add(it);true})
    init {state.value=state.value.copy(sourceFactOwner=port,unifiedKeywordOwner=queryPort,
        supportsLocalProcessing=true,localProcessingVersions=mapOf(entry.id to LocalProcessingVersion(source,0)))}
    private val controller=SourceFactController({state.value},{state.value=it(state.value)},{queue.add(it);true},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC),beforeOpen={unified.retire()},beforeWrite={retiredQueries++;unified.retire()})
    @After fun dispose(){controller.dispose();unified.dispose()}
    @Composable private fun Screen() {
        val currentRegistry=LocalSaveableStateRegistry.current;SideEffect {registry=currentRegistry}
        AIyouTheme {CompositionLocalProvider(LocalFactFormMeasureObserver provides measureObserver) {
            AIyouApp(previewDestination=Destination.MEMORIES,local=state.value,currentRecords={state.value},sourceFacts=controller,unifiedKeywords=unified)
        }}
    }
    private fun click(tag:String) { compose.showOriginalToolsFor(tag);compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick();compose.waitForIdle()}
    private fun drain() {compose.runOnIdle {runBlocking {while(true)(queue.poll()?:break)(owner)}};compose.waitForIdle()}
    private fun reader() {compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("entry-one"));compose.onNode(hasText("阅读全文") and hasAnyAncestor(hasTestTag("entry-one"))).performClick()}
    private fun open() {click("original-source-facts");drain()}
    private fun form() {
        compose.onNodeWithTag("fact-quote").performScrollTo().performTextReplacement("我的合成杯子是绿色")
        compose.onNodeWithTag("fact-topic-label").performScrollTo().performTextReplacement("合成办公物品")
        click("fact-affirm")
    }
    private fun confirm() {click("fact-confirm");drain()}
    private fun accept() {click("fact-accept:"+saved!!.id);drain();confirm()}
    private fun backToList(){compose.onNodeWithText("返回列表").assertIsDisplayed().performClick();compose.waitForIdle();compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)}
    private fun global(){compose.showNotebookToolsFor("facts-open");compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("facts-open"));compose.onNodeWithTag("facts-open").assertIsDisplayed().performClick();drain()}
    private fun frame(name:String){
        val image=compose.onNodeWithTag("facts-dialog").captureToImage().asAndroidBitmap()
        val file=File("build/reports/tests/testDebugUnitTest/source-facts/$name.png");file.parentFile.mkdirs()
        file.outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    private fun verifyFormLayout(fontScale:Float,frameName:String){
        data class Spec(val role:String,val tag:String,val label:String,val help:String)
        data class Region(val bounds:DpRect,val layout:TextLayoutResult,val expected:String)
        data class Row(val spec:Spec,val field:DpRect,val label:Region,val help:Region,val error:Region?)
        val specs=listOf(
            Spec("QUOTE","fact-quote","原文片段","逐字原文片段（最多320字）"),
            Spec("TOPIC","fact-topic-label","主题/上下文","新建普通事实主题/上下文（最多40字）"),
            Spec("FROM","fact-from","生效起始日","生效起始日（YYYY-MM-DD，未知留空）"),
            Spec("UNTIL","fact-until","结束日","结束日（不含此日，未知留空）"),
            Spec("ZONE","fact-zone","日期时区","日期时区（IANA，如 Asia/Shanghai；全未知留空）"))
        val receipt=JSONObject().put("source_sha",System.getenv("GITHUB_SHA")?:"local-not-CI")
            .put("purpose","measurement observation only; no layout fix")
            .put("fontScale",fontScale).put("units","dp, unclipped bounds in one unchanged dialog viewport")
            .put("anchor",if(fontScale==2f)"fact-quote"else"dialog title").put("status","NOT_COMPLETE")
        val measured=JSONArray();receipt.put("fields",measured)
        fun rect(b:DpRect)=JSONObject().put("left",b.left.value).put("top",b.top.value).put("right",b.right.value).put("bottom",b.bottom.value)
        fun coordinates(c:LayoutCoordinates):JSONObject {
            val value=JSONObject().put("attached",c.isAttached)
            if(c.isAttached){
                val p=c.positionInRoot()
                value.put("leftPx",p.x).put("topPx",p.y).put("widthPx",c.size.width).put("heightPx",c.size.height)
            }
            return value
        }
        fun layout(info:LayoutInfo):JSONObject {
            val modifiers=JSONArray()
            info.getModifierInfo().forEachIndexed {index,m->modifiers.put(JSONObject()
                .put("index",index).put("kind",m.modifier.javaClass.name).put("coordinates",coordinates(m.coordinates)))}
            return JSONObject().put("layoutWidthPx",info.width).put("layoutHeightPx",info.height)
                .put("density",info.density.density).put("fontScale",info.density.fontScale).put("placed",info.isPlaced)
                .put("contentCoordinates",coordinates(info.coordinates)).put("modifiers",modifiers)
        }
        fun fieldLayout(tag:String):JSONObject {
            val merged=compose.onNodeWithTag(tag).fetchSemanticsNode()
            val unmerged=compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode()
            return layout(merged.layoutInfo)
                .put("mergedMergesDescendants",merged.config.isMergingSemanticsOfDescendants)
                .put("mergedHasSetText",merged.config.contains(SemanticsActions.SetText))
                .put("unmergedMergesDescendants",unmerged.config.isMergingSemanticsOfDescendants)
                .put("unmergedHasSetText",unmerged.config.contains(SemanticsActions.SetText))
                .put("unmergedBoundsDp",rect(compose.onNodeWithTag(tag,useUnmergedTree=true).getUnclippedBoundsInRoot()))
        }
        fun paragraph(result:TextLayoutResult):JSONObject {
            val input=result.layoutInput
            return JSONObject().put("widthPx",result.size.width).put("heightPx",result.size.height)
                .put("density",input.density.density).put("fontScale",input.density.fontScale)
                .put("fontSize",input.style.fontSize.toString()).put("lineHeight",input.style.lineHeight.toString())
                .put("style",input.style.toString()).put("softWrap",input.softWrap).put("maxLines",input.maxLines)
                .put("overflow",input.overflow.toString()).put("layoutDirection",input.layoutDirection.toString())
                .put("constraints",input.constraints.toString())
        }
        fun text(tag:String,expected:String):Region {
            val node=compose.onNodeWithTag(tag,useUnmergedTree=true).assertTextEquals(expected)
            val layouts=mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){assertTrue(it(layouts))}
            return Region(node.getUnclippedBoundsInRoot(),layouts.single(),expected)
        }
        fun capture(){
            val file=File("build/reports/tests/testDebugUnitTest/source-facts/$frameName-layout.json")
            file.parentFile.mkdirs();file.writeText(receipt.toString(2));frame(frameName)
        }
        try {
            compose.waitForIdle()
            receipt.put("viewport",rect(compose.onNodeWithTag("facts-dialog").getUnclippedBoundsInRoot()))
            val sample=checkNotNull(formMeasure.get()) {"Missing actual Dialog measurement"}
            val sameSession=sample.first==checkNotNull(state.value.sourceFacts).session
            val trace=sample.second
            val predictions=JSONArray()
            trace.predictions.forEach {p->predictions.put(JSONObject().put("label",p.label)
                .put("widthPx",p.widthPx).put("heightPx",p.heightPx).put("fontSize",p.fontSize).put("lineHeight",p.lineHeight)
                .put("style",p.style).put("density",p.density).put("fontScale",p.fontScale)
                .put("softWrap",p.softWrap).put("maxLines",p.maxLines).put("constraints",p.constraints))}
            receipt.put("productionMeasurement",JSONObject().put("sameSession",sameSession)
                .put("density",trace.density).put("fontScale",trace.fontScale).put("reservedHalfPx",trace.reservedHalfPx)
                .put("measuredHalfPx",trace.measuredHalfPx).put("insetPx",trace.insetPx).put("insetDp",trace.insetDp.value)
                .put("predictions",predictions))
            val rejected=checkNotNull(state.value.sourceFacts).rejectedInput
            val rows=specs.map{spec->
                val field=compose.onNodeWithTag(spec.tag).getUnclippedBoundsInRoot()
                val label=text("fact-label:"+spec.role,spec.label);val help=text("fact-help:"+spec.role,spec.help)
                val errors=compose.onAllNodesWithTag("fact-error:"+spec.role,useUnmergedTree=true).fetchSemanticsNodes()
                assertEquals(if(FactInput.valueOf(spec.role) in rejected)1 else 0,errors.size)
                val error=if(errors.isEmpty())null else text("fact-error:"+spec.role,"超长输入未接纳；当前仅显示有界预览，请改正后再保存。")
                measured.put(JSONObject().put("role",spec.role).put("field",rect(field)).put("label",rect(label.bounds)).put("help",rect(help.bounds))
                    .put("labelLines",label.layout.lineCount).put("labelOverflow",label.layout.hasVisualOverflow)
                    .put("helpLines",help.layout.lineCount).put("helpOverflow",help.layout.hasVisualOverflow)
                    .put("error",error?.let{rect(it.bounds)}?:JSONObject.NULL)
                    .put("labelParagraph",paragraph(label.layout)).put("fieldLayout",fieldLayout(spec.tag))
                    .put("labelLayout",layout(compose.onNodeWithTag("fact-label:"+spec.role,useUnmergedTree=true).fetchSemanticsNode().layoutInfo)))
                Row(spec,field,label,help,error)
            }
            assertTrue("Dialog measurement must belong to the current session",sameSession)
            assertEquals(fontScale,trace.fontScale,0f)
            rows.forEachIndexed{index,row->
                for(region in listOfNotNull(row.label,row.help,row.error)){
                    val b=region.bounds;val f=row.field
                    assertTrue(row.spec.role+" requires finite real bounds",listOf(b.left.value,b.top.value,b.right.value,b.bottom.value).all{it.isFinite()})
                    assertEquals(fontScale,region.layout.layoutInput.density.fontScale,0f);assertFalse(region.layout.hasVisualOverflow)
                    assertEquals(region.expected.length,region.layout.getLineEnd(region.layout.lineCount-1,visibleEnd=true))
                    assertTrue(row.spec.role+" text must stay inside its own field",b.left>=f.left&&b.right<=f.right&&b.top>=f.top&&b.bottom<=f.bottom)
                }
                assertEquals(row.spec.role+" floating label must remain one line",1,row.label.layout.lineCount)
                assertTrue(row.help.bounds.top>=row.label.bounds.bottom)
                row.error?.let{assertTrue(it.bounds.top>=row.help.bounds.bottom)}
                if(index>0){
                    val previous=rows[index-1].field
                    assertTrue(row.spec.role+" field overlaps previous field",row.field.top>=previous.bottom)
                    assertTrue(row.spec.role+" label overlaps previous field",row.label.bounds.top>=previous.bottom)
                }
            }
            receipt.put("status","PASS")
        }catch(t:Throwable){
            receipt.put("status","FAIL").put("failure_type",t.javaClass.simpleName)
            runCatching{capture()}.exceptionOrNull()?.let{t.addSuppressed(it)}
            throw t
        }
        capture()
    }
    @Test fun explicitCandidateSeparateAcceptanceCorrectionAndRevocationReachExactOriginalAndUnifiedFact(){
        RuntimeEnvironment.setFontScale(1f);compose.setContent {Screen()};reader();open();form();click("fact-save")
        click("fact-cancel");assertEquals(0,writes);click("fact-save");confirm();assertEquals(1,creates)
        assertEquals(FactReview.PROPOSED,saved!!.review!!.review);accept();assertEquals(FactReview.ACCEPTED,saved!!.review!!.review)
        click("fact-correct:"+saved!!.id);compose.onNodeWithTag("fact-from").performScrollTo().performTextReplacement("2026-10-24")
        compose.onNodeWithTag("fact-zone").performScrollTo().performTextReplacement("UTC");click("fact-affirm");click("fact-save");confirm()
        assertEquals(FactReview.PROPOSED,saved!!.review!!.review);assertEquals(FactReview.ACCEPTED,saved!!.reviews.first().priorReview);accept()
        click("fact-source:"+saved!!.id);drain();compose.onNodeWithTag("original-text").assertTextEquals(entry.originalText)
        assertNull(state.value.sourceFacts);val text=compose.onNodeWithTag("original-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single();assertTrue(text.spanStyles.isNotEmpty())
        backToList();compose.onNodeWithTag("unified-open").performScrollTo().performClick();compose.waitForIdle()
        compose.onNodeWithTag("unified-query").performTextReplacement("合成办公物品");compose.onNodeWithTag("unified-scope").performScrollTo().performClick()
        compose.onNodeWithTag("unified-scope:FACT").performScrollTo().performClick();compose.onNodeWithTag("unified-results").performScrollToIndex(2)
        compose.runOnIdle{assertSame(queryPort,owner.unifiedKeywords)}
        click("unified-search");drain()
        compose.runOnIdle{assertNull(state.value.unifiedKeyword!!.error);assertNotNull(state.value.unifiedKeyword!!.snapshot)}
        val hit=state.value.unifiedKeyword!!.snapshot!!.groups.single().items.single() as UnifiedKeywordEvidence.Fact
        compose.onNodeWithTag("unified-results").performScrollToIndex(3)
        compose.onNodeWithTag("unified-fact-match:"+hit.key).performScrollTo().assertTextEquals("人工主题命中 · 原文仅作出处")
        val quote=compose.onNodeWithTag("unified-fact-quote:"+hit.key).performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single();assertTrue(quote.spanStyles.isEmpty())
        click("unified-source:"+hit.key);drain();compose.onNodeWithTag("original-text").assertTextEquals(entry.originalText)
        val original=compose.onNodeWithTag("original-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertTrue("Topic-only finding must not highlight the original as a keyword match",original.spanStyles.isEmpty())
        open();click("fact-revoke:"+saved!!.id);drain();val version=saved!!.rowVersion;click("fact-cancel");assertEquals(version,saved!!.rowVersion)
        click("fact-revoke:"+saved!!.id);drain();confirm();assertEquals(FactReview.REVOKED,saved!!.review!!.review)
        click("fact-close");backToList();global();click("fact-section-ACCEPTED");drain();assertTrue(state.value.sourceFacts!!.items.isEmpty())
        assertEquals("合成未提交草稿",state.value.text);assertEquals(listOf(entry),state.value.entries);assertTrue(state.value.tasks.items.isEmpty())
    }
    @Test fun actualPlatformDoubleFontKeepsEndpointRejectionStickyAndUnknownSameIntentFrozen(){
        RuntimeEnvironment.setFontScale(2f);compose.setContent{Screen()};reader();open();form()
        compose.onNodeWithTag("fact-from").performScrollTo().performTextReplacement("2026-10-24")
        compose.onNodeWithTag("fact-zone").performScrollTo().performTextReplacement("UTC")
        compose.onNodeWithTag("fact-from").performTextReplacement("2026-10-240")
        compose.runOnIdle{assertEquals("2026-10-240",state.value.sourceFacts!!.from);assertTrue(FactInput.FROM in state.value.sourceFacts!!.rejectedInput)}
        compose.onNodeWithTag("fact-quote").performScrollTo().performTextReplacement("我的合成杯子是绿色")
        compose.runOnIdle{assertTrue(FactInput.FROM in state.value.sourceFacts!!.rejectedInput)}
        click("fact-save");assertEquals(0,writes);assertNull(state.value.sourceFacts!!.confirmation)
        val field=compose.onNodeWithTag("fact-quote").performScrollTo().assertIsDisplayed();val layouts=mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult){assertTrue(it(layouts))};val layout=layouts.single()
        assertEquals(2f,layout.layoutInput.density.fontScale,0f);assertFalse(layout.hasVisualOverflow);assertEquals(state.value.sourceFacts!!.quote.length,layout.getLineEnd(layout.lineCount-1,visibleEnd=true))
        compose.runOnIdle{assertEquals(2f,checkNotNull(ShadowDialog.getLatestDialog()).context.resources.configuration.fontScale,0f)}
        val json=File("build/reports/tests/testDebugUnitTest/source-facts/fontscale.json");json.parentFile.mkdirs();json.writeText(JSONObject().put("source_sha",System.getenv("GITHUB_SHA")?:"local-not-CI").put("fontScale",layout.layoutInput.density.fontScale).put("hasVisualOverflow",layout.hasVisualOverflow).put("lineCount",layout.lineCount).toString(2))
        verifyFormLayout(2f,"02-double-font")
        for(tag in listOf("fact-save","fact-close"))compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("fact-from").performScrollTo().performTextReplacement("2026-10-24");click("fact-save")
        compose.runOnIdle{loseAck=true};confirm();val pending=state.value.sourceFacts!!.pending!!
        compose.onNodeWithTag("fact-quote").performScrollTo().assertIsNotEnabled();compose.onNodeWithTag("fact-refresh").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle{controller.refresh(state.value.sourceFacts!!.session);controller.edit(state.value.sourceFacts!!.session,FactInput.FROM,"2027-01-01");assertSame(pending,state.value.sourceFacts!!.pending)}
        assertTrue(queue.isEmpty());click("fact-retry");drain();assertNull(state.value.sourceFacts!!.pending);assertEquals(1,writes);assertEquals(1,creates)
    }
    @Test fun acceptedWriteSurvivesQueryRetirementAndActualRootRecompositionWhileActorIsBlocked(){
        RuntimeEnvironment.setFontScale(1f);compose.setContent{Screen()};reader();open();form();click("fact-save");click("fact-confirm")
        // Do not drain: the same real root has already recomposed with the pending intent.
        compose.waitForIdle();compose.onNodeWithTag("facts-dialog").assertExists();assertNotNull(state.value.sourceFacts!!.pending)
        assertTrue(state.value.sourceFacts!!.busy);assertEquals(1,retiredQueries);assertEquals(0,writes);assertEquals(1,queue.size)
        drain();assertEquals(1,writes);assertNull(state.value.sourceFacts!!.pending);assertNotNull(state.value.sourceFacts!!.notice)
        compose.onNode(hasText("普通事实自述") and hasAnyAncestor(hasTestTag("facts-dialog"))).performScrollTo().assertIsDisplayed()
        verifyFormLayout(1f,"01-normal-editor");assertEquals(listOf(entry),state.value.entries)
        val s=state.value.sourceFacts!!.session;click("fact-accept:"+saved!!.id);drain();click("fact-confirm");compose.waitForIdle()
        assertEquals(s,state.value.sourceFacts!!.session);assertEquals(1,writes);drain();assertEquals(2,writes)
    }
    @Test fun realCloseAndOwnerReplacementRejectQueuedOldConfirmationWithoutNewOwnerPublication(){
        RuntimeEnvironment.setFontScale(1f);compose.setContent{Screen()};reader();open();form();click("fact-save");val old=state.value.sourceFacts!!
        click("fact-confirm");compose.waitForIdle();click("fact-close");drain();assertEquals(0,writes);assertNull(state.value.sourceFacts)
        open();form();click("fact-save");click("fact-confirm");compose.waitForIdle()
        compose.runOnIdle{state.value=state.value.copy(sourceFactOwner=object:SourceFactRepository by port {})};compose.waitForIdle();drain()
        assertEquals(0,writes);assertNull(state.value.sourceFacts);compose.runOnIdle{controller.confirm(old.session,old.confirmation!!)};drain();assertEquals(0,writes)
        compose.runOnIdle{assertFalse(checkNotNull(registry).performSave().toString().contains("我的合成杯子是绿色"))};assertEquals(listOf(entry),state.value.entries)
    }
}
