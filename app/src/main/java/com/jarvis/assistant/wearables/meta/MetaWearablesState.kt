package com.jarvis.assistant.wearables.meta

/**
 * MetaWearablesState — the single source of truth for the Meta
 * Wearables module's lifecycle.
 *
 * The state machine is intentionally linear-ish (no fan-out): each
 * state has a small, documented set of valid successors.  See the
 * KDoc on each value for the transitions [MetaWearablesManager]
 * allows.
 *
 * Mapping from state → user-visible label is owned by
 * [com.jarvis.assistant.wearables.meta.WearablesSettings] /
 * the Compose screen — this enum stays purely about runtime.
 */
enum class MetaWearablesState {
    /** User toggle is OFF in Settings.  The whole subsystem is dormant. */
    DISABLED,

    /**
     * The Meta DAT SDK isn't on the classpath (production builds before
     * the dependency lands, or aggressive R8 strip).  We log it once
     * and present "not connected" to the user.  This is the safe
     * default for the very first ship of the module.
     */
    SDK_UNAVAILABLE,

    /**
     * Toggle is ON, SDK present, but the developer App ID / config the
     * SDK requires hasn't been provided.  Settings prompt the user
     * (or developer) to fill it.
     */
    NOT_CONFIGURED,

    /** A required runtime permission (Bluetooth, Nearby Devices, Camera) is missing. */
    PERMISSION_MISSING,

    /** Configured + permitted but no live connection to a device. */
    DISCONNECTED,

    /** Connection attempt in flight. */
    CONNECTING,

    /** Connected; camera not yet opened. */
    CONNECTED,

    /** Connected AND camera session is open and ready for frames / photos. */
    CAMERA_READY,

    /** Camera session actively delivering frames (or recording video). */
    STREAMING,

    /** Active photo capture in progress (short-lived). */
    CAPTURING,

    /**
     * Recoverable failure.  The manager will surface a friendly
     * message via [com.jarvis.assistant.core.safety.UserSafeErrorHandler]
     * and (where possible) auto-retry on the next user action.
     */
    ERROR,
    ;

    /**
     * Convenience: does the current state imply the user can issue a
     * "look at this" / "take a glasses photo" command right now?
     */
    val isReadyForCapture: Boolean
        get() = this == CAMERA_READY || this == STREAMING

    /** Does the state allow a connect attempt? */
    val canConnect: Boolean
        get() = this == DISCONNECTED || this == ERROR

    /** Does the state allow a disconnect? */
    val canDisconnect: Boolean
        get() = this == CONNECTING || this == CONNECTED ||
            this == CAMERA_READY || this == STREAMING || this == CAPTURING
}
