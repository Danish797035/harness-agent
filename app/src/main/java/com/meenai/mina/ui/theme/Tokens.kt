package com.meenai.mina.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Font size and weight. Only the size, font weight, and line height are determined, and the color is determined by where it is used. */
object HarnessType {
    /** Headline: Empty status, welcome page. */
    val LargeTitle = TextStyle(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
    /** Page title: Top bar. */
    val Title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    /** Text, line headings. */
    val Body = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** Card titles and other text that need to be emphasized. */
    val BodyStrong = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    /** Description text. */
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    /** Group subtitles. */
    val Label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** Tips, footers. */
    val Hint = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)
    /** The value at the end of the line. */
    val Value = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
}

typealias MinaType = HarnessType

/** spacing. Leave [Page] on the left and right sides of the page. */
object HarnessSpace {
    val S4 = 4.dp
    val S8 = 8.dp
    val S12 = 12.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val Page = 16.dp
}

typealias MinaSpace = HarnessSpace

/** The sizes of various elements. */
object HarnessSize {
    val TopBar = 56.dp
    val IconButton = 40.dp
    val RowMin = 52.dp
    val Button = 52.dp
    val Composer = 52.dp
    val Icon = 22.dp
    /** In the icon block on the left side of the list line, the graphic inside is [TileIcon]. */
    val Tile = 30.dp
    val TileIcon = 18.dp
    /** Small icons such as end-of-line arrows. */
    val SmallIcon = 16.dp
    const val IconStroke = 1.7f
}

typealias MinaSize = HarnessSize

/** Rounded corners. */
object HarnessRadius {
    val Card = 16.dp
    val Bubble = 20.dp
    val BubbleTail = 6.dp
    val Button = 14.dp
    val Tile = 9.dp
}

typealias MinaRadius = HarnessRadius

/** Commonly used shapes; top-level constants, not newly created during reorganization. */
object HarnessShapes {
    val Card = RoundedCornerShape(HarnessRadius.Card)
    /** User message bubble: The lower right corner is the tail corner. */
    val UserBubble = RoundedCornerShape(HarnessRadius.Bubble, HarnessRadius.Bubble, HarnessRadius.BubbleTail, HarnessRadius.Bubble)
    /** Harness' answer: The lower left corner is the tail corner, which is symmetrical to the user bubble. */
    val AnswerBubble = RoundedCornerShape(HarnessRadius.Bubble, HarnessRadius.Bubble, HarnessRadius.Bubble, HarnessRadius.BubbleTail)
    val Button = RoundedCornerShape(HarnessRadius.Button)
    val Tile = RoundedCornerShape(HarnessRadius.Tile)
    val Pill = CircleShape
}

typealias MinaShapes = HarnessShapes
