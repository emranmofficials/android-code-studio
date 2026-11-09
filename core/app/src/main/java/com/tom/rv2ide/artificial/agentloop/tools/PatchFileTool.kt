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
 * Applies a search/replace edit. The search text must match exactly once, which makes the edit
 * deterministic and prevents the model from accidentally changing unrelated code.
 */
class PatchFileTool : AgentTool {

  override val name = "patch_file"

  override val description =
      "Replace a unique block of text in an existing file. `search` must match exactly once."

  override val argsSchema =
      """{"path":"relative/path.kt","search":"exact existing text","replace":"new text"}"""

  override suspend fun execute(args: JsonObject, ctx: AgentContext): ToolResult =
      withContext(Dispatchers.IO) {
        val path = args.stringValue("path") ?: return@withContext ToolResult.Failure("missing 'path'")
        val search = args.stringValue("search")
        val replace = args.stringValue("replace")

        if (search.isNullOrEmpty()) {
          return@withContext ToolResult.Failure("'search' must not be empty")
        }
        if (replace == null) {
          return@withContext ToolResult.Failure("missing 'replace'")
        }

        val file =
            ProjectPaths.resolve(ctx.projectRoot, path)
                ?: return@withContext ToolResult.Failure("path is outside the project: $path")

        if (!file.exists() || !file.isFile) {
          return@withContext ToolResult.Failure("file not found: $path")
        }
        if (ProjectPaths.isIgnored(ctx.projectRoot, file)) {
          return@withContext ToolResult.Failure("file is ignored: $path")
        }

        val oldContent =
            try {
              file.readText()
            } catch (e: Exception) {
              return@withContext ToolResult.Failure("cannot read $path: ${e.message}")
            }

        val crlf = oldContent.contains("\r\n")
        val normalizedSearch = if (crlf) search.replace("\r\n", "\n").replace("\n", "\r\n") else search
        val normalizedReplace =
            if (crlf) replace.replace("\r\n", "\n").replace("\n", "\r\n") else replace

        val count = countMatches(oldContent, normalizedSearch)

        if (count == 0) {
          return@withContext ToolResult.Failure(
              "'search' not found in $path. Read the file again and copy the exact text.")
        }
        if (count > 1) {
          return@withContext ToolResult.Failure(
              "'search' matches $count times in $path. Include more surrounding lines to make it unique.")
        }

        val backup = FileBackup.backup(ctx, file)
        val newContent = oldContent.replaceFirst(normalizedSearch, normalizedReplace)

        try {
          file.writeText(newContent)
        } catch (e: Exception) {
          return@withContext ToolResult.Failure("cannot write $path: ${e.message}")
        }

        val diff = FileBackup.diff(ctx, path, oldContent, newContent)
        val note = if (backup == null) "" else "\n(backup: $backup)"

        ToolResult.Diff(
            path = path,
            diff = diff,
            output = "Patched $path ($count match replaced).$note\n$diff")
      }

  /** Counts non-overlapping matches, and falls back to a line-ending agnostic count. */
  private fun countMatches(content: String, search: String): Int {
    var count = 0
    var index = 0
    while (true) {
      val found = content.indexOf(search, index)
      if (found < 0) break
      count++
      index = found + search.length
      if (count > 2) break
    }
    if (count > 0) return count

    val lfContent = content.replace("\r\n", "\n")
    val lfSearch = search.replace("\r\n", "\n")
    index = 0
    while (true) {
      val found = lfContent.indexOf(lfSearch, index)
      if (found < 0) break
      count++
      index = found + lfSearch.length
      if (count > 2) break
    }
    return count
  }
}
