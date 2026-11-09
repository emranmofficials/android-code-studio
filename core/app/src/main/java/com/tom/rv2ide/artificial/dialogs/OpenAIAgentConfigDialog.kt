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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.openaiagent.OpenAIAgentConfig
import com.tom.rv2ide.artificial.openaiagent.OpenAIAgentSettings
import com.tom.rv2ide.artificial.openaiagent.OpenAIClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sheet for the OpenAI agent connection settings: endpoint, key, model and streaming.
 *
 * Saving also makes OpenAI the active AI provider, so the Agent tab uses it immediately. A custom
 * endpoint (Ollama, LM Studio, OpenRouter, ...) works as long as it speaks the OpenAI
 * `chat/completions` protocol.
 */
class OpenAIAgentConfigDialog(
    private val onSaved: () -> Unit = {},
) : BottomSheetDialogFragment() {

  private lateinit var baseUrlLayout: TextInputLayout
  private lateinit var baseUrlInput: TextInputEditText
  private lateinit var apiKeyLayout: TextInputLayout
  private lateinit var apiKeyInput: TextInputEditText
  private lateinit var modelLayout: TextInputLayout
  private lateinit var modelInput: TextInputEditText
  private lateinit var streamSwitch: MaterialSwitch
  private lateinit var fetchButton: MaterialButton
  private lateinit var cancelButton: MaterialButton
  private lateinit var saveButton: MaterialButton

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    return inflater.inflate(R.layout.dialog_openai_agent_config, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    baseUrlLayout = view.findViewById(R.id.baseUrlLayout)
    baseUrlInput = view.findViewById(R.id.baseUrlInput)
    apiKeyLayout = view.findViewById(R.id.apiKeyLayout)
    apiKeyInput = view.findViewById(R.id.apiKeyInput)
    modelLayout = view.findViewById(R.id.modelLayout)
    modelInput = view.findViewById(R.id.modelInput)
    streamSwitch = view.findViewById(R.id.streamSwitch)
    fetchButton = view.findViewById(R.id.btnFetchModels)
    cancelButton = view.findViewById(R.id.cancelButton)
    saveButton = view.findViewById(R.id.saveButton)

    loadSavedConfig()

    fetchButton.setOnClickListener { fetchModels() }
    cancelButton.setOnClickListener { dismiss() }
    saveButton.setOnClickListener { save() }
  }

  private fun loadSavedConfig() {
    val config = OpenAIAgentSettings.config()
    baseUrlInput.setText(config.baseUrl)
    apiKeyInput.setText(config.apiKey)
    modelInput.setText(config.model)
    streamSwitch.isChecked = config.stream
  }

  private fun currentConfig(): OpenAIAgentConfig {
    val saved = OpenAIAgentSettings.config()
    return saved.copy(
        baseUrl = baseUrlInput.text?.toString().orEmpty().trim(),
        apiKey = apiKeyInput.text?.toString().orEmpty().trim(),
        model = modelInput.text?.toString().orEmpty().trim(),
        stream = streamSwitch.isChecked,
    )
  }

  private fun save() {
    val config = currentConfig()
    baseUrlLayout.error = null
    modelLayout.error = null
    apiKeyLayout.error = null

    if (config.baseUrl.isBlank()) {
      baseUrlLayout.error = getString(R.string.agent_openai_base_url_required)
      return
    }
    if (config.model.isBlank()) {
      modelLayout.error = getString(R.string.agent_openai_model_required)
      return
    }
    if (!config.canRequest) {
      apiKeyLayout.error = getString(R.string.agent_openai_api_key_required)
      return
    }

    OpenAIAgentSettings.save(config)
    toast(getString(R.string.agent_openai_saved, config.model))
    onSaved()
    dismiss()
  }

  private fun fetchModels() {
    val config = currentConfig()
    if (!config.canRequest) {
      toast(getString(R.string.agent_openai_need_connection))
      return
    }

    val label = fetchButton.text
    fetchButton.isEnabled = false
    fetchButton.text = getString(R.string.agent_openai_fetching)

    viewLifecycleOwner.lifecycleScope.launch {
      var failure: String? = null
      val models =
          withContext(Dispatchers.IO) {
            runCatching { OpenAIClient().listModels(config) }.getOrElse { error ->
              failure = error.message.orEmpty()
              emptyList()
            }
          }

      fetchButton.isEnabled = true
      fetchButton.text = label

      val error = failure
      if (error != null) {
        toast(getString(R.string.agent_openai_models_failed, error))
        return@launch
      }
      if (models.isEmpty()) {
        toast(getString(R.string.agent_openai_models_empty))
        return@launch
      }

      MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.agent_openai_model)
          .setItems(models.toTypedArray()) { _, which -> modelInput.setText(models[which]) }
          .setNegativeButton(R.string.agent_openai_cancel, null)
          .show()
    }
  }

  private fun toast(message: String) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
  }
}
