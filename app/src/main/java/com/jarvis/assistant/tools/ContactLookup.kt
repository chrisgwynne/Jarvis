package com.jarvis.assistant.tools

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * ContactLookup — queries the device address book by display name.
 *
 * Lookup strategy (in order):
 *   1. SQL LIKE exact substring match (original behaviour — fast path)
 *   2. Jaro-Winkler fuzzy match against all contacts
 *      a. Single result with similarity > 0.85 → return it silently
 *      b. 2-3 results with similarity > 0.70 → return null, populate
 *         [lastAmbiguousMatches] so the caller can ask for disambiguation
 *      c. No results above 0.70 → return null (genuine miss)
 *
 * Prefers mobile numbers over home/work. On a tie, returns the first result
 * alphabetically. Returns null if permission is denied or no match found.
 */
class ContactLookup(private val context: Context) {

    companion object {
        private const val TAG = "ContactLookup"

        // Tuning constants
        private const val SILENT_THRESHOLD     = 0.85f   // single match: return without asking
        private const val AMBIGUOUS_THRESHOLD  = 0.70f   // 2-3 matches: ask user to disambiguate
        private const val TOP_N_RESULTS        = 3       // max candidates to surface
    }

    // -------------------------------------------------------------------------
    // Public data types
    // -------------------------------------------------------------------------

    /** Legacy type kept for backward-compatibility with existing callers. */
    data class Contact(val displayName: String, val number: String)

    /** Richer result type returned by [fuzzyLookup]. */
    data class ContactResult(
        val name: String,
        val phoneNumber: String,
        val similarity: Float
    ) {
        /** Convenience adapter for callers that expect the old [Contact] type. */
        fun toContact() = Contact(name, phoneNumber)
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /**
     * Populated when [find] (or [fuzzyLookup]) encounters 2-3 near-miss candidates.
     * Callers should check [disambiguationPrompt] after a null return from [find].
     */
    var lastAmbiguousMatches: List<ContactResult> = emptyList()
        private set

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Find the best-matching contact for [name] (partial, case-insensitive).
     *
     * Returns null if:
     *   - contacts permission was denied
     *   - no match found at all
     *   - 2-3 near-miss fuzzy matches exist (check [disambiguationPrompt])
     *
     * Side-effect: resets then potentially populates [lastAmbiguousMatches].
     */
    fun find(name: String): Contact? {
        lastAmbiguousMatches = emptyList()

        // --- Pass 1: SQL LIKE (original behaviour) ---
        val exact = likeQuery(name)
        if (exact != null) return exact

        // --- Pass 2: Jaro-Winkler fuzzy match ---
        val fuzzy = fuzzyLookup(name)

        return when {
            fuzzy.isEmpty() -> null

            // Single high-confidence match — return silently
            fuzzy.size == 1 && fuzzy[0].similarity >= SILENT_THRESHOLD ->
                fuzzy[0].toContact()

            // If the top result is above silent threshold even among multiple matches, use it
            fuzzy[0].similarity >= SILENT_THRESHOLD ->
                fuzzy[0].toContact()

            // 2-3 ambiguous matches — surface for disambiguation
            fuzzy.size in 2..TOP_N_RESULTS && fuzzy[0].similarity >= AMBIGUOUS_THRESHOLD -> {
                lastAmbiguousMatches = fuzzy
                null
            }

            // Single weak match above ambiguous threshold
            fuzzy.size == 1 && fuzzy[0].similarity >= AMBIGUOUS_THRESHOLD ->
                fuzzy[0].toContact()

            else -> null
        }
    }

    /**
     * Returns top [TOP_N_RESULTS] contacts whose Jaro-Winkler similarity to
     * [name] exceeds [AMBIGUOUS_THRESHOLD], sorted by score descending.
     *
     * This is a full-table scan and is only called after [likeQuery] misses.
     */
    fun fuzzyLookup(name: String): List<ContactResult> {
        val allContacts = loadAllContacts()
        val query = name.trim().lowercase()

        return allContacts
            .map { (displayName, number) ->
                val score = jaroWinkler(query, displayName.lowercase())
                ContactResult(displayName, number, score)
            }
            .filter { it.similarity >= AMBIGUOUS_THRESHOLD }
            .sortedByDescending { it.similarity }
            .take(TOP_N_RESULTS)
    }

    /**
     * Returns a natural-language disambiguation prompt if [lastAmbiguousMatches]
     * has 2-3 entries, or null otherwise.
     *
     * Example: "Did you mean Chris or Christine?"
     */
    fun disambiguationPrompt(): String? {
        val matches = lastAmbiguousMatches
        if (matches.size < 2) return null
        val names = matches.map { it.name }
        return when (names.size) {
            2    -> "Did you mean ${names[0]} or ${names[1]}?"
            else -> "Did you mean ${names.dropLast(1).joinToString(", ")}, or ${names.last()}?"
        }
    }

    /**
     * Picks the contact from [lastAmbiguousMatches] whose name exactly matches
     * [name] (case-insensitive). Returns null if no match or list is empty.
     *
     * Intended for the second turn of disambiguation dialogue:
     *   User: "Call Christine"  → find() returns null, prompt: "Did you mean Chris or Christine?"
     *   User: "Christine"       → selectAmbiguous("Christine") returns the right Contact
     */
    fun selectAmbiguous(name: String): ContactResult? {
        val q = name.trim().lowercase()
        return lastAmbiguousMatches.firstOrNull { it.name.lowercase() == q }
    }

    // -------------------------------------------------------------------------
    // Private helpers — queries
    // -------------------------------------------------------------------------

    /** Original SQL-LIKE query.  Returns the mobile-preferred match or null. */
    private fun likeQuery(name: String): Contact? {
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val cursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                proj,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission denied")
            return null
        } ?: return null

        var fallback: Contact? = null
        cursor.use {
            while (it.moveToNext()) {
                val displayName = it.getString(0) ?: continue
                val number      = it.getString(1)?.replace("\\s".toRegex(), "") ?: continue
                val type        = it.getInt(2)
                val contact     = Contact(displayName, number)
                if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) return contact
                if (fallback == null) fallback = contact
            }
        }
        return fallback
    }

    /**
     * Loads every contact (display name + best number) into memory for fuzzy scoring.
     * De-duplicates by display name, preferring mobile numbers.
     */
    private fun loadAllContacts(): List<Contact> {
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val cursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                proj,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission denied during fuzzy load")
            return emptyList()
        } ?: return emptyList()

        // name → Contact; mobile wins over any other type
        val map = linkedMapOf<String, Contact>()
        cursor.use {
            while (it.moveToNext()) {
                val name   = it.getString(0) ?: continue
                val number = it.getString(1)?.replace("\\s".toRegex(), "") ?: continue
                val type   = it.getInt(2)
                val isMobile = type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                if (!map.containsKey(name) || isMobile) {
                    map[name] = Contact(name, number)
                }
            }
        }
        return map.values.toList()
    }

    // -------------------------------------------------------------------------
    // Private helpers — Jaro-Winkler similarity (no external library)
    // -------------------------------------------------------------------------

    /**
     * Computes the Jaro-Winkler similarity between two strings.
     * Returns a value in [0.0, 1.0] where 1.0 is a perfect match.
     *
     * Implementation follows the standard Jaro-Winkler algorithm:
     *   sim_jw = sim_j + p * l * (1 - sim_j)
     * where p = 0.1 (standard prefix scale) and l = length of common prefix (max 4).
     */
    private fun jaroWinkler(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f

        val matchWindow = max(s1.length, s2.length) / 2 - 1
        if (matchWindow < 0) {
            // Strings are length 1 — check directly
            return if (s1[0] == s2[0]) 1.0f else 0.0f
        }

        val s1Matched = BooleanArray(s1.length)
        val s2Matched = BooleanArray(s2.length)

        var matches = 0
        var transpositions = 0

        // Count matches
        for (i in s1.indices) {
            val start = max(0, i - matchWindow)
            val end   = min(i + matchWindow + 1, s2.length)
            for (j in start until end) {
                if (s2Matched[j] || s1[i] != s2[j]) continue
                s1Matched[i] = true
                s2Matched[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0f

        // Count transpositions
        var k = 0
        for (i in s1.indices) {
            if (!s1Matched[i]) continue
            while (!s2Matched[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val jaro = (matches.toFloat() / s1.length +
                    matches.toFloat() / s2.length +
                    (matches - transpositions / 2.0f) / matches) / 3.0f

        // Winkler prefix bonus (up to 4 chars, scale p = 0.1)
        var prefixLen = 0
        val maxPrefix = min(4, min(s1.length, s2.length))
        while (prefixLen < maxPrefix && s1[prefixLen] == s2[prefixLen]) prefixLen++

        return jaro + prefixLen * 0.1f * (1.0f - jaro)
    }
}
