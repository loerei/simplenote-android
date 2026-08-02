package com.automattic.simplenote.utils

import com.automattic.simplenote.models.Block

class BlockPositionMapper {
    private var prefixSums: IntArray = intArrayOf(0)

    fun updatePrefixSums(blocks: List<Block>) {
        if (prefixSums.size != blocks.size + 1) {
            prefixSums = IntArray(blocks.size + 1)
        }
        var accum = 0
        prefixSums[0] = 0
        for (i in blocks.indices) {
            accum += blocks[i].totalLength
            prefixSums[i + 1] = accum
        }
    }

    fun toGlobalOffset(blocks: List<Block>, blockIndex: Int, localOffset: Int): Int {
        if (blocks.isEmpty()) return 0
        val validIndex = blockIndex.coerceIn(0, blocks.lastIndex)
        val validLocal = localOffset.coerceIn(0, blocks[validIndex].content.length)
        return prefixSums[validIndex] + validLocal
    }

    fun toLocalPosition(blocks: List<Block>, globalOffset: Int): Pair<Int, Int> {
        if (blocks.isEmpty()) return Pair(0, 0)
        val target = globalOffset.coerceIn(0, prefixSums.last())
        var low = 0
        var high = blocks.size - 1
        var foundBlockIndex = blocks.lastIndex

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (prefixSums[mid + 1] > target) {
                foundBlockIndex = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        val localOffset = (target - prefixSums[foundBlockIndex]).coerceIn(0, blocks[foundBlockIndex].content.length)
        return Pair(foundBlockIndex, localOffset)
    }
}
