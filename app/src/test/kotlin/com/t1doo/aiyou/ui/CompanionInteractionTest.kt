package com.t1doo.aiyou.ui

import com.t1doo.aiyou.designsystem.CompanionPose
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CompanionInteractionTest {
    @Test fun onlyExplicitForegroundActionsStartAndBusyDoubleClicksCannotReplaceTheirRun() {
        val panel = CompanionInteraction()
        assertNull(panel.start(CompanionPose.Greeting).run)
        panel.foreground(true)
        assertNull(panel.start(CompanionPose.Idle).run)
        val run = checkNotNull(panel.start(CompanionPose.Greeting).run)
        assertSame(run, panel.start(CompanionPose.Greeting).run)
        assertSame(run, panel.start(CompanionPose.Encouraging).run)
        assertEquals(CompanionPose.Greeting, panel.snapshot.pose)
        assertEquals(CompanionPose.Idle, panel.finish(run).pose)
        assertEquals(CompanionPose.Greeting, panel.snapshot.feedback)
        assertEquals(CompanionPose.Encouraging, panel.start(CompanionPose.Encouraging).pose)
    }

    @Test fun quietCancelsAndOldCompletionCannotFinishTheNextExplicitAction() {
        val panel = CompanionInteraction()
        panel.foreground(true)
        val first = checkNotNull(panel.start(CompanionPose.Greeting).run)
        assertEquals(CompanionPose.Idle, panel.quiet().feedback)
        val second = checkNotNull(panel.start(CompanionPose.Encouraging).run)
        assertNotSame(first, second)
        assertSame(second, panel.finish(first).run)
        assertNull(panel.finish(second).run)
        assertEquals(CompanionPose.Idle, panel.quiet().pose)
    }

    @Test fun backgroundCancelsAndForegroundReturnDoesNotReplayAnOldAction() {
        val panel = CompanionInteraction()
        panel.foreground(true)
        val old = checkNotNull(panel.start(CompanionPose.Greeting).run)
        assertEquals(CompanionInteraction.Snapshot(), panel.foreground(false))
        assertNull(panel.start(CompanionPose.Encouraging).run)
        assertNull(panel.foreground(true).run)
        assertEquals(CompanionPose.Idle, panel.snapshot.feedback)
        val fresh = checkNotNull(panel.start(CompanionPose.Encouraging).run)
        assertSame(fresh, panel.finish(old).run)
    }

    @Test fun retirementFencesBlockedCompletionAndAllOldCallbacksWithoutAffectingNewPanel() {
        val old = CompanionInteraction()
        old.foreground(true)
        val run = checkNotNull(old.start(CompanionPose.Greeting).run)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                waiting.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                old.finish(run)
            } catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        assertTrue(old.retire())
        assertFalse(old.retire())
        val fresh = CompanionInteraction()
        fresh.foreground(true)
        val freshRun = checkNotNull(fresh.start(CompanionPose.Encouraging).run)
        try {
            old.foreground(true); old.start(CompanionPose.Encouraging); old.quiet()
        } finally { release.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(CompanionInteraction.Snapshot(retired = true), old.snapshot)
        assertSame(freshRun, fresh.snapshot.run)
    }
}
