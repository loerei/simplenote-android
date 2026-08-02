package com.automattic.simplenote.adapters

import androidx.recyclerview.widget.RecyclerView
import com.automattic.simplenote.models.Block
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BlockNoteAdapterTest {

    private lateinit var adapter: BlockNoteAdapter

    @Before
    fun setUp() {
        adapter = BlockNoteAdapter()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {})
    }

    @Test
    fun testSetBlocks() {
        val blocks = listOf(
            Block(content = "First line", hasTrailingNewline = true),
            Block(content = "Second line", hasTrailingNewline = false)
        )
        adapter.setBlocks(blocks)

        assertEquals(2, adapter.getItemCount())
        assertEquals("First line", adapter.blocks[0].content)
        assertEquals("Second line", adapter.blocks[1].content)
    }

    @Test
    fun testActiveFocusedBlockId() {
        val block1 = Block(content = "Block 1")
        val block2 = Block(content = "Block 2")
        adapter.setBlocks(listOf(block1, block2))

        adapter.focusBlock(1, 4)
        assertEquals(block2.id, adapter.activeFocusedBlockId)
        assertEquals(4, adapter.pendingFocusCursorOffset)
    }

    @Test
    fun testSoftChunkingThresholdConfig() {
        assertEquals(4000, BlockEditorConfig.MAX_BLOCK_LENGTH)
        assertEquals(300L, BlockEditorConfig.SYNC_DEBOUNCE_MS)
    }

    @Test
    fun testBlockModelInvariants() {
        val block = Block(content = "Hello World", hasTrailingNewline = true)
        assertEquals(12, block.totalLength)

        val noNewlineBlock = Block(content = "Hello World", hasTrailingNewline = false)
        assertEquals(11, noNewlineBlock.totalLength)
    }
}
