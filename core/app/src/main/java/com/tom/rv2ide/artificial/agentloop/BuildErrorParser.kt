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

/**
 * Extracts compiler errors from raw Gradle output so the agent can be told exactly what to fix
 * instead of having to read the whole build log again.
 */
object BuildErrorParser {

  data class BuildIssue(
      val file: String,
      val line: Int,
      val column: Int,
      val message: String,
  ) {
    override fun toString(): String =
        if (file.isBlank()) message else "$file:$line:$column $message"
  }

  /** Kotlin compiler: `e: file:///path/File.kt:12:34 Unresolved reference: foo` */
  private val kotlinRegex =
      Regex("""^e:\s*(?:file://)?(.+?):(\d+):(\d+)\s*(.+)$""", RegexOption.MULTILINE)

  /** Java compiler: `/path/File.java:12: error: cannot find symbol` */
  private val javaRegex =
      Regex("""^(.+?\.java):(\d+):\s*(?:error|warning):\s*(.+)$""", RegexOption.MULTILINE)

  /** Gradle: `> Task :app:compileDebugKotlin FAILED` */
  private val failedTaskRegex = Regex("""^>\s*Task\s+(\S+)\s+FAILED$""", RegexOption.MULTILINE)

  /** Gradle: `FAILURE: Build failed with an exception.` */
  private val failureRegex = Regex("""^FAILURE:\s*(.+)$""", RegexOption.MULTILINE)

  /** AAPT2: `Android resource linking failed` or `error: resource ... not found.` */
  private val resourceRegex =
      Regex("""^(.*(?:resource linking failed|AAPT2 error|: error:).*)$""", RegexOption.MULTILINE)

  fun parse(output: String): List<BuildIssue> {
    if (output.isBlank()) return emptyList()

    val issues = ArrayList<BuildIssue>()

    kotlinRegex.findAll(output).forEach { match ->
      val message = match.groupValues[4].trim()
      if (message.isNotEmpty()) {
        issues +=
            BuildIssue(
                file = match.groupValues[1],
                line = match.groupValues[2].toIntOrNull() ?: 0,
                column = match.groupValues[3].toIntOrNull() ?: 0,
                message = message,
            )
      }
    }

    javaRegex.findAll(output).forEach { match ->
      val message = match.groupValues[3].trim()
      if (message.isNotEmpty()) {
        issues +=
            BuildIssue(
                file = match.groupValues[1],
                line = match.groupValues[2].toIntOrNull() ?: 0,
                column = 0,
                message = message,
            )
      }
    }

    // Resource / AAPT errors: keep the line but without file information.
    if (issues.isEmpty()) {
      resourceRegex.findAll(output).forEach { match ->
        val message = match.groupValues[1].trim()
        if (message.isNotEmpty()) {
          issues += BuildIssue(file = "", line = 0, column = 0, message = message)
        }
      }
    }

    // If nothing could be parsed, fall back to the failed task and Gradle failure lines.
    if (issues.isEmpty()) {
      failedTaskRegex.findAll(output).forEach { match ->
        issues +=
            BuildIssue(
                file = "",
                line = 0,
                column = 0,
                message = "Task ${match.groupValues[1]} FAILED",
            )
      }
      failureRegex.findAll(output).forEach { match ->
        issues +=
            BuildIssue(
                file = "",
                line = 0,
                column = 0,
                message = match.groupValues[1].trim(),
            )
      }
    }

    return issues.distinctBy { "${it.file}:${it.line}:${it.column}:${it.message}" }.take(MAX_ISSUES)
  }

  const val MAX_ISSUES = 40
}
