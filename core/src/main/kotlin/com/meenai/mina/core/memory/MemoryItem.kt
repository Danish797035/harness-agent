package com.meenai.mina.core.memory

import kotlinx.serialization.Serializable

@Suppress("EnumEntryName")
enum class MemoryKind { fact, preference, procedure }

/** Only practices will be pending (waiting for user approval). */
@Suppress("EnumEntryName")
enum class MemoryStatus { active, pending }

/** [type]: `user_said` (with the user’s original words [quote]) or `manual` (handwritten by the user in the settings). */
@Serializable
data class MemorySource(
    val type: String,
    val quote: String? = null,
    val conversationId: String? = null,
    val atMs: Long,
    val channel: String? = null,
)

/** [replaces]: Only one version of the old content that was updated this time remains (the interface "Original:..."). */
@Serializable
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val source: MemorySource,
    val status: MemoryStatus,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val replaces: String? = null,
)
