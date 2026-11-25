package com.meenai.mina.core.visual

/** The grid and threshold used for rough comparison of the two frames before and after the action (circumstantial evidence for judging the effect of the action). */
object FrameTuning {
    /** The number of columns and rows of the screen grid. */
    const val GRID_COLS = 24
    const val GRID_ROWS = 48
    /** If the brightness difference of one grid exceeds this much, it is considered that the grid has changed. */
    const val CELL_DELTA = 16
    /** A visible change requires at least this many frames (about 1%) of the entire screen to change. */
    const val FRAME_VISIBLE = 0.01
    /** At least this many cells in the target area must have changed to be considered a visible change in the area. */
    const val REGION_VISIBLE = 0.15
    /** Changing so many frames on the entire screen is considered a large area change. */
    const val FRAME_LARGE = 0.25
}
