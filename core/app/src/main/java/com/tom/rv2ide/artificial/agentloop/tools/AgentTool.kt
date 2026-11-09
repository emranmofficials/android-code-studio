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

package com.tom.rv2ide.artificial.agentloop.tools

import com.tom.rv2ide.artificial.agentloop.AgentContext
import com.tom.rv2ide.artificial.agentloop.BuildErrorParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A tool the agent can call. Tools must be safe to call from a background thread. */
interface AgentTool {

  val name: String
  val description: String

  /** JSON schema of the arguments, embedded in the system prompt. */
  val argsSchema: String

  suspend fun execute(args: JsonObject, ctx: AgentContext): ToolResult
}

sealed class ToolResult {

  data class Success(val output: String) : ToolResult()

  data class Failure(val error: String) : ToolResult()

  data class Build(
      val success: Boolean,
      val issues: List<BuildErrorParser.BuildIssue>,
      val output: String,
  ) : ToolResult()

  data class Diff(val path: String, val diff: String, val output: String) : ToolResult()

  /** Text that is shown to the user and sent back to the model. */
  fun summary(): String =
      when (this) {
        is Success -> output
        is Failure -> "ERROR: $error"
        is Build ->
            if (success) {
              "BUILD SUCCEEDED\n${output.takeLast(1500)}"
            } else {
              "BUILD FAILED\n${issues.joinToString("\n")}\n\n${output.takeLast(3000)}"
            }
        is Diff -> output
      }

  val isSuccess: Boolean
    get() =
        when (this) {
          is Success, is Diff -> true
          is Build -> success
          is Failure -> false
        }
}

/** Small helpers for reading the loosely typed JSON the models produce. */
internal fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.intValue(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.stringListValue(name: String): List<String>? {
  val element = this[name] ?: return null
  return try {
    element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
  } catch (e: Exception) {
    stringValue(name)?.let { listOf(it) }
  }
}

/** Resolves user/model supplied paths and keeps them inside the project. */
internal object ProjectPaths {

  fun resolve(root: File, path: String): File? {
    if (path.isBlank()) return null

    val candidate = File(path)
    val file = if (candidate.isAbsolute) candidate else File(root, path)

    return try {
      val canonicalRoot = root.canonicalFile
      val canonicalFile = file.canonicalFile
      if (canonicalFile.path == canonicalRoot.path ||
          canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
        canonicalFile
      } else {
        null
      }
    } catch (e: Exception) {
      null
    }
  }

  fun relative(root: File, file: File): String =
      try {
        file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
      } catch (e: Exception) {
        file.path
      }

  /** `true` if the file lives in a directory the agent must not touch. */
  fun isIgnored(root: File, file: File): Boolean {
    val relative = relative(root, file)
    return relative.split('/').any { part -> part in AgentContext.IGNORED_DIRS }
  }
}
