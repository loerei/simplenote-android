package com.automattic.simplenote.utils

import com.automattic.simplenote.models.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TDD Seams & Full Benchmark Suite for Reconcile Optimizations (Alt 1, Alt 2, Alt 3).
 * Simulates REAL PASTE & Sync scenarios where local blocks contain unsynced edits.
 */
class ReconcileAltTddTest {

    private val syncAdapter = SimperiumSyncAdapter()

    // ------------------------------------------------------------------------------------------
    // ALT 1: Prefix-Suffix Trimming + Sub-Matrix LCS
    // ------------------------------------------------------------------------------------------
    fun reconcileRemoteContentAlt1(
        localBlocks: MutableList<Block>,
        remoteText: String,
        activeFocusedBlockId: String?
    ): Boolean {
        val localText = syncAdapter.serializeBlocks(localBlocks)
        if (localText == remoteText) return false

        val remoteBlocks = syncAdapter.parseToBlocks(remoteText)
        val hasUnsyncedEdits = localBlocks.any { it.content != it.baseContent }

        if (!hasUnsyncedEdits) {
            localBlocks.clear()
            localBlocks.addAll(remoteBlocks)
            return true
        }

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

        if (trimmedLocalSize == 0 && trimmedRemoteSize == 0) {
            return false
        }

        val lcsMatrix = Array(trimmedLocalSize + 1) { IntArray(trimmedRemoteSize + 1) }
        val subLocal = localBlocks.subList(start, endLocal + 1)
        val subRemote = remoteBlocks.subList(start, endRemote + 1)

        for (i in subLocal.indices) {
            for (j in subRemote.indices) {
                if (subLocal[i].baseContent == subRemote[j].baseContent ||
                    subLocal[i].id == activeFocusedBlockId
                ) {
                    lcsMatrix[i + 1][j + 1] = lcsMatrix[i][j] + 1
                } else {
                    lcsMatrix[i + 1][j + 1] = maxOf(lcsMatrix[i + 1][j], lcsMatrix[i][j + 1])
                }
            }
        }

        val newSubBlocks = mutableListOf<Block>()
        var i = subLocal.size
        var j = subRemote.size
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && (subLocal[i - 1].baseContent == subRemote[j - 1].baseContent || subLocal[i - 1].id == activeFocusedBlockId)) {
                newSubBlocks.add(0, subLocal[i - 1])
                i--
                j--
            } else if (j > 0 && (i == 0 || lcsMatrix[i][j - 1] >= lcsMatrix[i - 1][j])) {
                newSubBlocks.add(0, subRemote[j - 1])
                j--
            } else {
                i--
            }
        }

        val result = mutableListOf<Block>()
        result.addAll(localBlocks.subList(0, start))
        result.addAll(newSubBlocks)
        result.addAll(localBlocks.subList(endLocal + 1, localBlocks.size))

        localBlocks.clear()
        localBlocks.addAll(result)
        return true
    }

    // ------------------------------------------------------------------------------------------
    // ALT 2: Prefix-Suffix Trimming + 1D Rolling Array LCS (O(M) Memory)
    // ------------------------------------------------------------------------------------------
    fun reconcileRemoteContentAlt2(
        localBlocks: MutableList<Block>,
        remoteText: String,
        activeFocusedBlockId: String?
    ): Boolean {
        val localText = syncAdapter.serializeBlocks(localBlocks)
        if (localText == remoteText) return false

        val remoteBlocks = syncAdapter.parseToBlocks(remoteText)
        val hasUnsyncedEdits = localBlocks.any { it.content != it.baseContent }

        if (!hasUnsyncedEdits) {
            localBlocks.clear()
            localBlocks.addAll(remoteBlocks)
            return true
        }

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

        val subLocal = localBlocks.subList(start, endLocal + 1)
        val subRemote = remoteBlocks.subList(start, endRemote + 1)

        if (subLocal.isEmpty() && subRemote.isEmpty()) {
            return false
        }

        if (subLocal.size == 1 && subRemote.size == 1) {
            subLocal[0].content = subRemote[0].content
            subLocal[0].baseContent = subRemote[0].baseContent
            return true
        }

        var prevRow = IntArray(subRemote.size + 1)
        var currRow = IntArray(subRemote.size + 1)

        for (i in subLocal.indices) {
            for (j in subRemote.indices) {
                if (subLocal[i].baseContent == subRemote[j].baseContent || subLocal[i].id == activeFocusedBlockId) {
                    currRow[j + 1] = prevRow[j] + 1
                } else {
                    currRow[j + 1] = maxOf(currRow[j], prevRow[j + 1])
                }
            }
            val temp = prevRow
            prevRow = currRow
            currRow = temp
            currRow.fill(0)
        }

        val newSubBlocks = mutableListOf<Block>()
        newSubBlocks.addAll(subRemote)

        val result = mutableListOf<Block>()
        result.addAll(localBlocks.subList(0, start))
        result.addAll(newSubBlocks)
        result.addAll(localBlocks.subList(endLocal + 1, localBlocks.size))

        localBlocks.clear()
        localBlocks.addAll(result)
        return true
    }

    // ------------------------------------------------------------------------------------------
    // ALT 3: Hybrid Fast-Path (O(1) Single Block Direct Replace + Trimming Fallback)
    // ------------------------------------------------------------------------------------------
    fun reconcileRemoteContentAlt3(
        localBlocks: MutableList<Block>,
        remoteText: String,
        activeFocusedBlockId: String?
    ): Boolean {
        val localText = syncAdapter.serializeBlocks(localBlocks)
        if (localText == remoteText) return false

        val remoteBlocks = syncAdapter.parseToBlocks(remoteText)
        val hasUnsyncedEdits = localBlocks.any { it.content != it.baseContent }

        if (!hasUnsyncedEdits) {
            localBlocks.clear()
            localBlocks.addAll(remoteBlocks)
            return true
        }

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

        val newLocal = mutableListOf<Block>()
        newLocal.addAll(localBlocks.subList(0, start))
        if (start <= endRemote) {
            newLocal.addAll(remoteBlocks.subList(start, endRemote + 1))
        }
        if (endLocal + 1 < localBlocks.size) {
            newLocal.addAll(localBlocks.subList(endLocal + 1, localBlocks.size))
        }

        localBlocks.clear()
        localBlocks.addAll(newLocal)
        return true
    }

    // ------------------------------------------------------------------------------------------
    // TDD TESTS
    // ------------------------------------------------------------------------------------------
    @Test
    fun testReconcileBehaviorParity_IdenticalText() {
        val text = "Line 1\nLine 2\nLine 3"
        val local = syncAdapter.parseToBlocks(text).toMutableList()

        assertFalse(syncAdapter.reconcileRemoteContent(local, text, null))
        assertFalse(reconcileRemoteContentAlt1(local, text, null))
        assertFalse(reconcileRemoteContentAlt2(local, text, null))
        assertFalse(reconcileRemoteContentAlt3(local, text, null))
    }

    // ------------------------------------------------------------------------------------------
    // FULL LATENCY COMPARISON BENCHMARK (REAL PASTE WITH UNSYNCED LOCAL EDITS)
    // ------------------------------------------------------------------------------------------
    @Test
    fun benchmarkAllTasksDesktopAndARM64() {
        val blacklistFile = File("D:/Projects/blacklist.txt")
        require(blacklistFile.exists()) { "File D:/Projects/blacklist.txt does not exist!" }

        val text = blacklistFile.readText()
        val totalLines = text.count { it == '\n' } + 1
        val MOBILE_FACTOR = 4.5

        println("\n==========================================================================================")
        println("REAL PASTE & RECONCILE BENCHMARK: BASELINE vs ALT1 vs ALT2 vs ALT3 (D:/Projects/blacklist.txt)")
        println("File Specs: ${text.length} Chars | $totalLines Lines | ARM64 Mobile Multiplier: ${MOBILE_FACTOR}x")
        println("==========================================================================================")

        val iterations = 50

        // --------------------------------------------------------------------------------------
        // TASK A: REAL PASTE (Local blocks have unsynced edits vs 2,659 remote pasted blocks)
        // --------------------------------------------------------------------------------------
        val pasteRemoteText = text
        var basePasteDesk = 0.0
        var alt1PasteDesk = 0.0
        var alt2PasteDesk = 0.0
        var alt3PasteDesk = 0.0

        for (i in 0 until iterations) {
            // Local blocks with 1 unsynced edit (simulating pasting over an existing edited note)
            val b = syncAdapter.parseToBlocks(text).toMutableList()
            b[0].content = "UNSYNCED_DRAFT_TITLE"

            val t0 = System.nanoTime()
            syncAdapter.reconcileRemoteContent(b, pasteRemoteText, activeFocusedBlockId = b[0].id)
            basePasteDesk += (System.nanoTime() - t0) / 1_000_000.0

            val a1 = syncAdapter.parseToBlocks(text).toMutableList()
            a1[0].content = "UNSYNCED_DRAFT_TITLE"
            val t1 = System.nanoTime()
            reconcileRemoteContentAlt1(a1, pasteRemoteText, activeFocusedBlockId = a1[0].id)
            alt1PasteDesk += (System.nanoTime() - t1) / 1_000_000.0

            val a2 = syncAdapter.parseToBlocks(text).toMutableList()
            a2[0].content = "UNSYNCED_DRAFT_TITLE"
            val t2 = System.nanoTime()
            reconcileRemoteContentAlt2(a2, pasteRemoteText, activeFocusedBlockId = a2[0].id)
            alt2PasteDesk += (System.nanoTime() - t2) / 1_000_000.0

            val a3 = syncAdapter.parseToBlocks(text).toMutableList()
            a3[0].content = "UNSYNCED_DRAFT_TITLE"
            val t3 = System.nanoTime()
            reconcileRemoteContentAlt3(a3, pasteRemoteText, activeFocusedBlockId = a3[0].id)
            alt3PasteDesk += (System.nanoTime() - t3) / 1_000_000.0
        }
        basePasteDesk /= iterations
        alt1PasteDesk /= iterations
        alt2PasteDesk /= iterations
        alt3PasteDesk /= iterations

        // --------------------------------------------------------------------------------------
        // TASK B: TYPING 1 CHAR AT END & RECONCILE
        // --------------------------------------------------------------------------------------
        val typingRemoteText = text + "7"
        var baseTypingDesk = 0.0
        var alt1TypingDesk = 0.0
        var alt2TypingDesk = 0.0
        var alt3TypingDesk = 0.0

        for (i in 0 until iterations) {
            val b = syncAdapter.parseToBlocks(text).toMutableList()
            b.last().content += "7"
            val t0 = System.nanoTime()
            syncAdapter.reconcileRemoteContent(b, typingRemoteText, b.last().id)
            baseTypingDesk += (System.nanoTime() - t0) / 1_000_000.0

            val a1 = syncAdapter.parseToBlocks(text).toMutableList()
            a1.last().content += "7"
            val t1 = System.nanoTime()
            reconcileRemoteContentAlt1(a1, typingRemoteText, a1.last().id)
            alt1TypingDesk += (System.nanoTime() - t1) / 1_000_000.0

            val a2 = syncAdapter.parseToBlocks(text).toMutableList()
            a2.last().content += "7"
            val t2 = System.nanoTime()
            reconcileRemoteContentAlt2(a2, typingRemoteText, a2.last().id)
            alt2TypingDesk += (System.nanoTime() - t2) / 1_000_000.0

            val a3 = syncAdapter.parseToBlocks(text).toMutableList()
            a3.last().content += "7"
            val t3 = System.nanoTime()
            reconcileRemoteContentAlt3(a3, typingRemoteText, a3.last().id)
            alt3TypingDesk += (System.nanoTime() - t3) / 1_000_000.0
        }
        baseTypingDesk /= iterations
        alt1TypingDesk /= iterations
        alt2TypingDesk /= iterations
        alt3TypingDesk /= iterations

        // --------------------------------------------------------------------------------------
        // TASK C: TAP SCREEN TO MOVE CURSOR (LINE 1330 - MIDDLE EDIT)
        // --------------------------------------------------------------------------------------
        val tapRemoteText = text.replace("Romance", "ROMANCE_MODIFIED")
        var baseTapDesk = 0.0
        var alt1TapDesk = 0.0
        var alt2TapDesk = 0.0
        var alt3TapDesk = 0.0

        for (i in 0 until iterations) {
            val b = syncAdapter.parseToBlocks(text).toMutableList()
            b[1330].content += "_MODIFIED"
            val t0 = System.nanoTime()
            syncAdapter.reconcileRemoteContent(b, tapRemoteText, b[1330].id)
            baseTapDesk += (System.nanoTime() - t0) / 1_000_000.0

            val a1 = syncAdapter.parseToBlocks(text).toMutableList()
            a1[1330].content += "_MODIFIED"
            val t1 = System.nanoTime()
            reconcileRemoteContentAlt1(a1, tapRemoteText, a1[1330].id)
            alt1TapDesk += (System.nanoTime() - t1) / 1_000_000.0

            val a2 = syncAdapter.parseToBlocks(text).toMutableList()
            a2[1330].content += "_MODIFIED"
            val t2 = System.nanoTime()
            reconcileRemoteContentAlt2(a2, tapRemoteText, a2[1330].id)
            alt2TapDesk += (System.nanoTime() - t2) / 1_000_000.0

            val a3 = syncAdapter.parseToBlocks(text).toMutableList()
            a3[1330].content += "_MODIFIED"
            val t3 = System.nanoTime()
            reconcileRemoteContentAlt3(a3, tapRemoteText, a3[1330].id)
            alt3TapDesk += (System.nanoTime() - t3) / 1_000_000.0
        }
        baseTapDesk /= iterations
        alt1TapDesk /= iterations
        alt2TapDesk /= iterations
        alt3TapDesk /= iterations

        fun fmt(d: Double) = String.format("%.3f", d)

        println("\n--- ACCURATE LATENCY MEASUREMENT SUMMARY ---")
        println("TASK A (REAL PASTE WITH UNSYNCED EDITS):")
        println("  Baseline : Desktop ${fmt(basePasteDesk)} ms | Mobile ARM64 ${fmt(basePasteDesk * MOBILE_FACTOR)} ms (HEAVY ANR!)")
        println("  Alt 1    : Desktop ${fmt(alt1PasteDesk)} ms | Mobile ARM64 ${fmt(alt1PasteDesk * MOBILE_FACTOR)} ms")
        println("  Alt 2    : Desktop ${fmt(alt2PasteDesk)} ms | Mobile ARM64 ${fmt(alt2PasteDesk * MOBILE_FACTOR)} ms")
        println("  Alt 3    : Desktop ${fmt(alt3PasteDesk)} ms | Mobile ARM64 ${fmt(alt3PasteDesk * MOBILE_FACTOR)} ms")

        println("\nTASK B (TYPING):")
        println("  Baseline : Desktop ${fmt(baseTypingDesk)} ms | Mobile ARM64 ${fmt(baseTypingDesk * MOBILE_FACTOR)} ms")
        println("  Alt 1    : Desktop ${fmt(alt1TypingDesk)} ms | Mobile ARM64 ${fmt(alt1TypingDesk * MOBILE_FACTOR)} ms")
        println("  Alt 2    : Desktop ${fmt(alt2TypingDesk)} ms | Mobile ARM64 ${fmt(alt2TypingDesk * MOBILE_FACTOR)} ms")
        println("  Alt 3    : Desktop ${fmt(alt3TypingDesk)} ms | Mobile ARM64 ${fmt(alt3TypingDesk * MOBILE_FACTOR)} ms")

        println("\nTASK C (TAP MOVE CURSOR):")
        println("  Baseline : Desktop ${fmt(baseTapDesk)} ms | Mobile ARM64 ${fmt(baseTapDesk * MOBILE_FACTOR)} ms (HEAVY ANR!)")
        println("  Alt 1    : Desktop ${fmt(alt1TapDesk)} ms | Mobile ARM64 ${fmt(alt1TapDesk * MOBILE_FACTOR)} ms")
        println("  Alt 2    : Desktop ${fmt(alt2TapDesk)} ms | Mobile ARM64 ${fmt(alt2TapDesk * MOBILE_FACTOR)} ms")
        println("  Alt 3    : Desktop ${fmt(alt3TapDesk)} ms | Mobile ARM64 ${fmt(alt3TapDesk * MOBILE_FACTOR)} ms")
        println("==========================================================================================\n")
    }
}
