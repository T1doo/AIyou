package com.t1doo.aiyou.ui

import com.t1doo.aiyou.designsystem.CompanionPose

/** Ephemeral panel ownership only. No record, preference, storage or network ports. */
internal class CompanionInteraction {
    class Run internal constructor(val pose: CompanionPose)
    data class Snapshot(val foreground: Boolean = false, val run: Run? = null,
                        val feedback: CompanionPose = CompanionPose.Idle, val retired: Boolean = false) {
        val pose: CompanionPose get() = run?.pose ?: CompanionPose.Idle
    }
    private var value = Snapshot()
    val snapshot: Snapshot get() = synchronized(this) { value }

    @Synchronized fun foreground(active: Boolean): Snapshot {
        if (!value.retired) value = if (active) value.copy(foreground = true) else Snapshot()
        return value
    }

    @Synchronized fun start(pose: CompanionPose): Snapshot {
        if (!value.retired && value.foreground && value.run == null && pose != CompanionPose.Idle)
            value = value.copy(run = Run(pose), feedback = pose)
        return value
    }

    @Synchronized fun finish(expected: Run): Snapshot {
        if (!value.retired && value.run === expected) value = value.copy(run = null)
        return value
    }

    @Synchronized fun quiet(): Snapshot {
        if (!value.retired && value.foreground) value = Snapshot(foreground = true)
        return value
    }

    @Synchronized fun retire(): Boolean {
        if (value.retired) return false
        value = Snapshot(retired = true)
        return true
    }
}
