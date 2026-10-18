package com.t1doo.aiyou

import com.t1doo.aiyou.ui.AppAccessPreference
import com.t1doo.aiyou.ui.FileAppAccessPreference
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppAccessPreferenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun confirmedSettingSurvivesNewInstancesAndStaleWriterCannotDisableIt() {
        val file = File(temporary.newFolder(), "access/startup")
        val first = FileAppAccessPreference(file); val second = FileAppAccessPreference(file)
        val original = first.read(); assertEquals(AppAccessPreference(0, false), original)
        val enabled = first.write(original, true)
        assertTrue(enabled.enabled); assertEquals(1L, enabled.version); assertEquals(enabled, second.read())
        try { second.write(original, false); fail("Stale snapshot must not overwrite enabled lock") } catch (_: IOException) { }
        assertEquals(enabled, first.read())
        val disabled = second.write(enabled, false)
        assertEquals(AppAccessPreference(2, false), FileAppAccessPreference(file).read()); assertEquals(2L, disabled.version)
    }

    @Test fun malformedOversizedAndIncompleteSettingFailClosedAndPreserveTheirBytes() {
        val directory = temporary.newFolder()
        for ((index, bytes) in listOf("".toByteArray(), "false".toByteArray(),
            "AIyou startup access v1\n01\nfalse\n".toByteArray(),
            "AIyou startup access v1\n9223372036854775808\nfalse\n".toByteArray(), ByteArray(90) { 65 }).withIndex()) {
            val file = File(directory, "invalid-$index"); file.writeBytes(bytes)
            try { FileAppAccessPreference(file).read(); fail("Invalid setting must not default to unlocked") } catch (_: IOException) { }
            assertArrayEquals(bytes, file.readBytes())
        }
        val absent = File(directory, "incomplete"); val partial = File(absent.path + ".new")
        partial.writeText("AIyou startup access v1\n1\ntrue\n")
        val bytes = partial.readBytes()
        try { FileAppAccessPreference(absent).read(); fail("Incomplete transition must not default to unlocked") } catch (_: IOException) { }
        assertArrayEquals(bytes, partial.readBytes()); assertFalse(absent.exists())
    }

    @Test fun atomicRecoveryKeepsConfirmedEnabledBackupAndVersionOverflowCannotOverwriteIt() {
        val file = File(temporary.newFolder(), "startup")
        val backup = File(file.path + ".bak")
        backup.writeText("AIyou startup access v1\n4\ntrue\n")
        assertEquals(AppAccessPreference(4, true), FileAppAccessPreference(file).read())
        file.writeText("AIyou startup access v1\n9223372036854775807\ntrue\n")
        val old = file.readBytes(); val storage = FileAppAccessPreference(file)
        try { storage.write(storage.read(), false); fail("Version overflow must fail closed") } catch (_: IOException) { }
        assertArrayEquals(old, file.readBytes())
    }
}
