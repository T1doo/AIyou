package androidx.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Supplies a cancellable host scope; does not simulate Android lifecycle behavior. */
open class AndroidViewModel(private val application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = application as T
    protected open fun onCleared() {}
}
val AndroidViewModel.viewModelScope: CoroutineScope get() = scope
