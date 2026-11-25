package com.meenai.mina.core.risk

/** Types of irreversible operations (common to confirmation cards, judgment results, and reports). */
@Suppress("EnumEntryName")
enum class IrrevAction { send, pay, delete, order, submit, other }

/**
 * The tool declares "what to press to determine whether it is irreversible": Tap clicked element or
 * coordinate; FileControl point file control (same as click, but not the point obtained by looking at
 * the picture); Enter the carriage return after typing; Key button; Swipe horizontal sliding (sliding to
 * delete); Interact key combination, dragging, and drop-down selection on the web page.
 */
enum class GateTarget { Tap, FileControl, Enter, Key, Swipe, Interact }
