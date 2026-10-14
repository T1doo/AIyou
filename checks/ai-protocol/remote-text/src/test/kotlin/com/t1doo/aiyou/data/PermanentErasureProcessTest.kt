package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Independent JVMs share real SQLite and FileChannel locks. No old process suite is re-labelled. */
class PermanentErasureProcessTest {
    @Test(timeout=40000) fun independentAttachmentIntentWriterCannotPublishAfterFenceAndFreshProcessResumes(): Unit = scenario("attachment-write","intent")
    @Test(timeout=40000) fun independentCreatedPartialWriterLeavesDurableOwnershipAndFreshProcessErasesIt(): Unit = scenario("attachment-write","created")
    @Test(timeout=40000) fun independentEditWriterCannotRenameAfterFenceAndFreshProcessErasesItsPartial(): Unit = scenario("edit-write","encrypted")
    private fun scenario(mode: String,pause: String) = PermanentErasureFixture().use { f ->
        val id=f.entry(); val attachment=PermanentErasureFixture.uuid()
        Child(f.root,mode,id,attachment,1,"not-yet-confirmed",pause).use { writer ->
            writer.await("PAUSED")
            val intent=f.intent(id)
            assertThrows(PermanentErasureIncomplete::class.java) {runBlocking {f.erase.erase(intent)}}
            assertEquals(PermanentErasurePhase.PREPARED,f.erase.read(id)!!.phase)
            writer.resume(); val result=writer.await("RESULT"); assertEquals(if(mode.startsWith("attachment")) "BLOCKED:0" else "BLOCKED",result.last())
            writer.finish()
            Child(f.root,"erase",id,attachment,intent.generation,intent.mutationId,"none").use { eraser ->
                assertEquals("DONE",eraser.await("RESULT").last());eraser.finish();assertNotEquals(writer.pid,eraser.pid)
            }
            assertTrue(f.erase.read(id)!!.complete)
            Child(f.root,if(mode.startsWith("attachment")) "attachment-late" else "edit-late",id,attachment,intent.generation,intent.mutationId,"none").use { late ->
                assertEquals(if(mode.startsWith("attachment")) "BLOCKED:0" else "BLOCKED",late.await("RESULT").last());late.finish();assertNotEquals(writer.pid,late.pid)
            }
            assertEquals(listOf(".store.lock"),f.mediaRoot.list()!!.toList());assertEquals(listOf(".store.lock"),f.editRoot.list()!!.toList())
            assertTrue(f.db.read("SELECT id FROM entries WHERE id=?",id).isEmpty());assertTrue(f.db.read("PRAGMA foreign_key_check").isEmpty())
        }
    }
    private class Child(root: Path,mode: String,id: String,attachment: String,generation: Long,mutation: String,pause: String) : AutoCloseable {
        private val lines=LinkedBlockingQueue<String>()
        private val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
            checkNotNull(System.getProperty("aiyou.erasure.process.classpath")),PermanentErasureProcessDriver::class.java.name,
            root.toString(),mode,id,attachment,generation.toString(),mutation,pause).redirectErrorStream(true).start()
        val pid=process.pid();private var bridgePid: Long?=null
        private val output=process.outputStream.bufferedWriter();private val transcript=mutableListOf<String>()
        private val evidence=Path.of(checkNotNull(System.getProperty("aiyou.erasure.process.evidence")))
        init {
            Files.createDirectories(evidence)
            Thread {process.inputStream.bufferedReader().forEachLine {lines.offer(it)}}.apply {isDaemon=true;start()}
            await("OPEN")
            output.write("START\n");output.flush()
        }
        fun await(phase: String): List<String> {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(true) {
                val remaining=deadline-System.nanoTime();check(remaining>0) {"Owned erasure child phase deadline"}
                val line=lines.poll(remaining,TimeUnit.NANOSECONDS) ?: error("Owned erasure child phase deadline")
                transcript+=line
                if(!line.startsWith("ERASURE_CHILD\t")) continue
                val fields=line.split('\t');require(fields.size==6)
                assertEquals(pid,fields[1].toLong());assertEquals(ProcessHandle.current().pid(),fields[2].toLong())
                val sqlitePid=fields[3].toLong()
                if(bridgePid==null) {
                    bridgePid=sqlitePid
                    assertEquals(pid,ProcessHandle.of(sqlitePid).orElseThrow().parent().orElseThrow().pid())
                } else assertEquals(bridgePid,sqlitePid)
                if(fields[4]==phase) return fields
            }
        }
        fun resume() {output.write("RESUME\n");output.flush()}
        fun finish() {
            await("CLOSED");assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue())
            bridgePid?.let { p -> ProcessHandle.of(p).ifPresent { assertTrue(it.onExit().get(3,TimeUnit.SECONDS).isAlive.not()) } }
            println("PERMANENT_ERASURE_PROCESS_EVENT jvm=$pid parent=${ProcessHandle.current().pid()} sqlite=$bridgePid complete=true")
        }
        override fun close() {
            try {
                if(process.isAlive) {runCatching {resume()};output.close();if(!process.waitFor(10,TimeUnit.SECONDS)) {process.destroy();check(process.waitFor(3,TimeUnit.SECONDS))}}
            } finally {Files.write(evidence.resolve("child-$pid.log"),transcript);output.close()}
        }
    }
}
