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
import com.tom.rv2ide.artificial.agentloop.tools.BuildTool
import com.tom.rv2ide.artificial.agentloop.tools.ListFilesTool
import com.tom.rv2ide.artificial.agentloop.tools.PatchFileTool
import com.tom.rv2ide.artificial.agentloop.tools.ReadFileTool
import com.tom.rv2ide.artificial.agentloop.tools.SearchTool
import com.tom.rv2ide.artificial.agentloop.tools.ToolResult
import com.tom.rv2ide.artificial.agentloop.tools.WriteFileTool
import com.tom.rv2ide.artificial.agents.AIAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The v0.1 agent loop: plan, act with one tool, observe, repeat. The model never has direct access
 * to the file system or to Gradle; it can only ask for one of the [AgentTool]s below.
 */
class AgentLoop(
    private val agent: AIAgent,
    private val ctx: AgentContext,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val listener: (AgentEvent) -> Unit,
) {

  @Volatile private var cancelled = false

  private val tools: Map<String, AgentTool> =
      listOf(
              ReadFileTool(),
              ListFilesTool(),
              SearchTool(),
              PatchFileTool(),
              WriteFileTool(),
              BuildTool(),
          )
          .associateBy { it.name }

  private val history = ArrayList<String>()
  private val errorCounts = HashMap<String, Int>()
  private val changedFiles = LinkedHashSet<String>()

  suspend fun run(userRequest: String) {
    if (userRequest.isBlank()) {
      listener(AgentEvent.Failed("The request is empty."))
      return
    }

    val tree = AgentPrompt.projectTree(ctx.projectRoot)
    val systemPrompt = AgentPrompt.system(tools.values)

    listener(AgentEvent.PlanReady("Planning…"))
    val plan =
        request(AgentPrompt.plan(userRequest, tree)).getOrElse { error ->
          listener(AgentEvent.Failed(error.message ?: "Could not reach the AI provider."))
          return
        }
    listener(AgentEvent.PlanReady(plan.trim().take(4000)))

    val currentErrors = StringBuilder()
    var step = 0

    while (step < maxSteps) {
      if (cancelled) {
        listener(AgentEvent.Cancelled)
        return
      }

      step++
      val prompt =
          AgentPrompt.step(
              systemPrompt = systemPrompt,
              userRequest = userRequest,
              tree = tree,
              step = step,
              maxSteps = maxSteps,
              history = history,
              buildErrors = currentErrors.toString(),
          )

      val response =
          request(prompt).getOrElse { error ->
            listener(AgentEvent.Failed(error.message ?: "Could not reach the AI provider."))
            return
          }

      var action = AgentProtocol.parse(response)
      if (action == null) {
        // One repair attempt for models which wrap the JSON in prose or use a broken fence.
        action = request(AgentPrompt.repair(response)).getOrNull()?.let { AgentProtocol.parse(it) }
      }
      if (action == null) {
        listener(AgentEvent.Failed("The model did not return a valid action."))
        return
      }

      if (action.tool == "finish") {
        val files = LinkedHashSet(changedFiles)
        action.stringList("files")?.let { files.addAll(it) }
        listener(
            AgentEvent.Done(
                summary = action.string("summary")?.takeIf { it.isNotBlank() } ?: "Finished.",
                files = files.toList(),
                steps = step,
            ))
        return
      }

      val tool = tools[action.tool]
      if (tool == null) {
        history += "STEP $step: unknown tool '${action.tool}'. Use one of ${tools.keys} or finish."
        continue
      }

      if (cancelled) {
        listener(AgentEvent.Cancelled)
        return
      }

      listener(AgentEvent.StepStart(step, tool.name, action.preview()))
      val startedAt = System.currentTimeMillis()
      val result =
          try {
            tool.execute(action.args, ctx)
          } catch (e: kotlinx.coroutines.CancellationException) {
            listener(AgentEvent.Cancelled)
            return
          } catch (e: Exception) {
            ToolResult.Failure(e.message ?: e.javaClass.simpleName)
          }
      val duration = System.currentTimeMillis() - startedAt

      listener(
          AgentEvent.StepResult(
              step = step,
              tool = tool.name,
              success = result.isSuccess,
              output = result.summary().take(MAX_EVENT_OUTPUT),
              durationMs = duration,
          ))

      when (result) {
        is ToolResult.Diff -> {
          changedFiles += result.path
          listener(AgentEvent.DiffReady(result.path, result.diff))
        }
        is ToolResult.Build -> {
          if (!result.success) {
            val key =
                result.issues.firstOrNull()?.toString()
                    ?: result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "Build failed"
            val count = (errorCounts[key] ?: 0) + 1
            errorCounts[key] = count
            currentErrors.clear()
            currentErrors.append(key)
            if (count >= MAX_SAME_ERROR) {
              listener(AgentEvent.Stuck(key, count))
              return
            }
          } else {
            currentErrors.clear()
          }
        }
        else -> Unit
      }

      history += buildHistoryEntry(step, tool.name, result)
      while (history.size > MAX_HISTORY) {
        history.removeAt(0)
      }

      if (cancelled) {
        listener(AgentEvent.Cancelled)
        return
      }
    }

    listener(AgentEvent.Failed("Reached the maximum of $maxSteps steps."))
  }

  /** Requests cancellation of the loop and of any running Gradle build. */
  fun cancel() {
    cancelled = true
    try {
      ctx.buildService?.cancelCurrentBuild()
    } catch (e: Exception) {
      // Ignore: the build may have finished already.
    }
  }

  private suspend fun request(prompt: String): Result<String> =
      withContext(Dispatchers.IO) {
        try {
          // Keep each request independent: the agent sends its own compact history, and the
          // providers' conversation history would otherwise grow with every step.
          agent.clearConversation()
          agent.chat(
              messages = listOf(AgentMessage(AgentMessage.ROLE_USER, prompt)),
              injectFiles = false,
          )
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  private fun buildHistoryEntry(step: Int, tool: String, result: ToolResult): String =
      buildString {
        append("STEP ")
        append(step)
        append(" [")
        append(tool)
        append("] ")
        append(if (result.isSuccess) "OK" else "FAILED")
        append('\n')
        append(result.summary().take(MAX_HISTORY_OUTPUT))
      }

  companion object {
    const val DEFAULT_MAX_STEPS = 20
    const val MAX_SAME_ERROR = 3
    const val MAX_HISTORY = 10
    const val MAX_HISTORY_OUTPUT = 2500
    const val MAX_EVENT_OUTPUT = 4000
  }
}
