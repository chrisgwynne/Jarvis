package com.jarvis.assistant.security

enum class DenialReason(val code: String, val description: String) {
    TOOL_NOT_IN_ALLOWLIST("TOOL_NOT_IN_ALLOWLIST", "Tool name has no approved ActionType mapping"),
    MISSING_REQUIRED_PARAM("MISSING_REQUIRED_PARAM", "A required parameter was absent or blank"),
    UNSAFE_PARAM_VALUE("UNSAFE_PARAM_VALUE", "A parameter value failed safety validation"),
    UNSAFE_STORAGE_PATH("UNSAFE_STORAGE_PATH", "Requested path is outside approved storage boundaries"),
    NETWORK_TOOL_OFFLINE("NETWORK_TOOL_OFFLINE", "Network tool requested while device is offline"),
    PERMISSION_DENIED("PERMISSION_DENIED", "Required Android permission was not granted"),
    DISABLED_BY_POLICY("DISABLED_BY_POLICY", "Action type is explicitly disabled in current policy")
}
