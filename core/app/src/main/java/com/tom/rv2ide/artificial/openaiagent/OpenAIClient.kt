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

import com.tom.rv2ide.artificial.agentloop.AgentMessage
import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Minimal OpenAI-compatible client used by the coding agent.
 *
 * It deliberately has no third party dependency: one `chat/completions` POST (optionally streamed
 * over server sent events) and one `models` GET. Errors are mapped to the app's existing AI
 * exceptions so the provider switcher keeps working.
 */
class OpenAIClient {

  /**
   * A piece of a streamed answer. [reasoning] is true for "thinking" text which some models
   * (OpenAI o-series, DeepSeek, OpenRouter) send separately from the answer.
   */
  data class ChatChunk(val text: String, val reasoning: Boolean)

  private val json = Json {
    isLenient = true
    ignoreUnknownKeys = true
  }

  /** Endpoint which does not support server sent events; the request is retried without it. */
  private class StreamUnsupported : Exception()

  /** Lists the model ids the endpoint advertises. Returns an empty list when it has none. */
  fun listModels(config: OpenAIAgentConfig): List<String> {
    val connection = openConnection(config.modelsUrl)
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/json")
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS

    try {
      val code = connection.responseCode
      val text = readBody(connection, code)
      if (code !in 200..299) throw mapError(code, text)

      val root =
          try {
            json.parseToJsonElement(text).jsonObject
          } catch (e: Exception) {
            throw Exception("The endpoint returned an invalid model list: ${text.take(300)}")
          }

      val data = root["data"] as? JsonArray ?: return emptyList()
      return data
          .mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
          }
          .distinct()
          .sorted()
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Sends the conversation and returns the assistant answer. When [onChunk] is given and
   * [OpenAIAgentConfig.stream] is enabled the answer is streamed; otherwise a single request is
   * made and the complete answer is returned.
   */
  fun chat(
      config: OpenAIAgentConfig,
      messages: List<AgentMessage>,
      onChunk: ((ChatChunk) -> Unit)? = null,
  ): String {
    val shouldStream = config.stream && onChunk != null
    val body = requestBody(config, messages, shouldStream)

    return try {
      execute(config, body, shouldStream, onChunk)
    } catch (e: StreamUnsupported) {
      // Some OpenAI-compatible servers reject `stream: true`; ask once more without it.
      execute(config, requestBody(config, messages, stream = false), stream = false, onChunk = null)
    }
  }

  private fun requestBody(
      config: OpenAIAgentConfig,
      messages: List<AgentMessage>,
      stream: Boolean,
  ): String =
      buildJsonObject {
            put("model", config.model)
            put(
                "messages",
                buildJsonArray {
                  messages.forEach { message ->
                    add(
                        buildJsonObject {
                          put("role", message.role)
                          put("content", message.content)
                        })
                  }
                })
            if (isReasoningModel(config.model)) {
              // The o-series and GPT-5 reject `temperature` and use `max_completion_tokens`.
              put("max_completion_tokens", config.maxTokens)
            } else {
              put("temperature", config.temperature)
              put("max_tokens", config.maxTokens)
            }
            put("stream", stream)
          }
          .toString()

  private fun execute(
      config: OpenAIAgentConfig,
      body: String,
      stream: Boolean,
      onChunk: ((ChatChunk) -> Unit)?,
  ): String {
    val connection = openConnection(config.chatCompletionsUrl)
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = if (stream) STREAM_READ_TIMEOUT_MS else READ_TIMEOUT_MS
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
    if (config.apiKey.isNotBlank()) {
      connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
    }

    try {
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode

      if (code !in 200..299) {
        val error = readBody(connection, code)
        if (stream && code == 400) throw StreamUnsupported()
        throw mapError(code, error)
      }

      if (!stream) {
        val text = readBody(connection, code)
        val root =
            try {
              json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
              throw Exception("The endpoint returned an invalid answer: ${text.take(300)}")
            }
        val message = firstChoice(root)?.get("message") as? JsonObject
        return (message?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
      }

      return readStream(connection, onChunk)
    } finally {
      connection.disconnect()
    }
  }

  private fun readStream(
      connection: HttpURLConnection,
      onChunk: ((ChatChunk) -> Unit)?,
  ): String {
    val content = StringBuilder()
    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
      while (true) {
        val line = reader.readLine() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
        if (!trimmed.startsWith("data:")) continue

        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") break

        val root =
            try {
              json.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
              continue
            }
        val delta = firstChoice(root)?.get("delta") as? JsonObject ?: continue

        val reasoning =
            (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
        if (!reasoning.isNullOrEmpty()) {
          onChunk?.invoke(ChatChunk(reasoning, reasoning = true))
        }

        val piece = (delta["content"] as? JsonPrimitive)?.contentOrNull
        if (!piece.isNullOrEmpty()) {
          content.append(piece)
          onChunk?.invoke(ChatChunk(piece, reasoning = false))
        }
      }
    }
    return content.toString().trim()
  }

  private fun firstChoice(root: JsonObject): JsonObject? =
      (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject

  private fun openConnection(url: String): HttpURLConnection =
      URL(url).openConnection() as HttpURLConnection

  private fun readBody(connection: HttpURLConnection, code: Int): String =
      try {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      } catch (e: Exception) {
        ""
      }

  /** Maps an HTTP error to one of the app's AI exceptions, keeping the provider switch usable. */
  private fun mapError(code: Int, body: String): Exception {
    val message =
        try {
          val root = json.parseToJsonElement(body).jsonObject
          val error = root["error"]
          if (error is JsonObject) {
            (error["message"] as? JsonPrimitive)?.contentOrNull ?: body.take(500)
          } else {
            ((root["error"] as? JsonPrimitive)?.contentOrNull)
                ?: ((root["message"] as? JsonPrimitive)?.contentOrNull)
                ?: body.take(500)
          }
        } catch (e: Exception) {
          body.take(500)
        }

    val lower = message.lowercase()
    return when {
      lower.contains("insufficient_quota") ||
          lower.contains("insufficient balance") ||
          lower.contains("billing") ->
          QuotaExceededException(message)
      lower.contains("balance") -> InsufficientBalanceException(message)
      code == 401 || code == 403 || lower.contains("invalid_api_key") ->
          InvalidApiKeyException(message)
      code == 429 || lower.contains("rate limit") -> RateLimitException(message)
      else -> Exception("OpenAI API error ($code): $message")
    }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 120_000
    const val STREAM_READ_TIMEOUT_MS = 180_000

    /** Models which reject `temperature` / use `max_completion_tokens`. */
    fun isReasoningModel(model: String): Boolean {
      val name = model.substringAfterLast('/').lowercase()
      return name.startsWith("o1") ||
          name.startsWith("o3") ||
          name.startsWith("o4") ||
          name.startsWith("gpt-5") ||
          name.startsWith("gpt5")
    }
  }
}
