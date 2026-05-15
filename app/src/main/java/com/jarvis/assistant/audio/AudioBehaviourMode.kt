package com.jarvis.assistant.audio

/**
 * AudioBehaviourMode — the audio session state Jarvis is currently in.
 *
 * Only one mode is active at a time.  Transitions are driven by JarvisRuntime
 * state-machine changes; the enum itself carries no behaviour — it is a
 * diagnostic signal used for logging and the audio diagnostics screen.
 *
 * Android AudioManager implications:
 *   PASSIVE_LISTENING  — mode stays MODE_NORMAL; no audio focus held.
 *                        Spotify / media keeps playing at full volume.
 *   ASSISTANT_SPEAKING — focus held as AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
 *                        media apps duck briefly then resume.  Mode stays
 *                        MODE_NORMAL so TTS routes through the media path.
 *   MEDIA_FRIENDLY     — same as PASSIVE_LISTENING but explicitly flagged
 *                        when media was detected playing at session start.
 *   FULL_DUPLEX        — mic + speaker active simultaneously (barge-in
 *                        during speaking); mode MODE_NORMAL, AEC via
 *                        software (VOICE_RECOGNITION source in BargeInDetector).
 *   CALL_MODE          — cellular or VoIP call active; mode switches to
 *                        MODE_IN_COMMUNICATION via CallCoordinator.  Jarvis
 *                        releases audio focus so telephony can take over.
 */
enum class AudioBehaviourMode {
    /** Mic open, waiting for user speech. No audio focus held. */
    PASSIVE_LISTENING,

    /** Jarvis is producing TTS output. Audio focus held (MAY_DUCK). */
    ASSISTANT_SPEAKING,

    /** Mic open while media playback was detected at session start. */
    MEDIA_FRIENDLY,

    /** Barge-in active: mic and TTS both running simultaneously. */
    FULL_DUPLEX,

    /** Cellular or VoIP call. Jarvis releases audio focus entirely. */
    CALL_MODE,
}
