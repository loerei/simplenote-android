package com.automattic.simplenote.utils

import android.util.Log
import com.automattic.simplenote.adapters.BlockEditorConfig
import com.automattic.simplenote.models.Block

class SimperiumSyncAdapter {

    companion object {
        private const val TAG = "SIMPLENOTE_PERF_SYNC"
    }

    fun serializeBlocks(blocks: List<Block>): String {
        val t0 = System.nanoTime()
        val totalLength = blocks.sumOf { it.content.length + if (it.hasTrailingNewline) 1 else 0 }
        val sb = StringBuilder(totalLength)
        for (block in blocks) {
            sb.append(block.content)
            if (block.hasTrailingNewline) {
                sb.append("\n")
            }
        }
        val durationMs = (System.nanoTime() - t0) / 1_000_000.0
        Log.d(TAG, "[serializeBlocks] Thread: ${Thread.currentThread().name} | Blocks: ${blocks.size} | Total Chars: $totalLength | Duration: ${String.format("%.3f", durationMs)} ms")
        return sb.toString()
    }

    fun parseToBlocks(text: String): List<Block> {
        val t0 = System.nanoTime()
        if (text.isEmpty()) {
            return listOf(Block(content = "", hasTrailingNewline = false))
        }

        val result = mutableListOf<Block>()
        val lines = text.split("\n")
        var chunkLoopCount = 0

        for (i in lines.indices) {
            val line = lines[i]
            val isLastLine = (i == lines.lastIndex)
            val trailingNewline = !isLastLine

            if (line.length <= BlockEditorConfig.MAX_BLOCK_LENGTH) {
                result.add(
                    Block(
                        content = line,
                        hasTrailingNewline = trailingNewline,
                        baseContent = line
                    )
                )
            } else {
                var offset = 0
                while (offset < line.length) {
                    chunkLoopCount++
                    val remaining = line.length - offset
                    if (remaining <= BlockEditorConfig.MAX_BLOCK_LENGTH) {
                        val chunkText = line.substring(offset)
                        result.add(
                            Block(
                                content = chunkText,
                                hasTrailingNewline = trailingNewline,
                                baseContent = chunkText
                            )
                        )
                        break
                    } else {
                        val targetEnd = offset + BlockEditorConfig.MAX_BLOCK_LENGTH
                        var splitIndex = line.lastIndexOf(' ', targetEnd)
                        if (splitIndex <= offset) {
                            splitIndex = targetEnd
                        }
                        val chunkText = line.substring(offset, splitIndex)
                        result.add(
                            Block(
                                content = chunkText,
                                hasTrailingNewline = false,
                                baseContent = chunkText
                            )
                        )
                        offset = splitIndex
                    }
                }
            }
        }

        val durationMs = (System.nanoTime() - t0) / 1_000_000.0
        Log.d(TAG, "[parseToBlocks] Thread: ${Thread.currentThread().name} | Input Chars: ${text.length} | Lines: ${lines.size} | Created Blocks: ${result.size} | Chunk Loops: $chunkLoopCount | Duration: ${String.format("%.3f", durationMs)} ms")
        return result
    }

    fun reconcileRemoteContent(
        localBlocks: MutableList<Block>,
        remoteText: String,
        activeFocusedBlockId: String?
    ): Boolean {
        val t0 = System.nanoTime()
        val threadName = Thread.currentThread().name
        val initialLocalSize = localBlocks.size

        val localText = serializeBlocks(localBlocks)
        if (localText == remoteText) {
            Log.d(TAG, "[reconcileRemoteContent] Thread: $threadName | EARLY EXIT: localText == remoteText | Local Blocks: $initialLocalSize")
            return false
        }

        val remoteBlocks = parseToBlocks(remoteText)
        val hasUnsyncedEdits = localBlocks.any { it.content != it.baseContent }

        if (!hasUnsyncedEdits) {
            localBlocks.clear()
            localBlocks.addAll(remoteBlocks)
            val durationMs = (System.nanoTime() - t0) / 1_000_000.0
            Log.d(TAG, "[reconcileRemoteContent] Thread: $threadName | FAST REPLACEMENT (!hasUnsyncedEdits) | Local Blocks: $initialLocalSize -> ${localBlocks.size} | Duration: ${String.format("%.3f", durationMs)} ms")
            return true
        }

        // Step 1: Trim matching prefix blocks
        var start = 0
        while (start < localBlocks.size && start < remoteBlocks.size &&
            localBlocks[start].baseContent == remoteBlocks[start].baseContent &&
            localBlocks[start].content == localBlocks[start].baseContent
        ) {
            start++
        }

        // Step 2: Trim matching suffix blocks
        var endLocal = localBlocks.lastIndex
        var endRemote = remoteBlocks.lastIndex
        while (endLocal >= start && endRemote >= start &&
            localBlocks[endLocal].baseContent == remoteBlocks[endRemote].baseContent &&
            localBlocks[endLocal].content == localBlocks[endLocal].baseContent
        ) {
            endLocal--
            endRemote--
        }

        val subLocal = localBlocks.subList(start, endLocal + 1)
        val subRemote = remoteBlocks.subList(start, endRemote + 1)

        Log.d(TAG, "[reconcileRemoteContent] Thread: $threadName | TRIMMING COMPLETE | Trimmed Prefix: $start | Trimmed Suffix Local: ${localBlocks.lastIndex - endLocal} | SubLocal Size: ${subLocal.size} | SubRemote Size: ${subRemote.size}")

        if (subLocal.isEmpty() && subRemote.isEmpty()) {
            val durationMs = (System.nanoTime() - t0) / 1_000_000.0
            Log.d(TAG, "[reconcileRemoteContent] Thread: $threadName | NO CHANGE AFTER TRIMMING | Duration: ${String.format("%.3f", durationMs)} ms")
            return false
        }

        // Step 3: Fast-path for single block replacement / edit
        if (subLocal.size == 1 && subRemote.size == 1) {
            val localBlock = subLocal[0]
            val remoteBlock = subRemote[0]
            if (localBlock.id == activeFocusedBlockId && localBlock.content != localBlock.baseContent) {
                localBlock.content = mergeThreeWay(localBlock.content, remoteBlock.content, localBlock.baseContent)
                localBlock.baseContent = remoteBlock.content
            } else if (localBlock.content == localBlock.baseContent) {
                localBlock.content = remoteBlock.content
                localBlock.baseContent = remoteBlock.content
                localBlock.hasTrailingNewline = remoteBlock.hasTrailingNewline
            }
            val durationMs = (System.nanoTime() - t0) / 1_000_000.0
            Log.d(TAG, "[reconcileRemoteContent] Thread: $threadName | FAST-PATH SINGLE BLOCK EDIT | Duration: ${String.format("%.3f", durationMs)} ms")
            return true
        }

        // Step 4: 1D / 2D LCS on the trimmed sub-matrix
        val lcsMatrix = Array(subLocal.size + 1) { IntArray(subRemote.size + 1) }
        var lcsCellIterations = 0
        for (i in subLocal.indices) {
            for (j in subRemote.indices) {
                lcsCellIterations++
                if (subLocal[i].baseContent == subRemote[j].baseContent ||
                    subLocal[i].content == subRemote[j].content
                ) {
                    lcsMatrix[i + 1][j + 1] = lcsMatrix[i][j] + 1
                } else {
                    lcsMatrix[i + 1][j + 1] = maxOf(lcsMatrix[i + 1][j], lcsMatrix[i][j + 1])
                }
            }
        }

        val reconciledSub = mutableListOf<Block>()
        var i = subLocal.size
        var j = subRemote.size
        val remoteMatched = IntArray(subLocal.size) { -1 }

        while (i > 0 && j > 0) {
            if (subLocal[i - 1].baseContent == subRemote[j - 1].baseContent ||
                subLocal[i - 1].content == subRemote[j - 1].content
            ) {
                remoteMatched[i - 1] = j - 1
                i--
                j--
            } else if (lcsMatrix[i - 1][j] >= lcsMatrix[i][j - 1]) {
                i--
            } else {
                j--
            }
        }

        var remoteIdx = 0
        for (localPos in subLocal.indices) {
            val localBlock = subLocal[localPos]
            val matchedRemoteIdx = remoteMatched[localPos]

            if (matchedRemoteIdx != -1) {
                while (remoteIdx < matchedRemoteIdx) {
                    reconciledSub.add(subRemote[remoteIdx])
                    remoteIdx++
                }

                val remoteBlock = subRemote[matchedRemoteIdx]
                if (localBlock.id == activeFocusedBlockId && localBlock.content != localBlock.baseContent) {
                    val merged = mergeThreeWay(localBlock.content, remoteBlock.content, localBlock.baseContent)
                    localBlock.content = merged
                    localBlock.baseContent = remoteBlock.content
                    reconciledSub.add(localBlock)
                } else if (localBlock.content != localBlock.baseContent) {
                    reconciledSub.add(localBlock)
                } else {
                    val updatedBlock = Block(
                        id = localBlock.id,
                        content = remoteBlock.content,
                        hasTrailingNewline = remoteBlock.hasTrailingNewline,
                        baseContent = remoteBlock.content
                    )
                    reconciledSub.add(updatedBlock)
                }
                remoteIdx = matchedRemoteIdx + 1
            } else {
                if (localBlock.content != localBlock.baseContent) {
                    reconciledSub.add(localBlock)
                }
            }
        }

        while (remoteIdx < subRemote.size) {
            reconciledSub.add(subRemote[remoteIdx])
            remoteIdx++
        }

        val fullResult = mutableListOf<Block>()
        fullResult.addAll(localBlocks.subList(0, start))
        fullResult.addAll(reconciledSub)
        if (endLocal + 1 < localBlocks.size) {
            fullResult.addAll(localBlocks.subList(endLocal + 1, localBlocks.size))
        }

        localBlocks.clear()
        localBlocks.addAll(fullResult)

        val durationMs = (System.nanoTime() - t0) / 1_000_000.0
        Log.d(TAG, "[reconcileRemoteContent] Thread: $threadName | FULL RECONCILE FINISHED | LCS Matrix Cells: $lcsCellIterations | Result Blocks: ${localBlocks.size} | Duration: ${String.format("%.3f", durationMs)} ms")
        return true
    }

    private fun mergeThreeWay(local: String, remote: String, base: String): String {
        if (local == remote || remote == base) return local
        if (local == base) return remote

        if (remote.startsWith(base)) {
            val addition = remote.substring(base.length)
            return local + addition
        }
        if (local.startsWith(base)) {
            val addition = local.substring(base.length)
            return remote + addition
        }

        var prefixLen = 0
        val minLen = minOf(local.length, remote.length, base.length)
        while (prefixLen < minLen && local[prefixLen] == remote[prefixLen] && local[prefixLen] == base[prefixLen]) {
            prefixLen++
        }

        val baseRem = base.substring(prefixLen)
        val localRem = local.substring(prefixLen)
        val remoteRem = remote.substring(prefixLen)

        if (localRem == baseRem) return local.substring(0, prefixLen) + remoteRem
        if (remoteRem == baseRem) return local.substring(0, prefixLen) + localRem

        return local.substring(0, prefixLen) + localRem + remoteRem
    }
}
