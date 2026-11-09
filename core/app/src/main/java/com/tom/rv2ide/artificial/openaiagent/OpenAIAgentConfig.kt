/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.openaiagent

import com.tom.rv2ide.preferences.internal.prefManager

/**
 * Connection settings for the OpenAI (and OpenAI-compatible) agent.
 *
 * The agent speaks the plain `chat/completions` protocol, so the same settings also drive local
 * servers (Ollama, LM Studio, vLLM) and third party gateways (OpenRouter, DeepSeek, ...) which
 * expose an OpenAI-compatible endpoint.
 */
data class OpenAIAgentConfig(
    val baseUrl: String = OpenAIAgentSettings.DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = OpenAIAgentSettings.DEFAULT_MODEL,
    val temperature: Double = 0.7,
    val maxTokens: Int = OpenAIAgentSettings.DEFAULT_MAX_TOKENS,
    val stream: Boolean = true,
) {

  /** Full URL used for a chat completion. */
  val chatCompletionsUrl: String
    get() = OpenAIAgentSettings.normalizeBaseUrl(baseUrl) + "/chat/completions"

  /** Full URL used to list the models the endpoint offers. */
  val modelsUrl: String
    get() = OpenAIAgentSettings.normalizeBaseUrl(baseUrl) + "/models"

  /**
   * A key is only required for the official OpenAI endpoint; local servers usually run without
   * one. Everything else (model + reachable base URL) must always be present.
   */
  val canRequest: Boolean
    get() =
        model.isNotBlank() &&
            baseUrl.isNotBlank() &&
            (apiKey.isNotBlank() ||
                !OpenAIAgentSettings.normalizeBaseUrl(baseUrl).contains("api.openai.com"))
}

/** Persists [OpenAIAgentConfig] in the app's default preferences. */
object OpenAIAgentSettings {

  const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
  const val DEFAULT_MODEL = "gpt-4o"
  const val DEFAULT_MAX_TOKENS = 8192

  /** Same key the AI preferences screen uses, so both entry points share one API key. */
  const val KEY_API_KEY = "ai_agent_openai_api_key"
  const val KEY_BASE_URL = "openai_agent_base_url"
  const val KEY_STREAM = "openai_agent_stream"

  // Shared with com.tom.rv2ide.artificial.agents.Agents so the AI settings model picker and this
  // sheet always show the same provider and model.
  private const val KEY_PROVIDER = "ai_provider_name"
  private const val KEY_MODEL = "ai_agent_model_name"

  /** Matches `/v1`, `/v1beta`, `/v4`, ... so the version segment is never added twice. */
  private val versionSegment = Regex("/v\\d+[A-Za-z0-9]*")

  /**
   * Turns whatever the user typed into an API base URL: adds `https://` when the scheme is
   * missing and `/v1` when the path has no version segment.
   */
  fun normalizeBaseUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    if (url.isEmpty()) return DEFAULT_BASE_URL
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      url = "https://$url"
    }
    if (!versionSegment.containsMatchIn(url)) {
      url = "$url/v1"
    }
    return url
  }

  fun config(fallbackApiKey: String? = null): OpenAIAgentConfig {
    val savedKey = prefManager.getString(KEY_API_KEY, "")
    val provider = prefManager.getString(KEY_PROVIDER, "gemini")
    val savedModel = prefManager.getString(KEY_MODEL, "")
    // Only reuse the app wide model when OpenAI is the active provider; otherwise the picker
    // still holds e.g. a Gemini model which the OpenAI endpoint would reject.
    val model = if (provider == "openai" && savedModel.isNotBlank()) savedModel else DEFAULT_MODEL
    return OpenAIAgentConfig(
        baseUrl = prefManager.getString(KEY_BASE_URL, DEFAULT_BASE_URL),
        apiKey = savedKey.ifBlank { fallbackApiKey.orEmpty() },
        model = model,
        stream = prefManager.getBoolean(KEY_STREAM, true),
    )
  }

  fun save(config: OpenAIAgentConfig) {
    prefManager.putString(KEY_BASE_URL, normalizeBaseUrl(config.baseUrl))
    prefManager.putString(KEY_PROVIDER, "openai")
    prefManager.putString(KEY_MODEL, config.model.trim())
    prefManager.putBoolean(KEY_STREAM, config.stream)
    if (config.apiKey.isNotBlank()) {
      prefManager.putString(KEY_API_KEY, config.apiKey.trim())
    }
  }
}
