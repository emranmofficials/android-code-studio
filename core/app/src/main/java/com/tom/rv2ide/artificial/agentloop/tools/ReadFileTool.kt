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

/** Reads a range of lines from a project file. */
class ReadFileTool : AgentTool {

  override val name = "read_file"

  override val description = "Read a text file. Always read a file before editing it."

  override val argsSchema =
      """{"path":"relative/path.kt","startLine":1,"endLine":400}"""

  override suspend fun execute(args: JsonObject, ctx: AgentContext): ToolResult =
      withContext(Dispatchers.IO) {
        val path = args.stringValue("path") ?: return@withContext ToolResult.Failure("missing 'path'")
        val file =
            ProjectPaths.resolve(ctx.projectRoot, path)
                ?: return@withContext ToolResult.Failure("path is outside the project: $path")

        if (!file.exists()) return@withContext ToolResult.Failure("file not found: $path")
        if (!file.isFile) return@withContext ToolResult.Failure("not a file: $path")
        if (ProjectPaths.isIgnored(ctx.projectRoot, file)) {
          return@withContext ToolResult.Failure("file is ignored: $path")
        }

        val lines =
            try {
              file.readLines()
            } catch (e: Exception) {
              return@withContext ToolResult.Failure("cannot read $path: ${e.message}")
            }

        val start = ((args.intValue("startLine") ?: 1) - 1).coerceAtLeast(0)
        if (start >= lines.size && lines.isNotEmpty()) {
          return@withContext ToolResult.Failure(
              "$path has ${lines.size} lines, startLine is out of range")
        }
        val requestedEnd = args.intValue("endLine") ?: (start + MAX_LINES)
        val end = requestedEnd.coerceIn(start, lines.size).coerceAtMost(start + MAX_LINES)

        val builder = StringBuilder()
        for (index in start until end) {
          builder.append(index + 1).append(": ").append(lines[index]).append('\n')
        }
        if (end < lines.size) {
          builder
              .append("… (")
              .append(lines.size - end)
              .append(" more lines, call read_file with startLine=")
              .append(end + 1)
              .append(')')
        }

        ToolResult.Success(builder.toString())
      }

  companion object {
    const val MAX_LINES = 400
  }
}
