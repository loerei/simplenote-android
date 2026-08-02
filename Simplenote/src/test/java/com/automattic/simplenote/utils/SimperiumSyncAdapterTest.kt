package com.automattic.simplenote.utils

import com.automattic.simplenote.adapters.BlockEditorConfig
import com.automattic.simplenote.models.Block
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SimperiumSyncAdapterTest {

    private lateinit var syncAdapter: SimperiumSyncAdapter

    @Before
    fun setUp() {
        syncAdapter = SimperiumSyncAdapter()
    }

    @Test
    fun testSerializeAndParseRoundTrip() {
        val originalText = "Line 1\nLine 2\nLine 3"
        val blocks = syncAdapter.parseToBlocks(originalText)

        assertEquals(3, blocks.size)
        assertEquals("Line 1", blocks[0].content)
        assertTrue(blocks[0].hasTrailingNewline)
        assertEquals("Line 2", blocks[1].content)
        assertTrue(blocks[1].hasTrailingNewline)
        assertEquals("Line 3", blocks[2].content)
        assertFalse(blocks[2].hasTrailingNewline)

        val serialized = syncAdapter.serializeBlocks(blocks)
        assertEquals(originalText, serialized)
    }

    @Test
    fun testSoftChunkingThresholdForLongParagraphs() {
        val longLine = "A".repeat(BlockEditorConfig.MAX_BLOCK_LENGTH + 500)
        val blocks = syncAdapter.parseToBlocks(longLine)

        assertEquals(2, blocks.size)
        assertEquals(BlockEditorConfig.MAX_BLOCK_LENGTH, blocks[0].content.length)
        assertFalse(blocks[0].hasTrailingNewline)
        assertEquals(500, blocks[1].content.length)
        assertFalse(blocks[1].hasTrailingNewline)

        val serialized = syncAdapter.serializeBlocks(blocks)
        assertEquals(longLine, serialized)
    }

    @Test
    fun testReconcileRemoteContentCleanLocal() {
        val localBlocks = syncAdapter.parseToBlocks("Line 1\nLine 2").toMutableList()
        val remoteText = "Line 1\nLine 2 updated\nLine 3 added"

        val changed = syncAdapter.reconcileRemoteContent(localBlocks, remoteText, null)
        assertTrue(changed)

        val updatedText = syncAdapter.serializeBlocks(localBlocks)
        assertEquals(remoteText, updatedText)
    }

    @Test
    fun testReconcileUnsyncedEditSafeguard() {
        val localBlocks = syncAdapter.parseToBlocks("Line 1\nLine 2").toMutableList()
        localBlocks[1].content = "Line 2 locally modified"

        val remoteText = "Line 1"

        val changed = syncAdapter.reconcileRemoteContent(localBlocks, remoteText, null)
        assertTrue(changed)

        assertTrue(localBlocks.any { it.content == "Line 2 locally modified" })
    }

    @Test
    fun testReconcileMyersDiffParagraphInsertionsAndDeletions() {
        val localBlocks = syncAdapter.parseToBlocks("Paragraph A\nParagraph B\nParagraph C").toMutableList()
        val remoteText = "Header Inserted\nParagraph A\nParagraph C"

        val changed = syncAdapter.reconcileRemoteContent(localBlocks, remoteText, null)
        assertTrue(changed)

        val updatedText = syncAdapter.serializeBlocks(localBlocks)
        assertEquals(remoteText, updatedText)
    }

    @Test
    fun testThreeWayMergeWithBaseContent() {
        val localBlocks = syncAdapter.parseToBlocks("Paragraph A\nParagraph B").toMutableList()
        val focusedBlock = localBlocks[1]
        focusedBlock.baseContent = "Paragraph B"
        focusedBlock.content = "Paragraph B - Local Edit"

        val remoteText = "Paragraph A\nParagraph B - Remote Edit"

        val changed = syncAdapter.reconcileRemoteContent(localBlocks, remoteText, focusedBlock.id)
        assertTrue(changed)
        assertTrue(localBlocks[1].content.contains("Local Edit") || localBlocks[1].content.contains("Remote Edit"))
    }
}
