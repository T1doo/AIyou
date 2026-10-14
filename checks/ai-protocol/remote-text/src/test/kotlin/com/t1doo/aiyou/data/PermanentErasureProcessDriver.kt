package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Only independently owned synthetic test roots, actual production stores and SQL. */
object PermanentErasureProcessDriver {
    val MEDIA = "synthetic erasure child media".toByteArray()
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 7)
        val root=Path.of(args[0]); val mode=args[1]; val source=args[2]; val attachment=args[3]; val generation=args[4].toLong(); val mutation=args[5]; val pause=args[6]
        val db=PythonSqliteDatabase(root.resolve("records.sqlite"),root.resolve("sqlite_bridge.py"),false,7)
        val gateway=DrainingRecordsSqlDatabase(db); val tags=SqlManualTagRepository(gateway,PermanentErasureFixture.KEY)
        val commands=LinkedBlockingQueue<String>()
        Thread { System.`in`.bufferedReader().forEachLine {commands.offer(it)} }.apply {isDaemon=true;start()}
        val pid=ProcessHandle.current().pid(); val ppid=ProcessHandle.current().parent().orElseThrow().pid()
        fun event(phase: String, value: String="") { println("ERASURE_CHILD\t$pid\t$ppid\t${db.pid}\t$phase\t$value");System.out.flush() }
        fun gate(stage: String) {if(stage==pause) {event("PAUSED",stage);check(commands.poll(20,TimeUnit.SECONDS)=="RESUME")}}
        val hooks=SqlErasureFileHooks(gateway)
        val media=EncryptedAttachmentStore(root.resolve("blobs").toFile(),PermanentErasureFixture.Ledger(gateway),PermanentErasureFixture.KEY.copyOf(),::gate).also {it.installErasureHooks(hooks)}
        val edits=EncryptedOriginalEditStore(root.resolve("edits").toFile(),PermanentErasureFixture.KEY.copyOf(),::gate).also {it.installErasureHooks(hooks)}
        val owner=DrainingRecordsStorage(gateway,media,edits,{db.close()},{tags.wipeKey()})
        event("OPEN")
        check(commands.poll(20,TimeUnit.SECONDS)=="START")
        var opens=0
        try {
            when(mode) {
                "attachment-write", "attachment-late" -> {
                    val intent=AttachmentIntent(attachment,source,1,"image/png",PermanentErasureFixture.hash(MEDIA),MEDIA.size.toLong())
                    try {media.save(intent) {opens++;ByteArrayInputStream(MEDIA)};event("RESULT","SAVED:$opens")}
                    catch (_: AttachmentConflict) {event("RESULT","BLOCKED:$opens")}
                    catch (_: IllegalStateException) {event("RESULT","BLOCKED:$opens")}
                }
                "edit-write", "edit-late" -> {
                    val draft=OriginalEditDraft(PermanentErasureFixture.uuid(),source,1,1,1,"合成私密原文🙂","合成跨进程草稿",OriginalEditDraftState.ACTIVE)
                    try {edits.write(null,draft);event("RESULT","SAVED")}
                    catch (_: OriginalEditDraftConflict) {event("RESULT","BLOCKED")}
                }
                "erase" -> {
                    val erase=SqlPermanentErasureRepository(gateway,media,edits,SqlRecordPackageRepository(gateway,tags))
                    event("RESULT",erase.erase(PermanentErasureIntent(SourceVersion(source,1),generation,mutation)).phase.name)
                }
                else -> error("Unknown synthetic process mode")
            }
        } finally {owner.close();owner.awaitClosed();event("CLOSED")}
    }
}
