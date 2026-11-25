package com.meenai.mina.core.general

import com.meenai.mina.core.tool.AgentTool

/** General tools (does not belong to which screen): wait, review, ask the user, confirm, hand over to the user, deal with obstacles, end if not done, check knowledge and skills, write down skills according to user requirements, memory, check the records of previous rounds of this conversation. In the order issued to the model. */
internal object GeneralGroup {
    val tools: List<AgentTool> = listOf(
        Wait, ViewPastScreens, ViewUserScreen, AskUser, RequestConfirmation, HandToUser, HandleObstacle, EndNotDone,
        KnowledgeSearch, KnowledgeRead, SkillSearch, SkillRead, SkillSave, MemorySearch, MemorySave, ProfileEdit, HistoryRead, ScheduleTask, SpawnAgent,
        com.meenai.mina.core.settings.SettingsList, com.meenai.mina.core.settings.SettingsSet,
        com.meenai.mina.core.image.GenerateImageTool, com.meenai.mina.core.image.EditImageTool,
    )
}
