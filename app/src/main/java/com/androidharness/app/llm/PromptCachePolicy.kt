package com.androidharness.app.llm

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Optional session routing hints are endpoint-specific, not an OpenAI-compatible capability. */
internal object PromptCachePolicy {
    // OpenAI documents prompt_cache_key; OpenRouter accepts it for sticky routing:
    // https://developers.openai.com/api/docs/guides/prompt-caching
    // https://openrouter.ai/docs/guides/best-practices/prompt-caching
    // Other gateways may cache prompts automatically but reject these request fields (#42).
    fun supportsKey(baseUrl: String): Boolean = when (baseUrl.toHttpUrlOrNull()?.host) {
        "api.openai.com", "openrouter.ai" -> true
        else -> false
    }

    /**
     * Whether requests to this endpoint keep a byte-stable prefix: a frozen system prompt,
     * untrimmed history, and per-request context appended at the end. DeepSeek bills input
     * tokens that repeat the start of a recent request at its much cheaper cache-hit price
     * (https://api-docs.deepseek.com/guides/kv_cache), so any change near the start of a
     * request re-bills the whole conversation at the full price.
     */
    fun prefixStable(baseUrl: String): Boolean =
        baseUrl.toHttpUrlOrNull()?.host?.let { it == "deepseek.com" || it.endsWith(".deepseek.com") } == true
}
