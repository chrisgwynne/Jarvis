package com.jarvis.assistant.auth

import android.net.Uri
import android.util.Base64
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.NetworkClient
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OpenAI OAuth 2.0 PKCE helper.
 *
 * FLOW:
 *   1. Call generateCodeVerifier() — random 32-byte secret, base64url-encoded.
 *   2. Call generateCodeChallenge(verifier) — SHA-256 of verifier, base64url-encoded.
 *   3. Build the authorization URI with buildAuthUri() and open it in a browser.
 *   4. OpenAI redirects to com.jarvis.assistant://oauth/callback?code=...
 *   5. Call exchangeCode() with the code and the original verifier to get tokens.
 *
 * REQUIREMENTS:
 *   The user must register an OAuth app at platform.openai.com and provide the
 *   resulting client_id in Jarvis settings. The redirect URI they register must
 *   be exactly: com.jarvis.assistant://oauth/callback
 *
 * TOKEN USAGE:
 *   The returned access_token is used as a standard Bearer token in API calls —
 *   identical to how an API key is used. OpenAI accepts both.
 */
object OpenAiOAuthManager {

    const val REDIRECT_URI = "com.jarvis.assistant://oauth/callback"
    private const val AUTH_URL  = "https://auth.openai.com/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    private const val SCOPE     = "openid email profile offline_access"

    // ── PKCE helpers ──────────────────────────────────────────────────────────

    /** Random 32-byte verifier, base64url-encoded (no padding). */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    /** S256 challenge: SHA-256(verifier), base64url-encoded. */
    fun generateCodeChallenge(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            hash,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    // ── URI builder ───────────────────────────────────────────────────────────

    fun buildAuthUri(clientId: String, codeChallenge: String, state: String): Uri =
        Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id",             clientId)
            .appendQueryParameter("response_type",         "code")
            .appendQueryParameter("redirect_uri",          REDIRECT_URI)
            .appendQueryParameter("scope",                 SCOPE)
            .appendQueryParameter("code_challenge",        codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state",                 state)
            .build()

    // ── Token exchange ────────────────────────────────────────────────────────

    /**
     * Exchange an authorization code for an access token + refresh token.
     * Returns Pair(access_token, refresh_token).
     */
    suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        clientId: String
    ): Pair<String, String> {
        val formBody = "grant_type=authorization_code" +
            "&code=${Uri.encode(code)}" +
            "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
            "&client_id=${Uri.encode(clientId)}" +
            "&code_verifier=${Uri.encode(codeVerifier)}"

        val responseBody = NetworkClient.postForm(TOKEN_URL, formBody)
        val json = NetworkClient.gson.fromJson(responseBody, TokenResponse::class.java)

        val access = json.access_token
            ?: throw LlmException("OAuth token exchange failed — no access_token returned.")
        return Pair(access, json.refresh_token ?: "")
    }

    /**
     * Refresh an expired access token.
     * Returns Pair(new_access_token, new_refresh_token).
     */
    suspend fun refreshToken(
        refreshToken: String,
        clientId: String
    ): Pair<String, String> {
        val formBody = "grant_type=refresh_token" +
            "&refresh_token=${Uri.encode(refreshToken)}" +
            "&client_id=${Uri.encode(clientId)}"

        val responseBody = NetworkClient.postForm(TOKEN_URL, formBody)
        val json = NetworkClient.gson.fromJson(responseBody, TokenResponse::class.java)

        val access = json.access_token
            ?: throw LlmException("OAuth token refresh failed.")
        return Pair(access, json.refresh_token ?: refreshToken)
    }

    // ── Wire format ───────────────────────────────────────────────────────────

    private data class TokenResponse(
        val access_token: String?,
        val refresh_token: String?,
        val token_type: String?,
        val expires_in: Int?
    )
}
