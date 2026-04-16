package com.jarvis.assistant.camera

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A minimal LifecycleOwner that can be driven manually from a foreground service.
 * Used for headless CameraX capture without an Activity or Fragment.
 *
 * USAGE:
 *   val owner = ServiceLifecycleOwner()
 *   owner.markResumed()                  // before binding camera use cases
 *   provider.bindToLifecycle(owner, ...)
 *   ...capture...
 *   owner.markDestroyed()                // triggers automatic use-case unbind
 *
 * THREAD: All calls must be made on the main thread (CameraX requirement).
 *         CameraCaptureManager uses withContext(Dispatchers.Main) to enforce this.
 */
internal class ServiceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** Walk through the full CREATED → STARTED → RESUMED sequence before binding. */
    fun markResumed() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** Tear down through PAUSED → STOPPED → DESTROYED to unbind all use cases. */
    fun markDestroyed() {
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
