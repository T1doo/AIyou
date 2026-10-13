package com.t1doo.aiyou

import com.t1doo.aiyou.ui.FileUiPreferences
import com.t1doo.aiyou.ui.UiPreferences
import com.t1doo.aiyou.ui.UiPreferencesStorage
import com.t1doo.aiyou.ui.UiPreferencesViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bothDisplayChoicesSurviveNewStorageAndViewModelInstances() {
        val file = File(temporary.newFolder(), "display/preferences")
        val first = FileUiPreferences(file)
        assertEquals(UiPreferences(), first.read())
        first.write(UiPreferences(showCompanion = false, reduceMotion = true))
        assertEquals(UiPreferences(false, true), FileUiPreferences(file).read())
        val vm = UiPreferencesViewModel(RuntimeEnvironment.getApplication(), FileUiPreferences(file), Dispatchers.Unconfined)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.state.value.ready)
        assertEquals(UiPreferences(false, true), vm.state.value.preferences)
    }

    @Test fun failedWriteKeepsConfirmedValuesAndRetryPersistsTheRequestedChoice() {
        var stored = UiPreferences()
        var fail = true
        var writes = 0
        val storage = object : UiPreferencesStorage {
            override fun read() = stored
            override fun write(value: UiPreferences) {
                writes++
                if (fail) throw IOException("synthetic private details")
                stored = value
            }
        }
        val vm = UiPreferencesViewModel(RuntimeEnvironment.getApplication(), storage, Dispatchers.Unconfined)
        shadowOf(Looper.getMainLooper()).idle()
        vm.change(UiPreferences(false, true)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(UiPreferences(), vm.state.value.preferences)
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.error!!.contains("synthetic"))
        fail = false
        vm.retry(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, writes)
        assertEquals(UiPreferences(false, true), vm.state.value.preferences)
        assertEquals(stored, vm.state.value.preferences)
        assertNull(vm.state.value.error)
    }

    @Test fun corruptPreferencesArePreservedAndNeverFlashTheCompanion() {
        val file = File(temporary.newFolder(), "preferences").apply { writeText("synthetic corrupt") }
        val vm = UiPreferencesViewModel(RuntimeEnvironment.getApplication(), FileUiPreferences(file), Dispatchers.Unconfined)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.state.value.ready)
        assertFalse(vm.state.value.preferences.showCompanion)
        assertTrue(vm.state.value.preferences.reduceMotion)
        assertNotNull(vm.state.value.error)
        vm.change(UiPreferences()); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("synthetic corrupt", file.readText())
    }
}
