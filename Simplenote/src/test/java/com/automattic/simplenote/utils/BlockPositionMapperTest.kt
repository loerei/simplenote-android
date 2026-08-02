package com.automattic.simplenote.utils

import com.automattic.simplenote.models.Block
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BlockPositionMapperTest {

    private lateinit var mapper: BlockPositionMapper

    @Before
    fun setUp() {
        mapper = BlockPositionMapper()
    }

    @Test
    fun testEmptyBlocks() {
        val blocks = emptyList<Block>()
        mapper.updatePrefixSums(blocks)
        assertEquals(0, mapper.toGlobalOffset(blocks, 0, 0))
        assertEquals(Pair(0, 0), mapper.toLocalPosition(blocks, 0))
    }

    @Test
    fun testSingleBlock() {
        val blocks = listOf(Block(content = "Hello", hasTrailingNewline = true))
        mapper.updatePrefixSums(blocks)

        assertEquals(0, mapper.toGlobalOffset(blocks, 0, 0))
        assertEquals(3, mapper.toGlobalOffset(blocks, 0, 3))
        assertEquals(5, mapper.toGlobalOffset(blocks, 0, 5))

        assertEquals(Pair(0, 0), mapper.toLocalPosition(blocks, 0))
        assertEquals(Pair(0, 3), mapper.toLocalPosition(blocks, 3))
        assertEquals(Pair(0, 5), mapper.toLocalPosition(blocks, 5))
        assertEquals(Pair(0, 5), mapper.toLocalPosition(blocks, 6))
    }

    @Test
    fun testMultipleBlocks() {
        val blocks = listOf(
            Block(content = "Hello", hasTrailingNewline = true),
            Block(content = "World", hasTrailingNewline = true),
            Block(content = "Test", hasTrailingNewline = false)
        )
        mapper.updatePrefixSums(blocks)

        assertEquals(0, mapper.toGlobalOffset(blocks, 0, 0))
        assertEquals(6, mapper.toGlobalOffset(blocks, 1, 0))
        assertEquals(12, mapper.toGlobalOffset(blocks, 2, 0))
        assertEquals(14, mapper.toGlobalOffset(blocks, 2, 2))

        assertEquals(Pair(0, 0), mapper.toLocalPosition(blocks, 0))
        assertEquals(Pair(0, 5), mapper.toLocalPosition(blocks, 5))
        assertEquals(Pair(1, 0), mapper.toLocalPosition(blocks, 6))
        assertEquals(Pair(1, 2), mapper.toLocalPosition(blocks, 8))
        assertEquals(Pair(2, 0), mapper.toLocalPosition(blocks, 12))
        assertEquals(Pair(2, 4), mapper.toLocalPosition(blocks, 16))
    }
}
