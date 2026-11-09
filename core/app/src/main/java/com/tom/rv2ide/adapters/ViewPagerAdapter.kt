package com.tom.rv2ide.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.fragments.AgentFragment
import com.tom.rv2ide.fragments.AIHistoryFragment
import com.tom.rv2ide.fragments.ChatFragment

class ViewPagerAdapter(
    fragmentActivity: FragmentActivity,
    private val aiAgent: AIAgentManager
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ChatFragment.newInstance(aiAgent)
            1 -> AgentFragment()
            2 -> AIHistoryFragment.newInstance(aiAgent)
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}
