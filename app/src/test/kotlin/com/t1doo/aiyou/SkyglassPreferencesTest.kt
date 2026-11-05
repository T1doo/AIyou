package com.t1doo.aiyou

import android.util.AtomicFile
import com.t1doo.aiyou.ui.FileUiPreferences
import com.t1doo.aiyou.model.ProcessingPolicy
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
class SkyglassPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun platformAtomicFileReplacementIsVisibleToReadback() {
        val file = File(temporary.newFolder(), "atomic-platform-probe")
        val atomic = android.util.AtomicFile(file)
        for (value in listOf("synthetic-first", "synthetic-second")) {
            val stream = atomic.startWrite()
            stream.write(value.toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
            atomic.finishWrite(stream)
            assertEquals("Platform AtomicFile replacement must be confirmed", value,
                atomic.openRead().use { it.readBytes().toString(Charsets.US_ASCII) })
        }
    }

    @Test fun legacyV4ReadIsUntouchedAndEveryThemeMotionPairSurvivesColdRead() {
        val file = File(temporary.newFolder(), "preferences")
        val legacy = "AIyou display v4\nfalse\ntrue\nWEEK\nAI_NONE\nfalse\n"
        file.writeText(legacy)
        val storage = FileUiPreferences(file)
        val original = storage.read()
        assertEquals(legacy, file.readText())
        for (theme in com.t1doo.aiyou.designsystem.SkyglassAppearance.entries)
            for (motion in com.t1doo.aiyou.designsystem.SkyglassMotion.entries) {
                val chosen = original.copy(appearance = theme, backgroundMotion = motion)
                storage.write(chosen)
                assertEquals(chosen, FileUiPreferences(file).read())
                assertEquals(ProcessingPolicy.AI_NONE, storage.newCapturePolicy())
                storage.write(chosen.copy(reduceMotion = false))
                assertEquals(motion, FileUiPreferences(file).read().backgroundMotion)
                assertEquals(theme, FileUiPreferences(file).read().appearance)
            }
    }

    @Test fun unknownV5ThemeOrMotionNeverBroadensPolicyOrRewritesBytes() {
        for (tail in listOf("UNKNOWN\nDRIFT\n", "BLUE\nUNKNOWN\n", "BLUE\nDRIFT", "BLUE\nDRIFT\nextra\n")) {
            val file = File(temporary.newFolder(), "preferences")
            val bytes = "AIyou display v5\ntrue\nfalse\nDAY\nLOCAL_ONLY\ntrue\n$tail"
            file.writeText(bytes)
            val storage = FileUiPreferences(file)
            assertThrows(IOException::class.java) { storage.read() }
            assertEquals(ProcessingPolicy.AI_NONE, storage.newCapturePolicy())
            assertEquals(bytes, file.readText())
        }
    }

}
