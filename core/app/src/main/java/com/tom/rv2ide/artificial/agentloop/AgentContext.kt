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

import com.tom.rv2ide.projects.builder.BuildService
import java.io.File

/**
 * Everything a tool is allowed to touch.
 *
 * @property projectRoot Root directory of the opened project. All tool paths are resolved
 *   relative to this directory and must stay inside it.
 * @property buildService The build service used to run Gradle tasks. May be `null` when the
 *   tooling API server has not been started yet.
 * @property backupDir Directory where the original contents of modified files are stored before
 *   they are overwritten, so a change can be undone.
 * @property onBuildOutput Called for (a subset of) the raw build output lines.
 */
data class AgentContext(
    val projectRoot: File,
    val buildService: BuildService?,
    val backupDir: File = File(projectRoot, DEFAULT_BACKUP_DIR),
    val onBuildOutput: (String) -> Unit = {},
) {

  companion object {
    const val DEFAULT_BACKUP_DIR = ".agent-backup"

    /** Directories which must never be read or written by the agent. */
    val IGNORED_DIRS =
        setOf(
            ".git",
            ".gradle",
            "build",
            ".agent-backup",
            ".idea",
            "caches",
        )
  }
}
