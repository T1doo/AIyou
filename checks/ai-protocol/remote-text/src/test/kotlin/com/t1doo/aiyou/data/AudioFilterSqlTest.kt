package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AudioFilterSqlTest {
    @Test fun audioFilterUsesCurrentRevisionAndRechecksCompleteAttachmentMembership():Unit=runBlocking {
        RecordEventDateFixture().use {f->
            val original=f.entry();val other=f.entry();f.drain()
            fun audio(id:String,revision:Int=1) {f.db.write("INSERT INTO attachments VALUES(?,?,?,'audio/wav',?,46,'READY')",UUID.randomUUID().toString(),id,revision,"a".repeat(64))}
            audio(original)
            val input=OriginalSearchInput(onlyAudio=true)
            val entries=f.search.candidates(LocalSearchRequest("")).entries
            val result=LocalOriginalSearchMatcher.indexed(entries,input,f.search)
            assertEquals(OriginalSearchStage.READY,result.stage)
            assertEquals(listOf(original),result.hits.map{it.entry.id})
            assertTrue(f.search.publishCurrent(result.proof!!) {})
            audio(other)
            assertFalse(f.search.publishCurrent(result.proof!!) {})
            val proof=f.search.candidates(LocalSearchRequest("",onlyAudio=true))
            assertEquals(setOf(original,other),proof.currentAudioEntryIds)
            f.append(original)
            assertFalse(f.search.publishCurrent(proof) {})
            assertEquals(setOf(other),f.search.candidates(LocalSearchRequest("",onlyAudio=true)).currentAudioEntryIds)
        }
    }
}
