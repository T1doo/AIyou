package androidx.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Executes product disposal with real coroutines; this does not simulate Android lifecycle. */
open class AndroidViewModel(private val application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = application as T
    protected open fun onCleared() {}
    fun clearForHostTest() { scope.cancel(); onCleared() }
}
val AndroidViewModel.viewModelScope: CoroutineScope get() = scope
