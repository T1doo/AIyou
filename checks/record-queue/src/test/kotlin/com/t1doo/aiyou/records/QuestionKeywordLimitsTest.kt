package com.t1doo.aiyou.records

import com.t1doo.aiyou.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
class QuestionKeywordLimitsTest {
    @Test(timeout=7000) fun actualPublicationRejectsReentrantNavigationInputBeforeAcquiringNavigation():Unit=runBlocking{
        val entry=StoredEntry("one",1,"合成淡茶",5,"UTC",ProcessingPolicy.LOCAL_ONLY)
        val source=LocalRecordUiState(ready=true,opening=false,entries=listOf(entry));val input=AtomicReference(OriginalSearchInput("淡茶",ownership=Any()))
        val gate=SearchNavigationGate();val held=CountDownLatch(1);val publication=CountDownLatch(1);val inspected=CountDownLatch(1);val rejected=AtomicBoolean()
        lateinit var c:LocalOriginalSearchController
        c=LocalOriginalSearchController({source},input::get,stateFactory={initial->
            val flow=MutableStateFlow(initial)
            object:MutableStateFlow<OriginalSearchState> by flow{
                override fun compareAndSet(expect:OriginalSearchState,update:OriginalSearchState):Boolean{
                    if(update.stage==OriginalSearchStage.READY){
                        publication.countDown()
                        rejected.set(!gate.inputDecision(0,c,{true}){c.beforeInputCommit{true}})
                        inspected.countDown()
                    }
                    return flow.compareAndSet(expect,update)
                }
            }
        },arbitrateNavigation=gate::arbitrate)
        val pool=Executors.newSingleThreadExecutor()
        try{
            val change=pool.submit{gate.arbitrate{held.countDown();check(publication.await(2,TimeUnit.SECONDS));c.changeInput{input.set(OriginalSearchInput("新的输入",ownership=Any()))}}}
            check(held.await(2,TimeUnit.SECONDS));c.sync(true)
            check(inspected.await(3,TimeUnit.SECONDS));change.get(3,TimeUnit.SECONDS)
            assertTrue(rejected.get());assertEquals("新的输入",input.get().query)
        }finally{c.close();pool.shutdownNow();check(pool.awaitTermination(2,TimeUnit.SECONDS))}
    }
    @Test fun hostilePasteSupplementary512AndTruncationStayBoundedWithoutLosingTheQuestion(){
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.propose("x".repeat(2_000_000))}
        val exact=String(IntArray(512){0x20000+it},0,512)
        val result=QuestionKeywordFragments.propose(exact)
        assertEquals(64,result.values.size);assertTrue(result.truncated)
        val cp=exact.codePoints().toArray()
        result.values.forEach{assertEquals(it.text,String(cp,it.start,it.end-it.start));assertTrue(it.end-it.start<=16)}
        assertEquals(512,exact.codePointCount(0,exact.length))
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.propose(exact+"字")}
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.propose(String(charArrayOf(0xd800.toChar())))}
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.propose("字"+0.toChar())}
    }
    @Test fun eightWordsAndSixtyFourCodepointsIncludeSpacesAndNeverSilentlyTruncate(){
        val words=(0..7).map{('a'+it).toString().repeat(if(it==7)8 else 7)}
        val joined=QuestionKeywordFragments.confirmedQuery(words);assertEquals(64,joined.codePointCount(0,joined.length))
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.confirmedQuery(words.dropLast(1)+(words.last()+"x"))}
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.confirmedQuery((1..9).map{it.toString()})}
        assertEquals("𠀀".repeat(64),QuestionKeywordFragments.confirmedQuery(listOf("𠀀".repeat(64))))
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.confirmedQuery(listOf("𠀀".repeat(65)))}
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.confirmedQuery(emptyList())}
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.confirmedQuery(listOf("没","没"))}
        assertThrows(IllegalArgumentException::class.java){QuestionKeywordFragments.confirmedQuery(listOf("不\n去"))}
    }
    @Test fun questionQuotesNegationAndNewVocabularyAreSourceFragmentsWithoutSynonyms(){
        val q="小舟说“如果没去𠀀园”，我喜欢哪种茶？";val cp=q.codePoints().toArray()
        val p=QuestionKeywordFragments.propose(q)
        p.values.forEach{assertEquals(it.text,String(cp,it.start,it.end-it.start))}
        assertTrue(p.values.none{it.text=="偏好"});assertTrue(p.values.any{it.text=="没"})
        assertEquals("没 去 𠀀园",QuestionKeywordFragments.confirmedQuery(listOf("没","去","𠀀园")))
        assertTrue(QuestionKeywordFragments.propose("？！").values.isEmpty())
    }
    @Test(timeout=5000) fun actualFallbackLocationEntersNavigationBeforeThePrivateSearchMutex():Unit=runBlocking{
        val entry=StoredEntry("one",1,"合成淡茶",5,"UTC",ProcessingPolicy.LOCAL_ONLY)
        val source=LocalRecordUiState(ready=true,opening=false,entries=listOf(entry))
        val input=OriginalSearchInput("淡茶",ownership=Any());val gate=SearchNavigationGate();val wrong=AtomicBoolean();val opened=AtomicBoolean()
        lateinit var c:LocalOriginalSearchController
        c=LocalOriginalSearchController({source},{input},arbitrateNavigation={action->
            val field=LocalOriginalSearchController::class.java.getDeclaredField("lock");field.isAccessible=true
            wrong.set(Thread.holdsLock(field.get(c)));gate.arbitrate(action)
        })
        try{c.sync(true);val ready=withTimeout(2000){c.state.first{it.stage==OriginalSearchStage.READY}}
            c.open(ready,ready.hits.single()){gate.publish(0){opened.set(true)}}
            assertTrue(opened.get());assertFalse("navigation must be acquired before the private search mutex",wrong.get())
        }finally{c.close()}
    }
}
