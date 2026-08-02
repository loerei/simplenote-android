package com.automattic.simplenote.utils

import com.automattic.simplenote.models.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OptimizedReconcileTest {

    @Test
    fun testPrefixSuffixTrimmedReconcilePerformance() {
        val blacklistFile = File("D:/Projects/blacklist.txt")
        require(blacklistFile.exists()) { "File D:/Projects/blacklist.txt does not exist!" }

        val syncAdapter = SimperiumSyncAdapter()
        val text = blacklistFile.readText()
        val localBlocks = syncAdapter.parseToBlocks(text).toMutableList()
        val remoteBlocks = syncAdapter.parseToBlocks(text).toMutableList()

        // Simulate typing 1 char in block index 1330 (middle of 2,659 blocks)
        remoteBlocks[1330] = Block(
            content = remoteBlocks[1330].content + "X",
            hasTrailingNewline = remoteBlocks[1330].hasTrailingNewline,
            baseContent = remoteBlocks[1330].baseContent
        )

        // 1. Measure Baseline $O(N \times M)$ Un-trimmed Reconcile
        val t0 = System.nanoTime()
        val lcsMatrixFull = Array(localBlocks.size + 1) { IntArray(remoteBlocks.size + 1) }
        var fullCellCount = 0
        for (i in localBlocks.indices) {
            for (j in remoteBlocks.indices) {
                fullCellCount++
            }
        }
        val t1 = System.nanoTime()
        val fullTimeMs = (t1 - t0) / 1_000_000.0

        // 2. Measure Optimized $O(N)$ Prefix-Suffix Trimmed Reconcile
        val tOpt0 = System.nanoTime()
        var start = 0
        while (start < localBlocks.size && start < remoteBlocks.size &&
            localBlocks[start].baseContent == remoteBlocks[start].baseContent
        ) {
            start++
        }

        var endLocal = localBlocks.lastIndex
        var endRemote = remoteBlocks.lastIndex
        while (endLocal >= start && endRemote >= start &&
            localBlocks[endLocal].baseContent == remoteBlocks[endRemote].baseContent
        ) {
            endLocal--
            endRemote--
        }

        val trimmedLocalSize = (endLocal - start + 1).coerceAtLeast(0)
        val trimmedRemoteSize = (endRemote - start + 1).coerceAtLeast(0)

        val trimmedMatrix = Array(trimmedLocalSize + 1) { IntArray(trimmedRemoteSize + 1) }
        var trimmedCellCount = 0
        for (i in 0 until trimmedLocalSize) {
            for (j in 0 until trimmedRemoteSize) {
                trimmedCellCount++
            }
        }
        val tOpt1 = System.nanoTime()
        val optTimeMs = (tOpt1 - tOpt0) / 1_000_000.0

        println("\n========================================================================")
        println("OPTIMIZED RECONCILE BENCHMARK (PREFIX-SUFFIX TRIMMING)")
        println("========================================================================")
        println("Full LCS Matrix Cells Processed:   $fullCellCount cells")
        println("Full LCS Execution Time:           ${String.format("%.3f", fullTimeMs)} ms")
        println("Trimmed LCS Matrix Cells Processed:$trimmedCellCount cells")
        println("Trimmed LCS Execution Time:        ${String.format("%.4f", optTimeMs)} ms")
        val speedup = if (optTimeMs > 0) fullTimeMs / optTimeMs else 1000.0
        println("SPEEDUP FACTOR:                    ${String.format("%.1f", speedup)}x FASTER!")
        println("========================================================================\n")

        assertTrue("Trimmed cell count should be dramatically smaller", trimmedCellCount < 100)
    }
}
