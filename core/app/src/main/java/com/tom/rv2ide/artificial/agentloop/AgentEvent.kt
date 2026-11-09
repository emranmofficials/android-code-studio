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
 * Events emitted by [AgentLoop] while it works. The UI renders these as agent steps so the user
 * can follow what the agent is doing.
 */
sealed interface AgentEvent {

  /** The agent produced a (short) plan for the user's request. */
  data class PlanReady(val plan: String) : AgentEvent

  /** A tool is about to be executed. [args] is a short, human readable preview. */
  data class StepStart(val step: Int, val tool: String, val args: String) : AgentEvent

  /** A tool finished. [output] is truncated before it is emitted. */
  data class StepResult(
      val step: Int,
      val tool: String,
      val success: Boolean,
      val output: String,
      val durationMs: Long,
  ) : AgentEvent

  /** A line of the raw Gradle build output. Emitted sparingly to keep the UI responsive. */
  data class BuildOutput(val line: String) : AgentEvent

  /** A file was changed and its diff is available. */
  data class DiffReady(val path: String, val diff: String) : AgentEvent

  /** The given files were modified by the agent. */
  data class FilesChanged(val paths: List<String>) : AgentEvent

  /** The agent decided it is done. */
  data class Done(val summary: String, val files: List<String>, val steps: Int) : AgentEvent

  /** The same build error repeated [attempts] times, so the agent stopped to avoid a loop. */
  data class Stuck(val error: String, val attempts: Int) : AgentEvent

  /** The run failed for a reason which cannot be fixed by the agent (API error, no tool, ...). */
  data class Failed(val message: String) : AgentEvent

  /** The user cancelled the run. */
  data object Cancelled : AgentEvent
}
