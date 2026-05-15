package com.jarvis.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * AudioFocusManager — requests and relinquishes Android audio focus.
 *
 * WHY AUDIO FOCUS?
 *   Audio focus is Android's cooperative multi-app audio contract.
 *   Holding AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK while Jarvis speaks tells
 *   media apps (Spotify, podcasts) to duck briefly, then resume at full
 *   volume once focus is released — the same behaviour as Google Assistant.
 *   Focus is NOT held during passive listening so Spotify keeps playing
 *   at full volume while the user is being heard.
 *
 * FOCUS LOSS HANDLING:
 *   Any focus loss (phone call, navigation prompt, etc.) fires [onFocusLost].
 *   JarvisRuntime connects this to silence() so the pipeline cleans up
 *   instead of fighting another app for the mic/speaker.
 *
 * LIFECYCLE:
 *   requestFocus()  — call just before TTS starts speaking
 *   abandonFocus()  — call immediately after TTS finishes (or on silence/release)
 */
class AudioFocusManager(
    context: Context,
    private val onFocusLost: (Boolean) -> Unit
) {
    companion object { private const val TAG = "AudioFocusManager" }

    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    val isHoldingFocus: Boolean get() = hasFocus

    /**
     * Request AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK.
     *
     * MAY_DUCK: media apps lower their volume while Jarvis speaks, then
     * resume at full volume after [abandonFocus] is called.  This is
     * intentionally NOT EXCLUSIVE — Spotify must not be paused.
     * Returns true if focus was granted or will be granted (delayed grant).
     * Returns false only on a hard failure (shouldn't happen in practice).
     */
    fun requestFocus(): Boolean {
        if (hasFocus) return true

        // Abandon any previous request that wasn't properly released (e.g. after
        // transient focus loss where abandonFocus() was not called).  Without this
        // the old focusRequest stays registered and its listener continues firing.
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null

        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
            Log.d(TAG, "Focus change: $change")
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    onFocusLost(false) // Permanent loss
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // We treat transient loss as a temporary suspension.
                    // We trigger onFocusLost(true) so the runtime can decide whether to silence
                    hasFocus = false
                    onFocusLost(true) // Transient loss
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    Log.d(TAG, "Focus gained")
                }
            }
        }

        // MAY_DUCK instead of the plain TRANSIENT: this asks music/podcast apps to
        // lower their volume while Jarvis speaks (ducking) rather than pausing
        // entirely.  Spotify and most media apps respect CAN_DUCK and resume at
        // full volume once we release focus — the same behaviour as Google Assistant.
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)   // we handle ducking ourselves; don't auto-pause
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        focusRequest = req
        val result = audioManager.requestAudioFocus(req)
        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        Log.d(TAG, "requestAudioFocus → $result  hasFocus=$hasFocus")
        return result != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    /**
     * Relinquish audio focus so other apps (media, navigation, etc.) can resume.
     * Safe to call multiple times or when focus was never held.
     */
    fun abandonFocus() {
        val req = focusRequest ?: return
        audioManager.abandonAudioFocusRequest(req)
        focusRequest = null
        hasFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }
}
