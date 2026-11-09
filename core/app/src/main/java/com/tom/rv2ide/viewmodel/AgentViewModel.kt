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

package com.tom.rv2ide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agentloop.AgentContext
import com.tom.rv2ide.artificial.agentloop.AgentStepData
import com.tom.rv2ide.artificial.agentloop.AgentEvent
import com.tom.rv2ide.artificial.agentloop.AgentLoop
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.projects.internal.ProjectManagerImpl
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Drives the v0.1 agent loop and turns its [AgentEvent]s into a list of step cards for the Agent
 * tab. All the real work happens on [Dispatchers.IO]; this class only keeps the UI state.
 */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

  private val _steps = MutableLiveData<List<AgentStepData>>(emptyList())
  val steps: LiveData<List<AgentStepData>> = _steps

  private val _running = MutableLiveData(false)
  val running: LiveData<Boolean> = _running

  private val _status = MutableLiveData(application.getString(R.string.agent_status_idle))
  val status: LiveData<String> = _status

  private var loop: AgentLoop? = null

  @Volatile private var nextId = 0L

  fun start(prompt: String) {
    if (_running.value == true) {
      _status.postValue(str(R.string.agent_running_wait))
      return
    }

    val projectDir = projectDirOrNull()
    if (projectDir.isNullOrBlank()) {
      _status.postValue(str(R.string.agent_no_project))
      return
    }

    val manager = AIAgentManager(getApplication())
    val agent = manager.getCurrentAgent()
    if (agent == null || !agent.isInitialized()) {
      _status.postValue(str(R.string.agent_no_provider))
      return
    }

    val context =
        AgentContext(
            projectRoot = File(projectDir),
            buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE),
            onBuildOutput = { line -> _status.postValue(line) },
        )

    val agentLoop = AgentLoop(agent = agent, ctx = context, listener = ::onEvent)
    loop = agentLoop
    _steps.postValue(emptyList())
    _running.postValue(true)
    _status.postValue(str(R.string.agent_status_planning))

    viewModelScope.launch(Dispatchers.IO) {
      try {
        agentLoop.run(prompt)
      } catch (e: Exception) {
        onEvent(AgentEvent.Failed(e.message ?: e.javaClass.simpleName))
      } finally {
        _running.postValue(false)
        loop = null
      }
    }
  }

  fun cancel() {
    loop?.cancel()
    _status.postValue(str(R.string.agent_status_cancelled))
  }

  fun clear() {
    if (_running.value == true) {
      _status.postValue(str(R.string.agent_running_wait))
      return
    }
    _steps.postValue(emptyList())
    _status.postValue(str(R.string.agent_status_idle))
  }

  /**
   * Restores the files from the most recent backup created by [AgentLoop]. Files which were newly
   * created by the agent are not removed.
   */
  fun undoLast() {
    val projectDir = projectDirOrNull()
    if (projectDir.isNullOrBlank()) {
      _status.postValue(str(R.string.agent_no_project))
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      val root = File(projectDir)
      val backupRoot = File(root, AgentContext.DEFAULT_BACKUP_DIR)
      val latest =
          backupRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull {
            it.name.toLongOrNull() ?: 0L
          }

      if (latest == null) {
        _status.postValue(str(R.string.agent_undo_none))
        return@launch
      }

      var restored = 0
      latest.walkTopDown().forEach { backup ->
        if (!backup.isFile) return@forEach
        try {
          val relative = backup.relativeTo(latest).invariantSeparatorsPath
          val target = File(root, relative)
          target.parentFile?.mkdirs()
          backup.copyTo(target, overwrite = true)
          restored++
        } catch (e: Exception) {
          // Skip files which cannot be restored.
        }
      }

      if (restored == 0) {
        _status.postValue(str(R.string.agent_undo_none))
      } else {
        _status.postValue(str(R.string.agent_undo_done, restored))
      }
    }
  }

  private fun onEvent(event: AgentEvent) {
    when (event) {
      is AgentEvent.PlanReady -> {
        if (event.plan == "Planning…") {
          _status.postValue(str(R.string.agent_status_planning))
          return
        }
        mutate { list ->
          list.add(
              AgentStepData(
                  id = nextId++,
                  step = 0,
                  title = str(R.string.agent_step_plan),
                  tool = "",
                  status = AgentStepData.Status.SUCCESS,
                  output = event.plan,
              ))
        }
      }

      is AgentEvent.StepStart -> {
        _status.postValue(str(R.string.agent_status_running))
        mutate { list ->
          list.add(
              AgentStepData(
                  id = nextId++,
                  step = event.step,
                  title = "Step ${event.step}",
                  tool = event.tool,
                  args = event.args,
              ))
        }
      }

      is AgentEvent.StepResult -> {
        mutate { list ->
          val index =
              list.indexOfLast {
                it.step == event.step &&
                    it.tool == event.tool &&
                    it.status == AgentStepData.Status.RUNNING
              }
          if (index >= 0) {
            list[index] =
                list[index].copy(
                    status =
                        if (event.success) {
                          AgentStepData.Status.SUCCESS
                        } else {
                          AgentStepData.Status.FAILED
                        },
                    output = event.output,
                    durationMs = event.durationMs,
                )
          }
        }
      }

      is AgentEvent.DiffReady -> {
        mutate { list ->
          val index = list.indexOfLast { it.tool == "patch_file" || it.tool == "write_file" }
          if (index >= 0) {
            val item = list[index]
            list[index] = item.copy(output = item.output + "\n\n" + event.diff)
          }
        }
      }

      is AgentEvent.BuildOutput -> _status.postValue(event.line)

      is AgentEvent.FilesChanged -> Unit

      is AgentEvent.Done -> {
        val files = if (event.files.isEmpty()) "" else str(R.string.agent_files_changed, event.files.joinToString(", "))
        mutate { list ->
          list.add(
              AgentStepData(
                  id = nextId++,
                  step = 0,
                  title = str(R.string.agent_status_done),
                  tool = "",
                  status = AgentStepData.Status.SUCCESS,
                  output = listOf(event.summary, files).filter { it.isNotBlank() }.joinToString("\n\n"),
              ))
        }
        _status.postValue(str(R.string.agent_status_done))
      }

      is AgentEvent.Stuck -> {
        mutate { list ->
          list.add(
              AgentStepData(
                  id = nextId++,
                  step = 0,
                  title = str(R.string.agent_status_stuck),
                  tool = "",
                  status = AgentStepData.Status.FAILED,
                  output = event.error,
              ))
        }
        _status.postValue(str(R.string.agent_status_stuck))
      }

      is AgentEvent.Failed -> {
        mutate { list ->
          list.add(
              AgentStepData(
                  id = nextId++,
                  step = 0,
                  title = str(R.string.agent_status_failed),
                  tool = "",
                  status = AgentStepData.Status.FAILED,
                  output = event.message,
              ))
        }
        _status.postValue(event.message)
      }

      AgentEvent.Cancelled -> {
        mutate { list ->
          list.add(
              AgentStepData(
                  id = nextId++,
                  step = 0,
                  title = str(R.string.agent_status_cancelled),
                  tool = "",
                  status = AgentStepData.Status.FAILED,
                  output = str(R.string.agent_status_cancelled),
              ))
        }
        _status.postValue(str(R.string.agent_status_cancelled))
      }
    }
  }

  private fun mutate(block: (MutableList<AgentStepData>) -> Unit) {
    val list = (_steps.value ?: emptyList()).toMutableList()
    block(list)
    _steps.postValue(list)
  }

  private fun str(id: Int, vararg args: Any): String =
      getApplication<Application>().getString(id, *args)

  private fun projectDirOrNull(): String? =
      try {
        ProjectManagerImpl.getInstance().projectDirPath.takeIf { it.isNotBlank() }
      } catch (e: Exception) {
        null
      }
}
