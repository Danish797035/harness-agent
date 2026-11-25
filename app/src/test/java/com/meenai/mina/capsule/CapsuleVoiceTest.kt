package com.meenai.mina.capsule

import androidx.compose.ui.geometry.Rect
import com.meenai.mina.cap.phone.a11y.HoldGesture
import com.meenai.mina.i18n.EnglishTexts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Press and hold the capsule to speak: which circle your finger falls on when you release it, how to distinguish between long pressing and dragging, and the sentence that flashes on the capsule after speaking. */
class CapsuleVoiceTest {
    @get:Rule val texts = EnglishTexts()

    private val cancel = Rect(100f, 200f, 162f, 262f)
    private val edit = Rect(300f, 200f, 362f, 262f)

    @Test fun insideCancelCircleIsCancel() {
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun justOutsideCancelCircleStillCancels() {
        // 10px outside the circle, 14px outside
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(90f, 230f, cancel, edit, slopPx = 14))
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 272f, cancel, edit, slopPx = 14))
    }

    @Test fun farFromBothCirclesIsSend() {
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(230f, 900f, cancel, edit, slopPx = 14))
        // The middle of the two circles is not close enough.
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(231f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun insideEditCircleIsEdit() {
        assertEquals(CapsuleVoice.Drop.Edit, CapsuleVoice.drop(330f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun circlesNotLaidOutYetMeanSend() {
        // When the panel has not been laid out (the position of the circle has not yet been reported),
        // it will not be mistakenly judged as cancelled.
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(0f, 0f, Rect.Zero, Rect.Zero, slopPx = 14))
    }

    @Test fun smallMoveThenTimeoutIsHold() {
        val g = HoldGesture(slopPx = 8f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Tap, g.move(5f))
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
        // After long pressing, no matter how you move, it will still be long pressing and will not turn
        // into dragging.
        assertEquals(HoldGesture.Kind.Hold, g.move(200f))
    }

    @Test fun movedPastSlopFirstIsDragAndNeverHold() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        assertEquals(HoldGesture.Kind.Drag, g.move(20f))
        assertEquals(HoldGesture.Kind.Drag, g.timeout())
        assertEquals(HoldGesture.Kind.Drag, g.move(2f))
    }

    @Test fun newDownStartsOver() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        g.move(20f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
    }

    @Test fun sentNoteSaysWhatHappenedWithTheFirstWords() {
        assertEquals("Told the task: 要下午三点以后的，二等座…", CapsuleVoice.sent(CardInputKind.Steer, "要下午三点以后的，二等座靠窗"))
        assertEquals("Answered: 明天上午", CapsuleVoice.sent(CardInputKind.Answer, "明天上午"))
        assertEquals("Sent: 查一下天气", CapsuleVoice.sent(CardInputKind.Continue, "查一下天气"))
        assertEquals("Sent: 查一下天气", CapsuleVoice.sent(CardInputKind.Start, "查一下天气"))
    }
}
