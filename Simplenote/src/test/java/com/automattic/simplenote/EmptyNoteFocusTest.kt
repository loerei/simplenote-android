package com.automattic.simplenote

import com.automattic.simplenote.adapters.BlockNoteAdapter
import com.automattic.simplenote.models.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EmptyNoteFocusTest {

    @Test
    fun testEmptyNoteAutoCreatesInitialBlock() {
        val adapter = BlockNoteAdapter()
        val emptyBlocks = mutableListOf(Block(content = "", hasTrailingNewline = false))
        adapter.setBlocks(emptyBlocks)

        assertEquals(1, adapter.getItemCount())
        assertEquals("", adapter.blocks[0].content)

        adapter.focusBlock(0, 0)
        assertNotNull(adapter.activeFocusedBlockId)
        assertEquals(adapter.blocks[0].id, adapter.activeFocusedBlockId)
    }
}
