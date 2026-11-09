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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agentloop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single action requested by the model. The protocol is plain JSON:
 *
 * ```json
 * {"tool": "read_file", "path": "app/src/main/AndroidManifest.xml"}
 * ```
 *
 * Both `tool` and `action` are accepted as the name field, and the arguments may either be at the
 * top level or inside an `args`/`arguments` object, because different models produce slightly
 * different shapes.
 */
data class AgentAction(val tool: String, val args: JsonObject) {

  fun string(name: String): String? {
    val element = args[name] ?: return null
    return (element as? JsonPrimitive)?.contentOrNull
  }

  fun int(name: String): Int? = (args[name] as? JsonPrimitive)?.intOrNull

  fun boolean(name: String): Boolean? = (args[name] as? JsonPrimitive)?.booleanOrNull

  fun stringList(name: String): List<String>? {
    val element = args[name] ?: return null
    return try {
      element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    } catch (e: Exception) {
      string(name)?.let { listOf(it) }
    }
  }

  /** Short, human readable preview for the UI. */
  fun preview(): String {
    val values = args.entries.take(3).joinToString(", ") { (key, value) ->
      val text =
          try {
            value.jsonPrimitive.contentOrNull ?: value.toString()
          } catch (e: Exception) {
            value.toString()
          }
      "$key=${text.take(120)}"
    }
    return values.ifBlank { tool }
  }
}

object AgentProtocol {

  private val json = Json { isLenient = true }

  private val fenceRegex =
      Regex("```(?:json)?\\s*(\\{.*?})\\s*```", setOf(RegexOption.DOT_MATCHES_ALL))

  /**
   * Parses the model response into an [AgentAction]. Returns `null` when the response does not
   * contain a JSON object with a tool name.
   */
  fun parse(response: String): AgentAction? {
    val candidate = extractJson(response) ?: return null
    val obj =
        try {
          json.parseToJsonElement(candidate).jsonObject
        } catch (e: Exception) {
          return null
        }

    val tool =
        obj.stringField("tool")
            ?: obj.stringField("action")
            ?: obj.stringField("name")
            ?: return null

    val nested = obj["args"] ?: obj["arguments"] ?: obj["parameters"]
    val args =
        if (nested is JsonObject) {
          buildJsonObject {
            nested.forEach { (key, value) -> put(key, value) }
            obj.forEach { (key, value) ->
              if (key != "args" && key != "arguments" && key != "parameters" && key != "tool" &&
                  key != "action" && key != "name") {
                put(key, value)
              }
            }
          }
        } else {
          buildJsonObject {
            obj.forEach { (key, value) ->
              if (key != "tool" && key != "action" && key != "name") {
                put(key, value)
              }
            }
          }
        }

    if (tool.isBlank()) return null
    return AgentAction(tool = tool.trim().lowercase(), args = args)
  }

  private fun JsonObject.stringField(name: String): String? =
      (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

  /** Strips Markdown fences and surrounding prose from a model response. */
  fun extractJson(response: String): String? {
    fenceRegex.find(response)?.let {
      return it.groupValues[1]
    }

    var text = response.trim()
    if (text.startsWith("```")) {
      // Unclosed fence: drop the first line and any trailing fence.
      text = text.substringAfter('\n', text)
      text = text.substringBeforeLast("```").trim()
    }

    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
  }
}
