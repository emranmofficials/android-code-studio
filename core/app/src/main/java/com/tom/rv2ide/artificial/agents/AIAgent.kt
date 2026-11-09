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

package com.tom.rv2ide.artificial.agents

import android.content.Context
import com.tom.rv2ide.artificial.agentloop.AgentMessage
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.file.FileWriteResult

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

interface AIAgent {
    val providerId: String
    val providerName: String
    
    fun initialize(apiKey: String, context: Context)
    fun reinitializeWithNewModel(apiKey: String, context: Context)
    fun setContext(context: Context)
    fun setProjectData(projectTreeResult: ProjectTreeResult)
    fun clearConversation()
    
    suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String>

    /**
     * Sends a list of conversation messages to the provider and returns the raw response.
     *
     * The default implementation flattens the messages into a single prompt and delegates to
     * [generateCode]. Providers which attach the whole project's file contents in
     * [generateCode] should honour [injectFiles] = `false` and skip that injection, because the
     * agent loop sends its own, much smaller, context. The loop uses an agent instance which was
     * never given project data, so the default implementation is safe as well.
     */
    suspend fun chat(
        messages: List<AgentMessage>,
        injectFiles: Boolean = false,
    ): Result<String> {
      val prompt =
          messages.joinToString(separator = "\n\n") { message ->
            "${message.role.uppercase()}: ${message.content}"
          }
      return generateCode(prompt = prompt, context = null, language = "text", projectStructure = null)
    }

    /**
     * Same as [chat], but providers which support server sent events may call [onChunk] while the
     * answer is being generated. The final text is returned once the answer is complete.
     *
     * [onChunk] receives the text and whether it is "thinking" (reasoning) text rather than part
     * of the answer. The default implementation emits nothing and simply falls back to [chat].
     */
    suspend fun chatStream(
        messages: List<AgentMessage>,
        onChunk: (text: String, reasoning: Boolean) -> Unit,
    ): Result<String> = chat(messages = messages, injectFiles = false)
    
    fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean)
    fun undoLastModification(): Boolean
    fun getModificationHistory(): List<ModificationAttempt>
    
    fun resetAttemptCount()
    fun incrementAttemptCount()
    fun getCurrentAttemptCount(): Int
    fun canRetry(): Boolean
    
    fun writeFile(filePath: String, content: String): FileWriteResult
    fun isInitialized(): Boolean
}

data class ModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)