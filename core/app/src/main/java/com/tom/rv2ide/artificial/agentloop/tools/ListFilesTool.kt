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

/** Lists the files of the project so the agent can discover the real paths. */
class ListFilesTool : AgentTool {

  override val name = "list_files"

  override val description = "List files and directories of the project."

  override val argsSchema = """{"path":".","depth":2}"""

  override suspend fun execute(args: JsonObject, ctx: AgentContext): ToolResult =
      withContext(Dispatchers.IO) {
        val path = args.stringValue("path")?.takeIf { it.isNotBlank() } ?: "."
        val base =
            ProjectPaths.resolve(ctx.projectRoot, path)
                ?: return@withContext ToolResult.Failure("path is outside the project: $path")

        if (!base.exists()) return@withContext ToolResult.Failure("path not found: $path")
        if (!base.isDirectory) return@withContext ToolResult.Failure("not a directory: $path")

        val depth = (args.intValue("depth") ?: DEFAULT_DEPTH).coerceIn(1, MAX_DEPTH)
        val lines = ArrayList<String>()

        base.walkTopDown()
            .maxDepth(depth)
            .forEach { file ->
              if (file == base) return@forEach
              if (ProjectPaths.isIgnored(ctx.projectRoot, file)) return@forEach

              val relative = ProjectPaths.relative(ctx.projectRoot, file)
              if (lines.size >= MAX_ENTRIES) return@forEach
              lines += if (file.isDirectory) "$relative/" else relative
            }

        val builder = StringBuilder()
        builder.append("Project root: ").append(ctx.projectRoot.path).append('\n')
        builder.append("Files under ").append(path).append(":\n")
        lines.forEach { builder.append(it).append('\n') }
        if (lines.size >= MAX_ENTRIES) {
          builder.append("… (list truncated, call list_files on a subdirectory)")
        }
        if (lines.isEmpty()) builder.append("(empty)")

        ToolResult.Success(builder.toString())
      }

  companion object {
    const val DEFAULT_DEPTH = 2
    const val MAX_DEPTH = 5
    const val MAX_ENTRIES = 400
  }
}
