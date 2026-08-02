package com.automattic.simplenote.adapters

import com.automattic.simplenote.models.Block
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockNoteAdapterDeleteBugTest {

    @Test
    fun testHoldingDeleteAcrossEmptyLinesFocusAndContentState() {
        // Initial state:
        // Line 0: Abc\n
        // Line 1: \n  (empty line)
        // Line 2: Cde\n
        // Line 3: Fgh\n
        val b0 = Block(id = "0", content = "Abc", hasTrailingNewline = true)
        val b1 = Block(id = "1", content = "", hasTrailingNewline = true)
        val b2 = Block(id = "2", content = "Cde", hasTrailingNewline = true)
        val b3 = Block(id = "3", content = "Fgh", hasTrailingNewline = true)

        val adapter = BlockNoteAdapter(mutableListOf(b0, b1, b2, b3))

        // Step 1: User deletes "Fgh" down to ""
        b3.content = ""

        // Step 2: Backspace at start of empty Block 3
        adapter.handleBackspaceAtStart(3)
        assertEquals(3, adapter.blocks.size) // Block 3 removed
        assertEquals("0", adapter.blocks[0].id)
        assertEquals("1", adapter.blocks[1].id)
        assertEquals("2", adapter.blocks[2].id)
        assertEquals("2", adapter.activeFocusedBlockId)
        assertEquals(3, adapter.pendingFocusCursorOffset) // Cursor at end of "Cde" (offset 3)

        // Step 3: User deletes "Cde" down to ""
        b2.content = ""

        // Step 4: Backspace at start of empty Block 2
        adapter.handleBackspaceAtStart(2)
        assertEquals(2, adapter.blocks.size) // Block 2 removed
        assertEquals("1", adapter.activeFocusedBlockId) // Focus moves to empty line Block 1
        assertEquals(0, adapter.pendingFocusCursorOffset) // Cursor at offset 0

        // Step 5: Backspace at start of empty Line Block 1
        adapter.handleBackspaceAtStart(1)
        assertEquals(1, adapter.blocks.size) // Empty line Block 1 removed!
        assertEquals("0", adapter.activeFocusedBlockId) // Focus MUST be on Block 0 ("Abc")
        assertEquals(3, adapter.pendingFocusCursorOffset) // Cursor MUST be at end of "Abc" (offset 3)!
        assertEquals("Abc", adapter.blocks[0].content)
    }
}
