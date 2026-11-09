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
 * A small line based diff which is good enough to preview a single agent edit. The changed region
 * is found by stripping the common prefix and suffix, so unrelated parts of a large file never end
 * up in the preview.
 */
object SimpleDiff {

  private const val MAX_CHARS = 8000
  private const val CONTEXT_LINES = 3

  fun unified(path: String, oldText: String, newText: String): String {
    if (oldText == newText) return ""

    val oldLines = oldText.lines()
    val newLines = newText.lines()

    var prefix = 0
    while (prefix < oldLines.size &&
        prefix < newLines.size &&
        oldLines[prefix] == newLines[prefix]) {
      prefix++
    }

    var suffix = 0
    while (suffix < oldLines.size - prefix &&
        suffix < newLines.size - prefix &&
        oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]) {
      suffix++
    }

    val contextStart = (prefix - CONTEXT_LINES).coerceAtLeast(0)
    val oldEnd = oldLines.size - suffix
    val newEnd = newLines.size - suffix

    val builder = StringBuilder()
    builder.append("--- a/").append(path).append('\n')
    builder.append("+++ b/").append(path).append('\n')
    builder.append("@@ -")
        .append(contextStart + 1)
        .append(',')
        .append(oldEnd - contextStart)
        .append(" +")
        .append(contextStart + 1)
        .append(',')
        .append(newEnd - contextStart)
        .append(" @@\n")

    for (index in contextStart until prefix) {
      builder.append(' ').append(oldLines[index]).append('\n')
    }
    for (index in prefix until oldEnd) {
      builder.append('-').append(oldLines[index]).append('\n')
    }
    for (index in prefix until newEnd) {
      builder.append('+').append(newLines[index]).append('\n')
    }
    val contextEnd = (oldEnd + CONTEXT_LINES).coerceAtMost(oldLines.size)
    for (index in oldEnd until contextEnd) {
      builder.append(' ').append(oldLines[index]).append('\n')
    }

    return if (builder.length > MAX_CHARS) {
      builder.substring(0, MAX_CHARS) + "\n… (diff truncated)"
    } else {
      builder.toString()
    }
  }
}
