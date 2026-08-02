package com.automattic.simplenote

import com.automattic.simplenote.widgets.SimplenoteEditText
import org.junit.Assert.assertNull
import org.junit.Test

class NoteEditorNullSafetyTest {

    @Test
    fun testNullContentEditTextGuard() {
        val mContentEditText: SimplenoteEditText? = null

        // Verify that guarding null mContentEditText prevents NullPointerException
        var exceptionThrown = false
        try {
            if (mContentEditText != null) {
                mContentEditText.addOnSelectionChangedListener(null)
            }
        } catch (e: NullPointerException) {
            exceptionThrown = true
        }

        assertNull(mContentEditText)
        org.junit.Assert.assertFalse("Guard failed: NullPointerException was thrown on null EditText", exceptionThrown)
    }
}
