package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskUndoFullBackupTest {
    @Test fun realCrossKeyFullBackupRestoresReopenedTaskAndRetiresUndoReceipt():Unit=runBlocking {
        FullFixture().use {source->FullFixture(90).use {target->
            val a=SqlManualTaskRepository(source.gateway,source.key);val b=SqlManualTaskRepository(target.gateway,target.key)
            try {
                val created=a.create(ManualTaskCreate("one","合成撤销备份",10,"UTC","create"))
                val done=a.change(ManualTaskMutation(created.id,created.version,ManualTaskStatus.COMPLETED,20,"done"))
                val request=ManualTaskReopen(done.id,done.version,30,"undo")
                val reopened=a.reopen(request)
                val snapshot=source.backup.capture(UUID.randomUUID().toString(),100)
                val sealed=source.backup.seal(snapshot,password());val out=ByteArrayOutputStream()
                try {sealed.writeTo(out){true}} finally {sealed.release();snapshot.release()}
                val bytes=out.toByteArray()
                val pack=target.backup.open({bytes.inputStream()},password())
                try {
                    assertEquals(1,pack.summary.tasksAndProposals)
                    target.backup.restore(pack){true}
                    assertEquals(reopened,b.snapshots().single())
                    assertThrows(com.t1doo.aiyou.domain.ManualTaskConflict::class.java) {b.reopen(request)}
                    assertTrue(target.backup.restore(pack){true}.replayed)
                    assertEquals(1,b.snapshots().size)
                } finally {pack.release();bytes.fill(0)}
            } finally {a.wipeKey();b.wipeKey()}
        }}
    }
    private fun password()="synthetic-frontend-task-backup".toCharArray()
}
