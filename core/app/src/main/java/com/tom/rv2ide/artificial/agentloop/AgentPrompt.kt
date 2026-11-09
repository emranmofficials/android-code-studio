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

import com.tom.rv2ide.artificial.agentloop.tools.AgentTool
import java.io.File

/** Builds the prompts used by [AgentLoop]. Kept separate so they are easy to tune. */
object AgentPrompt {

  private const val MAX_TREE_ENTRIES = 400
  private const val MAX_TREE_DEPTH = 5

  fun system(tools: Collection<AgentTool>): String =
      buildString {
        appendLine("You are an autonomous Android/Kotlin coding agent working inside the user's")
        appendLine("project. You make the change, build the project, read the build errors and fix")
        appendLine("them until the build succeeds.")
        appendLine()
        appendLine("RESPONSE FORMAT (STRICT):")
        appendLine("Respond with EXACTLY ONE JSON object and nothing else. Do not use Markdown.")
        appendLine("Example: {\"tool\":\"read_file\",\"path\":\"app/src/main/java/MainActivity.kt\"}")
        appendLine()
        appendLine("TOOLS:")
        tools.forEach { tool ->
          appendLine("- ${tool.name}: ${tool.description}")
          appendLine("  arguments: ${tool.argsSchema}")
        }
        appendLine("- finish: stop and report. arguments: {\"summary\":\"what changed\",\"files\":[\"...\"]}")
        appendLine()
        appendLine("RULES:")
        appendLine("1. Paths are relative to the project root and must stay inside it.")
        appendLine("2. Read a file before you edit it. Never guess its contents.")
        appendLine("3. patch_file requires `search` to match EXACTLY ONCE. Keep it short but unique.")
        appendLine("4. After changing code, call run_build to verify.")
        appendLine("5. If the build fails, fix the FIRST error only, then build again.")
        appendLine("6. Do not repeat the same edit which already failed.")
        appendLine("7. When the build succeeds (or the task cannot be done), call finish.")
        appendLine("8. Keep the existing code style, package structure and XML layout conventions.")
      }

  fun projectTree(root: File): String {
    val builder = StringBuilder()
    var count = 0

    root.walkTopDown()
        .maxDepth(MAX_TREE_DEPTH)
        .onEnter { file ->
          val relative = relativePath(root, file)
          val name = file.name
          !(name in AgentContext.IGNORED_DIRS && file != root) && relative.count { it == '/' } < MAX_TREE_DEPTH
        }
        .forEach { file ->
          if (file == root) return@forEach
          if (count >= MAX_TREE_ENTRIES) return@forEach
          val relative = relativePath(root, file)
          builder.append(if (file.isDirectory) "$relative/\n" else "$relative\n")
          count++
        }

    if (count >= MAX_TREE_ENTRIES) {
      builder.append("… (tree truncated)\n")
    }
    return builder.toString()
  }

  fun plan(userRequest: String, tree: String): String =
      buildString {
        appendLine("You are planning a change to an Android project. Do not write code yet.")
        appendLine("Give a short numbered plan (at most 8 steps) of the files you will change and")
        appendLine("why. Do not use Markdown headings and do not output JSON.")
        appendLine()
        appendLine("=== PROJECT TREE ===")
        appendLine(tree)
        appendLine("=== USER REQUEST ===")
        appendLine(userRequest)
      }

  fun step(
      systemPrompt: String,
      userRequest: String,
      tree: String,
      step: Int,
      maxSteps: Int,
      history: List<String>,
      buildErrors: String,
  ): String =
      buildString {
        appendLine(systemPrompt)
        appendLine("=== PROJECT TREE ===")
        appendLine(tree)
        appendLine("=== USER REQUEST ===")
        appendLine(userRequest)
        appendLine("=== STEP $step OF $maxSteps ===")
        if (history.isNotEmpty()) {
          appendLine("=== RECENT STEPS (oldest first) ===")
          history.forEach { entry ->
            appendLine(entry.take(3000))
            appendLine()
          }
        } else {
          appendLine("No steps have been executed yet.")
        }
        if (buildErrors.isNotBlank()) {
          appendLine("=== CURRENT BUILD ERRORS ===")
          appendLine(buildErrors.take(4000))
        }
        appendLine("Respond now with exactly one JSON object.")
      }

  fun repair(invalidResponse: String): String =
      buildString {
        appendLine("Your previous response was not a valid action.")
        appendLine("Previous response:")
        appendLine(invalidResponse.take(1200))
        appendLine()
        appendLine("Respond with EXACTLY ONE JSON object such as")
        appendLine("{\"tool\":\"read_file\",\"path\":\"app/build.gradle.kts\"} and nothing else.")
      }

  private fun relativePath(root: File, file: File): String =
      try {
        file.relativeTo(root).invariantSeparatorsPath
      } catch (e: Exception) {
        file.name
      }
}
