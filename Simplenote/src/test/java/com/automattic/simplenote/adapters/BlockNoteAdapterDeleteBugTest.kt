package com.automattic.simplenote.adapters

import com.automattic.simplenote.models.Block
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockNoteAdapterDeleteBugTest {

    @Test
    fun testScenario1_holdingDeleteFromEndOfParagraphAcrossEmptyLine() {
        // Line 0: Nếu đoạn text bên trên\n
        // Line 1: \n (empty line)
        // Line 2: Cách đoạn text nơi ta bắt đầu giữ xóa một dòng trống\n
        val b0 = Block(id = "0", content = "Nếu đoạn text bên trên", hasTrailingNewline = true)
        val b1 = Block(id = "1", content = "", hasTrailingNewline = true)
        val b2 = Block(id = "2", content = "Cách đoạn text nơi ta bắt đầu giữ xóa một dòng trống", hasTrailingNewline = true)

        val adapter = BlockNoteAdapter(mutableListOf(b0, b1, b2))

        // User deletes content of Line 2 down to ""
        b2.content = ""

        // Backspace on empty Line 2
        adapter.handleBackspaceAtStart(2)

        // Must collapse both empty Line 2 and Line 1, land cursor at end of Line 0 ("Nếu đoạn text bên trên|")
        assertEquals(1, adapter.blocks.size)
        assertEquals("0", adapter.activeFocusedBlockId)
        assertEquals("Nếu đoạn text bên trên".length, adapter.pendingFocusCursorOffset)
        assertEquals("Nếu đoạn text bên trên", adapter.blocks[0].content)
    }

    @Test
    fun testScenario2_holdingDeleteFromMiddleOfParagraphAcrossEmptyLine() {
        // Line 0: Nếu đoạn text bên trên\n
        // Line 1: \n (empty line)
        // Line 2:  nơi ta bắt đầu giữ xóa một dòng trống\n  (after deleting "Cách đoạn text")
        val b0 = Block(id = "0", content = "Nếu đoạn text bên trên", hasTrailingNewline = true)
        val b1 = Block(id = "1", content = "", hasTrailingNewline = true)
        val b2 = Block(id = "2", content = " nơi ta bắt đầu giữ xóa một dòng trống", hasTrailingNewline = true)

        val adapter = BlockNoteAdapter(mutableListOf(b0, b1, b2))

        // Backspace at offset 0 of Line 2
        adapter.handleBackspaceAtStart(2)

        // Empty Line 1 must be removed, Line 2 merged into Line 0
        // Result: "Nếu đoạn text bên trên nơi ta bắt đầu giữ xóa một dòng trống"
        // Cursor offset must be at end of Line 0 content ("Nếu đoạn text bên trên".length = 22)
        assertEquals(1, adapter.blocks.size)
        assertEquals("0", adapter.activeFocusedBlockId)
        assertEquals("Nếu đoạn text bên trên".length, adapter.pendingFocusCursorOffset)
        assertEquals("Nếu đoạn text bên trên nơi ta bắt đầu giữ xóa một dòng trống", adapter.blocks[0].content)
    }
}
