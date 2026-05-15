package com.jarvis.assistant.wearables.meta

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StubMetaWearablesProvider — the fail-safe provider returned when
 * the Meta DAT SDK is not present on the classpath.
 *
 * Every call resolves quickly with no side effects.  The state stays
 * pinned at [MetaWearablesState.SDK_UNAVAILABLE] so the Settings
 * screen renders "SDK not available — install the Meta Wearables DAT
 * dependency" and `LookAtThisWearableTool` falls back to the phone
 * camera path.
 *
 * This is the contract that lets the rest of Jarvis treat the
 * wearables module as optional today, before the real SDK dependency
 * lands.
 */
class StubMetaWearablesProvider :
    WearableDeviceProvider,
    WearableCameraProvider,
    WearableContextProvider {

    private val _stateFlow = MutableStateFlow(MetaWearablesState.SDK_UNAVAILABLE)
    override val stateFlow: StateFlow<MetaWearablesState> = _stateFlow.asStateFlow()
    override val currentState: MetaWearablesState get() = _stateFlow.value

    override val deviceName: String? = null
    override val batteryPercent: Int? = null
    override val lastError: String? = "SDK not present"

    init {
        Log.d(TAG, "[META_WEARABLES_INIT] backend=stub state=SDK_UNAVAILABLE")
    }

    override suspend fun connect(): Boolean {
        Log.d(TAG, "[META_WEARABLES_CONNECT_START] backend=stub → ignored (SDK absent)")
        return false
    }

    override suspend fun disconnect() = Unit

    override suspend fun startCameraSession(): Boolean = false
    override suspend fun stopCameraSession() = Unit
    override suspend fun capturePhoto(): String? = null
    override suspend fun startStream(
        onFrame: (ByteArray, Int, Int) -> Boolean,
    ): Boolean = false
    override suspend fun stopStream() = Unit

    override fun peekRecent(): RecentVisualContext? = null
    override fun clearRecent() = Unit

    companion object { private const val TAG = "MetaWearablesStub" }
}
