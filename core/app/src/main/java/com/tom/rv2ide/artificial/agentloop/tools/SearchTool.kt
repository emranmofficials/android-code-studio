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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Searches for a literal string in the project sources. */
class SearchTool : AgentTool {

  override val name = "search"

  override val description = "Find a literal string in the project and show file:line matches."

  override val argsSchema = """{"query":"text to find","path":"."}"""

  override suspend fun execute(args: JsonObject, ctx: AgentContext): ToolResult =
      withContext(Dispatchers.IO) {
        val query = args.stringValue("query")?.takeIf { it.isNotBlank() }
        if (query == null || query.length < 2) {
          return@withContext ToolResult.Failure("'query' must be at least 2 characters")
        }

        val path = args.stringValue("path")?.takeIf { it.isNotBlank() } ?: "."
        val base =
            ProjectPaths.resolve(ctx.projectRoot, path)
                ?: return@withContext ToolResult.Failure("path is outside the project: $path")

        if (!base.exists()) return@withContext ToolResult.Failure("path not found: $path")

        val matches = ArrayList<String>()
        var scanned = 0
        var truncated = false

        val files = if (base.isFile) sequenceOf(base) else base.walkTopDown().maxDepth(12)

        files.forEach { file ->
          if (truncated) return@forEach
          if (!file.isFile) return@forEach
          if (ProjectPaths.isIgnored(ctx.projectRoot, file)) return@forEach
          if (file.length() > MAX_FILE_SIZE) return@forEach
          if (file.extension.lowercase() in BINARY_EXTENSIONS) return@forEach

          scanned++
          if (scanned > MAX_FILES) {
            truncated = true
            return@forEach
          }

          try {
            file.useLines { lines ->
              lines.forEachIndexed { index, line ->
                if (matches.size >= MAX_MATCHES) {
                  truncated = true
                  return@useLines
                }
                if (line.contains(query)) {
                  val relative = ProjectPaths.relative(ctx.projectRoot, file)
                  matches += "$relative:${index + 1}: ${line.trim().take(200)}"
                }
              }
            }
          } catch (e: Exception) {
            // Skip unreadable files.
          }
        }

        if (matches.isEmpty()) {
          return@withContext ToolResult.Success("No matches for \"$query\".")
        }

        val builder = StringBuilder()
        builder.append("Matches for \"").append(query).append("\":\n")
        matches.forEach { builder.append(it).append('\n') }
        if (truncated) builder.append("… (more matches not shown)")
        ToolResult.Success(builder.toString())
      }

  companion object {
    const val MAX_MATCHES = 80
    const val MAX_FILES = 1500
    const val MAX_FILE_SIZE = 512 * 1024L

    val BINARY_EXTENSIONS =
        setOf(
            "png", "jpg", "jpeg", "gif", "webp", "apk", "aab", "jar", "zip", "so", "dex", "bin",
            "ttf", "otf", "mp3", "mp4", "wav", "ogg")
  }
}
