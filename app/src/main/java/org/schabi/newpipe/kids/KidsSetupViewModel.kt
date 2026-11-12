/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID
import java.util.concurrent.Executors

enum class KidsSetupStatus { READY, SAVING, COMPLETE, ERROR }

/** Keeps an in-flight setup operation across screen rotation. */
class KidsSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mutableStatus = MutableLiveData(KidsSetupStatus.READY)
    val status: LiveData<KidsSetupStatus> = mutableStatus

    fun createProfile(name: String, limit: Int, pin: String) {
        if (mutableStatus.value == KidsSetupStatus.SAVING || mutableStatus.value == KidsSetupStatus.COMPLETE) return
        mutableStatus.value = KidsSetupStatus.SAVING
        executor.execute {
            val result = runCatching {
                ParentSettings(KidsStorage.get(getApplication())).setup(pin, UUID.randomUUID().toString(), name, limit)
            }
            mutableStatus.postValue(if (result.isSuccess) KidsSetupStatus.COMPLETE else KidsSetupStatus.ERROR)
        }
    }

    override fun onCleared() {
        executor.shutdown()
        super.onCleared()
    }
}
