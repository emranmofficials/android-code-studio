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

package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.ItemAgentStepBinding
import com.tom.rv2ide.artificial.agentloop.AgentStepData

/** One row of the agent step list. */
class AgentStepAdapter : ListAdapter<AgentStepData, AgentStepAdapter.ViewHolder>(DIFF) {

  private val expanded = HashSet<Long>()

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val binding =
        ItemAgentStepBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return ViewHolder(binding)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.bind(getItem(position), expanded.contains(getItem(position).id))
  }

  inner class ViewHolder(private val binding: ItemAgentStepBinding) :
      RecyclerView.ViewHolder(binding.root) {

    fun bind(item: AgentStepData, isExpanded: Boolean) {
      binding.stepTitle.text = if (item.step > 0) "Step ${item.step}" else item.title
      binding.toolChip.text = item.tool
      binding.toolChip.visibility = if (item.tool.isBlank()) View.GONE else View.VISIBLE

      binding.duration.text = if (item.durationMs > 0) "${item.durationMs} ms" else ""

      binding.stepArgs.text = item.args
      binding.stepArgs.visibility = if (item.args.isBlank()) View.GONE else View.VISIBLE

      val output = item.output.trim()
      binding.stepOutput.text = output
      binding.stepOutput.visibility =
          if (isExpanded && output.isNotEmpty()) View.VISIBLE else View.GONE

      val icon =
          when (item.status) {
            AgentStepData.Status.RUNNING -> R.drawable.ic_refresh
            AgentStepData.Status.SUCCESS -> R.drawable.ic_check
            AgentStepData.Status.FAILED -> R.drawable.ic_error
          }
      binding.statusIcon.setImageResource(icon)
      binding.statusIcon.setColorFilter(
          MaterialColors.getColor(
              binding.root,
              when (item.status) {
                AgentStepData.Status.RUNNING -> R.attr.colorPrimary
                AgentStepData.Status.SUCCESS -> R.attr.colorSuccess
                AgentStepData.Status.FAILED -> R.attr.colorError
              }))

      binding.root.setOnClickListener {
        if (output.isEmpty()) return@setOnClickListener
        if (expanded.contains(item.id)) {
          expanded.remove(item.id)
        } else {
          expanded.add(item.id)
        }
        val position = bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
          notifyItemChanged(position)
        }
      }
    }
  }

  companion object {
    private val DIFF =
        object : DiffUtil.ItemCallback<AgentStepData>() {
          override fun areItemsTheSame(oldItem: AgentStepData, newItem: AgentStepData): Boolean =
              oldItem.id == newItem.id

          override fun areContentsTheSame(oldItem: AgentStepData, newItem: AgentStepData): Boolean =
              oldItem == newItem
        }
  }
}
