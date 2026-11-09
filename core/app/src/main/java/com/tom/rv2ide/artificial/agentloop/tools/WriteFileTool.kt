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

/**
 * Creates a new file. Existing files are never overwritten; use [PatchFileTool] to change them.
 */
class WriteFileTool : AgentTool {

  override val name = "write_file"

  override val description = "Create a NEW file. Fails if the file already exists."

  override val argsSchema =
      """{"path":"relative/path.kt","content":"full file content"}"""

  override suspend fun execute(args: JsonObject, ctx: AgentContext): ToolResult =
      withContext(Dispatchers.IO) {
        val path = args.stringValue("path") ?: return@withContext ToolResult.Failure("missing 'path'")
        val content = args.stringValue("content")
        if (content == null) return@withContext ToolResult.Failure("missing 'content'")

        val file =
            ProjectPaths.resolve(ctx.projectRoot, path)
                ?: return@withContext ToolResult.Failure("path is outside the project: $path")

        if (file.exists()) {
          return@withContext ToolResult.Failure(
              "file already exists: $path. Use patch_file to edit it.")
        }
        if (ProjectPaths.isIgnored(ctx.projectRoot, file)) {
          return@withContext ToolResult.Failure("path is ignored: $path")
        }

        try {
          file.parentFile?.mkdirs()
          file.writeText(content)
        } catch (e: Exception) {
          return@withContext ToolResult.Failure("cannot write $path: ${e.message}")
        }

        val diff = FileBackup.diff(ctx, path, "", content)
        ToolResult.Diff(
            path = path,
            diff = diff,
            output = "Created $path (${content.length} chars)\n$diff")
      }
}
