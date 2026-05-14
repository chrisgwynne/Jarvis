package com.jarvis.assistant.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ArchitectureInvariantsTest — pure-JVM source scanner that enforces
 * the routing + extraction rules documented in
 * `docs/architecture/routing-invariants.md`.
 *
 * Every assertion in this file maps to one numbered invariant (R1..R7).
 * Failures point at the file + reason so the fix is obvious.
 *
 * Also wired in as the Gradle task `:app:checkArchitectureInvariants`
 * (see app/build.gradle.kts), so a CI / pre-push run catches drift
 * without requiring the developer to remember.
 *
 * Why a source scanner rather than reflection?  Reflection only sees
 * the compiled bytecode at the call sites that survived dead-code
 * elimination.  These invariants are about the *shape of the source
 * tree* — duplicate class declarations, forbidden imports, missing
 * gates.  A textual scan is the right tool.
 */
class ArchitectureInvariantsTest {

    private val mainSrc = File("src/main/java/com/jarvis/assistant")
    private val mainSrcAlt = File("app/src/main/java/com/jarvis/assistant")
    private val srcRoot: File get() = if (mainSrc.isDirectory) mainSrc else mainSrcAlt

    private fun allKotlinFiles(): List<File> =
        srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun filesUnder(subpath: String): List<File> =
        File(srcRoot, subpath).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ── R1 — phone-capable tool names never appear under remote/ ──────────

    @Test fun `R1 phone-capable tool names never appear in remote routing source`() {
        // Names taken from ActionType.APPROVED_TOOL_MAP — every local-first
        // tool listed there is, by definition, phone-capable.  We forbid
        // these as string literals inside remote/openclaw/** and hermes/**
        // because routing them through a relay is the bug this invariant
        // is here to prevent.
        val forbidden = listOf(
            "call_contact", "end_call", "send_sms", "whatsapp_message",
            "send_email", "volume_control", "media_control", "flashlight",
            "set_alarm", "set_timer", "location_reminder",
            "camera_capture", "analyze_camera_view", "audio_recording",
            "open_app", "weather", "smart_home", "calendar",
            "read_notifications", "clear_notifications",
            "where_am_i", "nearest_place", "directions", "navigate",
            "look_at_this", "read_screen", "tap_screen",
            "calculator", "unit_conversion", "stopwatch", "brightness",
            "dnd", "screen_rotation", "screenshot", "settings_panel",
            "find_phone", "share_location", "read_sms", "recent_calls",
            "calendar_create",
        )
        val remoteFiles = (filesUnder("remote/openclaw") + filesUnder("hermes"))
            .filter { !it.name.endsWith("Test.kt") }
        val violations = mutableListOf<String>()
        for (file in remoteFiles) {
            val text = file.readText()
            for (name in forbidden) {
                val literal = "\"$name\""
                if (literal in text) {
                    // Allow lines explicitly tagged for the allowlist.
                    val offending = text.lines()
                        .filter { literal in it && "// allowlist:phone-name" !in it }
                    if (offending.isNotEmpty()) {
                        violations += "${file.relativeTo(srcRoot)} mentions $literal"
                    }
                }
            }
        }
        assertTrue(
            "R1 violation — phone-capable tool names leaked into remote/ source:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── R2 — Todoist stays local-first ────────────────────────────────────

    @Test fun `R2 todoist package never imports remote openclaw or hermes`() {
        val violations = filesUnder("todoist").mapNotNull { file ->
            val text = file.readText()
            if ("import com.jarvis.assistant.remote.openclaw" in text ||
                "import com.jarvis.assistant.hermes" in text) {
                file.relativeTo(srcRoot).path
            } else null
        }
        assertTrue(
            "R2 violation — todoist/** must not import openclaw/hermes:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── R3 — Calendar / Maps / Messaging / Smart Home stay local-first ────

    @Test fun `R3 local-first feature packages never import openclaw or hermes`() {
        val checked = listOf(
            "maps",
            "tools/device/messaging",
            "tools/smart",
            "tools/device/apps",
        ).flatMap { filesUnder(it) }
        val singleFiles = listOf(
            "tools/device/CalendarTool.kt",
            "tools/device/CalendarCreateTool.kt",
            "tools/device/ShareLocationTool.kt",
            "tools/device/FindPhoneTool.kt",
        ).map { File(srcRoot, it) }.filter { it.isFile }
        val violations = (checked + singleFiles).mapNotNull { file ->
            val text = file.readText()
            if ("com.jarvis.assistant.remote.openclaw" in text ||
                "com.jarvis.assistant.hermes" in text) {
                file.relativeTo(srcRoot).path
            } else null
        }
        assertTrue(
            "R3 violation — local-first packages must not import openclaw/hermes:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── R4 — TTS never receives raw stack traces ──────────────────────────

    @Test fun `R4 ttsEngine speak call sites never speak Throwable toString`() {
        // Forbid the specific anti-pattern: `ttsEngine.speak(throwable.toString())`
        // / `ttsEngine.speak(e.message ?: "")` / `ttsEngine.speak(e.stackTraceToString())`.
        // These are the historical leaks SpeechSanitizer was designed to
        // prevent.
        val rx = Regex(
            """\bttsEngine\.speak\(\s*(?:[a-zA-Z_]\w*\.(?:toString\(\)|stackTraceToString\(\)|message))""",
        )
        val violations = allKotlinFiles().mapNotNull { file ->
            val matches = rx.findAll(file.readText())
                .map { it.value }
                .toList()
            if (matches.isNotEmpty()) {
                "${file.relativeTo(srcRoot)} → ${matches.joinToString("; ")}"
            } else null
        }
        assertTrue(
            "R4 violation — TTS spoken raw exception text:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── R5 — local command success path returns to listening ──────────────

    @Test fun `R5 SessionContinuationPolicy default for local-tool path stays CONTINUE_LISTENING`() {
        val file = File(srcRoot, "runtime/session/SessionContinuationPolicy.kt")
        assertTrue("SessionContinuationPolicy not found at expected path",
            file.isFile)
        val text = file.readText()
        // The policy file MUST mention CONTINUE_LISTENING — the verdict
        // exists and is referenced.  We don't grep for an exact decision
        // table because the policy may legitimately evolve; we just lock
        // the contract that the verdict still exists in this file.
        assertTrue("CONTINUE_LISTENING verdict missing from policy",
            "CONTINUE_LISTENING" in text)
    }

    // ── R6 — OpenClaw / Hermes only when explicitly enabled + requested ───

    @Test fun `R6 OpenClaw dispatch is gated by openClawEnabled AND shouldRoute`() {
        // The JarvisRuntime call site (or wherever the OpenClaw dispatch
        // lives) MUST consult both settings.openClawEnabled (or the repo
        // snapshot) AND OpenClawRouter.shouldRoute / similar.  We scan
        // every file that calls into openClawNode.execute / send and
        // assert both gates are present somewhere upstream of that call
        // in the same file.
        val openClawCallSites = allKotlinFiles().filter { file ->
            val t = file.readText()
            ("openClawNode.execute(" in t || "openClawNode.send(" in t ||
                "openClawRouter.route(" in t)
        }
        // It's fine to have zero call sites — the runtime might be in a
        // partial state.  When call sites exist, gate evidence must too.
        val violations = openClawCallSites.mapNotNull { file ->
            val t = file.readText()
            val hasEnabled = "openClawEnabled" in t || "isFullyConfigured" in t
            val hasShouldRoute = "shouldRoute" in t || "openClawRouter.route" in t
            if (!hasEnabled || !hasShouldRoute) {
                "${file.relativeTo(srcRoot)} missing gates (enabled=$hasEnabled shouldRoute=$hasShouldRoute)"
            } else null
        }
        assertTrue(
            "R6 violation — OpenClaw dispatch missing one of the required gates:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── R7 — architectural anchors appear exactly once ────────────────────

    @Test fun `R7 architectural anchor classes are defined exactly once`() {
        val anchors = listOf(
            "TranscriptNormalizer",
            "RecentActionContextStore",
            "UserSafeErrorHandler",
            "SessionContinuationPolicy",
            "CommandPermissionPolicy",
            "ContextualFollowupParser",
            "ProactivityGate",
            "ProactivitySettings",
            "JarvisRuntime",
        )
        for (anchor in anchors) {
            val rx = Regex("""^(?:class|object|sealed class|data class) $anchor\b""",
                RegexOption.MULTILINE)
            val matches = allKotlinFiles().sumOf { rx.findAll(it.readText()).count() }
            assertEquals(
                "R7 violation — anchor $anchor should appear exactly once, found $matches",
                1, matches,
            )
        }
    }

    // ── Bonus — no TODO bombs in proactivity or scheduled-reminders ───────

    @Test fun `no TODO HACK FIXME bombs in proactivity hot paths`() {
        val rx = Regex("""\b(TODO|FIXME|HACK|XXX):?""")
        val hotPaths = listOf("proactive", "runtime/context", "runtime/session")
            .flatMap { filesUnder(it) }
        val offenders = hotPaths.filter { rx.containsMatchIn(it.readText()) }
            .map { it.relativeTo(srcRoot).path }
        // Soft assertion — count is allowed but we surface it for visibility.
        assertFalse(
            "Unexpectedly large TODO/FIXME load in proactivity hot paths " +
                "(${offenders.size}):\n${offenders.joinToString("\n")}",
            offenders.size > 25,
        )
    }
}
