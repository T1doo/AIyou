package com.t1doo.aiyou

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import androidx.activity.ComponentActivity
import com.t1doo.aiyou.records.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/** Actual broker, ComponentActivity and saved Bundle; no production store/Keystore opening. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordPackagePickerTest {
    @Test fun actualLegacySafIntentHasOnlySelectedScopeAndPausedResultIsDeliveredOnce() {
        val lifecycle = Robolectric.buildActivity(HarnessActivity::class.java).setup()
        try {
            val activity = lifecycle.get(); var calls = 0
            assertTrue(activity.picker.launch(true, "synthetic-session") { calls++; assertNotNull(it); assertFalse(it.toString().contains("private-uri")) })
            val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
            assertEquals(1, launched.requestCode); assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched.intent.action)
            assertEquals("application/octet-stream", launched.intent.type)
            assertEquals("AIyou-encrypted-records.aiyoupk", launched.intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(0, launched.intent.flags); assertTrue(launched.intent.categories!!.contains(Intent.CATEGORY_OPENABLE))
            lifecycle.pause()
            activity.deliver(1, Intent().setData(Uri.parse("content://aiyou.synthetic/private-uri")))
            activity.deliver(1, Intent().setData(Uri.parse("content://aiyou.synthetic/repeated")))
            assertEquals(1, calls)
        } finally { lifecycle.destroy() }
    }
    @Test fun recreationPersistsOnlyMonotonicCounterNeverAdoptsOldRequestOrMasksHighCodes() {
        val old = Robolectric.buildActivity(HarnessActivity::class.java).setup(); var oldCalls = 0; val saved = Bundle()
        old.get().picker.launch(false, "synthetic-old") { oldCalls++ }
        old.pause().saveInstanceState(saved).stop().destroy()
        assertEquals(2, saved.getInt(ActivityRecordPackagePicker.COUNTER))
        assertPrivateAbsent(saved, listOf("synthetic-old", "content://"))
        val next = Robolectric.buildActivity(HarnessActivity::class.java).create(saved).start().resume()
        try {
            var freshCalls = 0
            assertTrue(next.get().picker.launch(false, "synthetic-new") { freshCalls++ })
            assertEquals(2, Shadows.shadowOf(next.get()).nextStartedActivityForResult.requestCode)
            next.get().deliver(1, Intent().setData(Uri.parse("content://aiyou.synthetic/old")))
            assertEquals(0, oldCalls); assertEquals(0, freshCalls)
            assertFalse(next.get().picker.result(0x10002, Activity.RESULT_OK, Intent()))
            next.get().deliver(2, Intent().setData(Uri.parse("content://aiyou.synthetic/new")))
            assertEquals(1, freshCalls)
        } finally { next.destroy() }
    }
    @Test fun exhaustedCorruptAndMissingRestoredCountersFailClosedWithoutLaunching() {
        val lifecycle = Robolectric.buildActivity(HarnessActivity::class.java).setup()
        try {
            val activity = lifecycle.get()
            for (saved in listOf(Bundle(), Bundle().apply { putString(ActivityRecordPackagePicker.COUNTER, "wrong type") },
                Bundle().apply { putInt(ActivityRecordPackagePicker.COUNTER, 0) },
                Bundle().apply { putInt(ActivityRecordPackagePicker.COUNTER, 65536) })) {
                val broker = ActivityRecordPackagePicker(activity, saved); broker.resumed()
                assertFalse(broker.launch(false, "synthetic") { fail("Unexpected result") }); assertEquals(65536, broker.nextCode)
            }
            assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
        } finally { lifecycle.destroy() }
    }
    @Test fun finalCodeCancellationAndSaveBoundaryNeverReuseCodesOrLaunchAfterStateSaved() {
        val lifecycle = Robolectric.buildActivity(HarnessActivity::class.java).setup()
        try {
            val activity = lifecycle.get()
            val broker = ActivityRecordPackagePicker(activity, Bundle().apply { putInt(ActivityRecordPackagePicker.COUNTER, 65535) })
            broker.resumed(); assertTrue(broker.launch(false, "last") { fail("Cancelled result") }); broker.cancel("last")
            assertEquals(65536, broker.nextCode); assertFalse(broker.launch(false, "exhausted") {})
            assertTrue(broker.result(65535, Activity.RESULT_OK, Intent().setData(Uri.parse("content://aiyou.synthetic/late"))))
            val fresh = ActivityRecordPackagePicker(activity, null); fresh.resumed(); val saved = Bundle(); fresh.save(saved)
            assertFalse(fresh.launch(false, "after-save") {}); assertEquals(1, fresh.nextCode)
            fresh.resumed(); assertTrue(fresh.launch(false, "after-resume") {})
        } finally { lifecycle.destroy() }
    }
    @Test fun destroyedOwnerUnknownReservedResultsAndForeignSchemesNeverOpenOrReachRegistry() {
        val lifecycle = Robolectric.buildActivity(HarnessActivity::class.java).setup()
        try {
            val activity = lifecycle.get(); var delivered: SelectedRecordPackageDocument? = null; var calls = 0
            activity.picker.launch(false, "foreign") { calls++; delivered = it }
            activity.deliver(1, Intent().setData(Uri.parse("file:///private-placeholder")))
            assertEquals(1, calls); assertNull(delivered)
            assertTrue(activity.picker.result(123, Activity.RESULT_OK, Intent().setData(Uri.parse("content://aiyou.synthetic/unknown"))))
            activity.picker.launch(false, "destroyed") { fail("Retired owner callback") }
            activity.picker.destroy(); assertTrue(activity.picker.result(2, Activity.RESULT_OK, Intent().setData(Uri.parse("content://aiyou.synthetic/destroyed"))))
            val saved = Bundle(); lifecycle.saveInstanceState(saved)
            assertPrivateAbsent(saved, listOf("content://", "private-placeholder"))
        } finally { lifecycle.destroy() }
    }
    @Test fun selectedContentUriRemainsMemoryOnlyAcrossActualActivitySavedState() {
        val lifecycle = Robolectric.buildActivity(HarnessActivity::class.java).setup()
        try {
            val activity = lifecycle.get(); var selected: SelectedRecordPackageDocument? = null
            activity.picker.launch(false, "synthetic-selected-owner") { selected = it }
            activity.deliver(1, Intent().setData(Uri.parse("content://aiyou.synthetic/selected-private-uri")))
            assertNotNull(selected) // Keep the actual platform document and private Uri owner alive.
            val saved = Bundle(); lifecycle.pause().saveInstanceState(saved)
            assertPrivateAbsent(saved, listOf("content://", "selected-private-uri", "synthetic-selected-owner"))
            assertEquals(2, saved.getInt(ActivityRecordPackagePicker.COUNTER))
        } finally { lifecycle.destroy() }
    }
    @Suppress("DEPRECATION")
    private fun assertPrivateAbsent(value: Any?, forbidden: List<String>) {
        when (value) {
            null, is Number, is Boolean, is Char -> Unit
            is String -> forbidden.forEach { assertFalse("Private picker state entered SavedState", value.contains(it)) }
            is Bundle -> value.keySet().forEach { assertPrivateAbsent(it, forbidden); assertPrivateAbsent(value.get(it), forbidden) }
            is Map<*, *> -> value.forEach { (k,v) -> assertPrivateAbsent(k, forbidden); assertPrivateAbsent(v, forbidden) }
            is Iterable<*> -> value.forEach { assertPrivateAbsent(it, forbidden) }
            is Array<*> -> value.forEach { assertPrivateAbsent(it, forbidden) }
            is SparseArray<*> -> repeat(value.size()) { assertPrivateAbsent(value.keyAt(it), forbidden); assertPrivateAbsent(value.valueAt(it), forbidden) }
            is ByteArray -> forbidden.forEach { text -> for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                val marker = text.toByteArray(charset)
                assertFalse("Private Parcelable bytes entered SavedState", value.indices.any { at ->
                    at + marker.size <= value.size && marker.indices.all { value[at + it] == marker[it] } })
            } }
            is IntArray, is LongArray, is FloatArray, is DoubleArray, is BooleanArray -> Unit
            is Parcelable -> {
                val parcel = Parcel.obtain()
                try { parcel.writeParcelable(value, 0); assertPrivateAbsent(parcel.marshall(), forbidden) } finally { parcel.recycle() }
            }
            else -> fail("Uninspected SavedState type: " + value.javaClass.name)
        }
    }
    class HarnessActivity : ComponentActivity() {
        lateinit var picker: ActivityRecordPackagePicker
        override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); picker = ActivityRecordPackagePicker(this, savedInstanceState) }
        override fun onResume() { super.onResume(); picker.resumed() }
        override fun onPause() { picker.paused(); super.onPause() }
        override fun onSaveInstanceState(outState: Bundle) { picker.save(outState); super.onSaveInstanceState(outState) }
        override fun onDestroy() { picker.destroy(); super.onDestroy() }
        @Suppress("DEPRECATION")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (!picker.result(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
        }
        fun deliver(code: Int, data: Intent) = onActivityResult(code, Activity.RESULT_OK, data)
    }
}
