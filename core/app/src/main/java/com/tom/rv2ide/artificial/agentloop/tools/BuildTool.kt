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
import com.tom.rv2ide.artificial.agentloop.BuildErrorParser
import com.tom.rv2ide.services.builder.GradleBuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Runs Gradle tasks through the project's [com.tom.rv2ide.projects.builder.BuildService] and
 * returns the parsed compiler errors, so the agent can fix them.
 */
class BuildTool : AgentTool {

  override val name = "run_build"

  override val description = "Run Gradle tasks. Returns success/failure and the compiler errors."

  override val argsSchema = """{"tasks":[":app:assembleDebug"]}"""

  override suspend fun execute(args: JsonObject, ctx: AgentContext): ToolResult =
      withContext(Dispatchers.IO) {
        val service =
            ctx.buildService
                ?: return@withContext ToolResult.Failure(
                    "Build service is not available. Open a project and wait for Gradle sync.")

        if (!service.isToolingServerStarted()) {
          return@withContext ToolResult.Failure(
              "Gradle tooling server is not running. Wait for Gradle sync to finish and try again.")
        }
        if (service.isBuildInProgress) {
          return@withContext ToolResult.Failure("A build is already running. Wait for it to finish.")
        }

        val tasks =
            args.stringListValue("tasks")?.filter { it.isNotBlank() }?.toTypedArray()
                ?: DEFAULT_TASKS

        val output = StringBuilder()
        var lastEmit = 0L

        val listener: ((String) -> Unit) = { line ->
          output.append(line).append('\n')
          val now = System.currentTimeMillis()
          val important =
              line.contains("error", ignoreCase = true) ||
                  line.contains("FAILED") ||
                  line.startsWith("e: ")
          if (important || now - lastEmit >= OUTPUT_THROTTLE_MS) {
            lastEmit = now
            ctx.onBuildOutput(line.take(300))
          }
        }

        val closeable = (service as? GradleBuildService)?.addOutputListener(listener)

        try {
          val result = service.executeTasks(*tasks).get()
          val raw = output.toString()
          val issues = BuildErrorParser.parse(raw)
          ToolResult.Build(
              success = result?.isSuccessful == true,
              issues = issues,
              output = raw,
          )
        } catch (e: Exception) {
          val raw = output.toString()
          val cause = e.cause ?: e
          ToolResult.Build(
              success = false,
              issues = BuildErrorParser.parse(raw),
              output = (raw + "\n" + (cause.message ?: cause.javaClass.simpleName)).trim(),
          )
        } finally {
          closeable?.close()
        }
      }

  companion object {
    val DEFAULT_TASKS = arrayOf(":app:assembleDebug")
    const val OUTPUT_THROTTLE_MS = 200L
  }
}
