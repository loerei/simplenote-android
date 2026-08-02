package com.automattic.simplenote.adapters

import com.automattic.simplenote.models.Block
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockNoteAdapterDeleteBugTest {

    @Test
    fun testHoldingDeleteAcrossEmptyLinesFocusAndContentState() {
        val b0 = Block(id = "0", content = "Abc", hasTrailingNewline = true)
        val b1 = Block(id = "1", content = "", hasTrailingNewline = true)
        val b2 = Block(id = "2", content = "Cde", hasTrailingNewline = true)
        val b3 = Block(id = "3", content = "Fgh", hasTrailingNewline = true)

        val adapter = BlockNoteAdapter(mutableListOf(b0, b1, b2, b3))

        b3.content = ""
        adapter.handleBackspaceAtStart(3)
        assertEquals(3, adapter.blocks.size)

        b2.content = ""
        // Backspace on empty b2 while b1 is also empty -> Fast-path collapses both empty blocks into b0 ("Abc")!
        adapter.handleBackspaceAtStart(2)
        assertEquals(1, adapter.blocks.size)
        assertEquals("0", adapter.activeFocusedBlockId)
        assertEquals(3, adapter.pendingFocusCursorOffset)
        assertEquals("Abc", adapter.blocks[0].content)
    }

    @Test
    fun testConsecutiveEmptyLinesCollapseAtomicOnDelete() {
        val b0 = Block(id = "0", content = "Nếu đoạn text bên trên", hasTrailingNewline = true)
        val b1 = Block(id = "1", content = "", hasTrailingNewline = true)
        val b2 = Block(id = "2", content = "", hasTrailingNewline = true)

        val adapter = BlockNoteAdapter(mutableListOf(b0, b1, b2))

        // Backspace on empty Line 2 while Line 1 is also empty
        adapter.handleBackspaceAtStart(2)

        // Must collapse both empty lines and land cursor at end of Line 0 ("Nếu đoạn text bên trên|")
        assertEquals(1, adapter.blocks.size)
        assertEquals("0", adapter.activeFocusedBlockId)
        assertEquals("Nếu đoạn text bên trên".length, adapter.pendingFocusCursorOffset)
        assertEquals("Nếu đoạn text bên trên", adapter.blocks[0].content)
    }
}
