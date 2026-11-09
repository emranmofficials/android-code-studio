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

package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.AgentStepAdapter
import com.tom.rv2ide.artificial.dialogs.OpenAIAgentConfigDialog
import com.tom.rv2ide.databinding.FragmentAgentBinding
import com.tom.rv2ide.viewmodel.AgentViewModel

/**
 * The "Agent" tab: the user describes a task, the agent edits the project, builds it and fixes the
 * errors. The look follows the app's existing View/XML design (see fragment_chat.xml).
 */
class AgentFragment : Fragment() {

  private var binding: FragmentAgentBinding? = null
  private val viewModel: AgentViewModel by viewModels()
  private val adapter = AgentStepAdapter()

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    val binding = FragmentAgentBinding.inflate(inflater, container, false)
    this.binding = binding
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val binding = binding ?: return

    binding.agentSteps.layoutManager = LinearLayoutManager(requireContext())
    binding.agentSteps.adapter = adapter

    binding.btnSend.setOnClickListener {
      val prompt = binding.agentInput.text?.toString()?.trim().orEmpty()
      if (prompt.isEmpty()) {
        binding.agentPromptLayout.error = getString(R.string.agent_input_hint)
        return@setOnClickListener
      }
      binding.agentPromptLayout.error = null
      binding.agentInput.setText("")
      viewModel.start(prompt)
    }

    binding.btnClear.setOnClickListener {
      viewModel.clear()
    }

    binding.btnStop.setOnClickListener {
      viewModel.cancel()
    }

    binding.btnUndo.setOnClickListener {
      viewModel.undoLast()
    }

    binding.btnOpenAISettings.setOnClickListener {
      OpenAIAgentConfigDialog().show(parentFragmentManager, "OpenAIAgentConfigDialog")
    }

    viewModel.steps.observe(viewLifecycleOwner) { steps ->
      adapter.submitList(steps) {
        if (steps.isNotEmpty()) {
          binding.agentSteps.scrollToPosition(steps.lastIndex)
        }
      }
    }

    viewModel.running.observe(viewLifecycleOwner) { running ->
      binding.progressIndicator.visibility = if (running) View.VISIBLE else View.GONE
      binding.btnStop.visibility = if (running) View.VISIBLE else View.GONE
      binding.btnSend.isEnabled = !running
      binding.btnClear.isEnabled = !running
      binding.btnUndo.isEnabled = !running
      binding.btnOpenAISettings.isEnabled = !running
    }

    viewModel.status.observe(viewLifecycleOwner) { status ->
      binding.statusText.text = status
    }
  }

  override fun onDestroyView() {
    binding?.agentSteps?.adapter = null
    binding = null
    super.onDestroyView()
  }
}
