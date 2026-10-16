package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SourceTasksTest {
    private val binding=TaskSourceBinding(SourceVersion("synthetic",2),OriginalExports.hash("合成🙂原文"),7,0,5)
    private fun request(b:TaskSourceBinding=binding,title:String="合成人工标题",mutation:String="explicit",schedule:ManualTaskSchedule?=null)=
        SourceTaskCreate(SourceTaskPreview(b,"合成🙂原文"),ManualTaskCreate(SourceTasks.id(b),title,20,"UTC",mutation,schedule))
    @Test fun stableFragmentIdentityAndImmutableReceiptDistinguishEveryExplicitChoice() {
        val request=request();assertEquals(SourceTasks.id(binding),request.task.id);assertNull(request.task.schedule)
        assertEquals(SourceTasks.receipt(request),SourceTasks.receipt(request()))
        for(other in listOf(request(title="合成另一个标题"),request(mutation="another"),
            request(schedule=ManualTaskSchedule(LocalDate.of(2026,10,20),null,"UTC")),
            request.copy(preview=request.preview.copy(quote="另一个原文")))) {
            assertNotEquals(SourceTasks.receipt(request),SourceTasks.receipt(other))
        }
        assertNotEquals(SourceTasks.id(binding),SourceTasks.id(binding.copy(generation=8)))
        assertNotEquals(SourceTasks.id(binding),SourceTasks.id(binding.copy(source=SourceVersion("synthetic",3))))
    }
    @Test fun sourceBoundsForeignTaskIdentityAndPrivateRepresentationsCannotBeConfused() {
        assertThrows(IllegalArgumentException::class.java) { binding.copy(end=0) }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(end=321) }
        assertThrows(IllegalArgumentException::class.java) { SourceTaskPreview(binding,"不匹配") }
        assertThrows(IllegalArgumentException::class.java) { SourceTaskCreate(request().preview,ManualTaskCreate("foreign","标题",20,"UTC","id")) }
        assertFalse(request().toString().contains("合成"));assertFalse(binding.toString().contains("synthetic"))
    }
}
