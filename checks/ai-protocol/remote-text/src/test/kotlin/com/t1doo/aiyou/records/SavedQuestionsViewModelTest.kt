package com.t1doo.aiyou.records

import android.app.Application
import com.t1doo.aiyou.data.*
import com.t1doo.aiyou.domain.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual admitted VM/serial command lane -> real question SQL/cipher. No platform/network substitution. */
class SavedQuestionsViewModelTest {
    @Test(timeout=15000) fun actualOwnerBindsHistoryAndDrainsConfirmedSaveAfterPanelCloseWithoutEntryWrite():Unit=runBlocking {
        FullFixture().use {f ->
            val history=SqlSavedQuestionRepository(f.gateway,f.key)
            val base=SqliteVmEntryRepository(f.db,f.gateway,RemoteTextTransactions(f.gateway)) {
                f.gateway.stopAndAwaitIdle();history.wipeKey()
            }
            val owner=object:EntryRepository by base,AutoCloseable {
                override val savedQuestions=history
                override suspend fun prepare() {}
                override fun close()=base.close()
            }
            val vm=LocalRecordsViewModel(Application(),{owner},Clock.fixed(Instant.ofEpochMilli(100),ZoneOffset.UTC))
            try {
                val ready=withTimeout(4000) {vm.state.first {it.ready&&!it.opening}}
                assertSame(history,ready.savedQuestionOwner)
                val c=vm.savedQuestions;c.open("通过生产队列保存的合成问题")
                var ui=withTimeout(4000) {c.state.first {it?.available==true&&!it.busy}!!}
                c.review(ui);ui=c.state.value!!;assertNotNull(ui.confirmation);c.confirm(ui);ui=c.state.value!!;c.close(ui)
                val drained=CompletableDeferred<Unit>();assertTrue(vm.enqueueTaskOperation {drained.complete(Unit)})
                withTimeout(4000) {drained.await()};assertNull(c.visible())
                assertEquals(1,history.list().size);assertTrue(f.db.read("SELECT * FROM entries").isEmpty())
                assertTrue(f.db.read("SELECT * FROM local_candidate_batches").isEmpty())
                c.open();val reopened=withTimeout(4000) {c.state.first {it?.available==true&&!it.busy}!!}
                assertEquals("通过生产队列保存的合成问题",reopened.selected!!.question)
            } finally {
                vm.clearForHostTest();assertTrue(base.closed.await(4,TimeUnit.SECONDS));base.closeFailure.get()?.let {throw it}
                assertNull(vm.savedQuestions.visible())
            }
        }
    }
}
