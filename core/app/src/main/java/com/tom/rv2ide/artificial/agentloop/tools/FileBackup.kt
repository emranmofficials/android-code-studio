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
import com.tom.rv2ide.artificial.agentloop.SimpleDiff
import java.io.File

/** Stores the original content of a file so the user can undo an agent edit. */
internal object FileBackup {

  fun backup(ctx: AgentContext, file: File): String? {
    return try {
      val relative = ProjectPaths.relative(ctx.projectRoot, file)
      val target = File(File(ctx.backupDir, System.currentTimeMillis().toString()), relative)
      target.parentFile?.mkdirs()
      target.writeText(file.readText())
      target.path
    } catch (e: Exception) {
      null
    }
  }

  fun diff(ctx: AgentContext, path: String, oldText: String, newText: String): String =
      SimpleDiff.unified(ProjectPaths.relative(ctx.projectRoot, File(ctx.projectRoot, path)),
          oldText, newText)
}
