package com.automattic.simplenote.utils

import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class TypingBenchmarkTestProcessChecklists {

    @Test
    fun benchmarkProcessChecklistsOnBlacklistFile() {
        val blacklistFile = File("D:/Projects/blacklist.txt")
        require(blacklistFile.exists()) { "File D:/Projects/blacklist.txt does not exist!" }

        val fullText = blacklistFile.readText()
        val lineCount = fullText.count { it == '\n' } + 1
        val charCount = fullText.length

        println("\n==========================================================================================")
        println("OPTIMIZATION BENCHMARK SIMULATION ON REAL FILE: D:/Projects/blacklist.txt")
        println("File Specs: $charCount Characters | $lineCount Lines | Typing position: End of File (Offset $charCount)")
        println("==========================================================================================")

        val warmups = 10
        val iterations = 100

        // Warmup JIT compiler
        for (w in 0 until warmups) {
            processChecklistsOriginal(fullText)
            processChecklistsAlt1LocalRange(fullText, fullText.length)
            processChecklistsAlt2FastPrecheck(fullText)
            processChecklistsAlt3IncrementalWindow(fullText, fullText.length - 1, 1)
            processChecklistsAlt4SinglePassRegex(fullText)
            processChecklistsAlt5OptimalCombined(fullText, fullText.length - 1, 1)
        }

        val timesOriginal = LongArray(iterations)
        val timesAlt1 = LongArray(iterations)
        val timesAlt2 = LongArray(iterations)
        val timesAlt3 = LongArray(iterations)
        val timesAlt4 = LongArray(iterations)
        val timesAlt5 = LongArray(iterations)

        for (iter in 0 until iterations) {
            val doc = fullText + "a" // Simulating 1 char typed at end of real file
            val newPos = doc.length

            // 0. Original (Baseline Entire Doc)
            val t0 = System.nanoTime()
            processChecklistsOriginal(doc)
            timesOriginal[iter] = System.nanoTime() - t0

            // Alt 1: Local Range Scanning (Current Paragraph)
            val t1 = System.nanoTime()
            processChecklistsAlt1LocalRange(doc, newPos)
            timesAlt1[iter] = System.nanoTime() - t1

            // Alt 2: Fast Pre-check (Early Exit if no '[' in full doc)
            val t2 = System.nanoTime()
            processChecklistsAlt2FastPrecheck(doc)
            timesAlt2[iter] = System.nanoTime() - t2

            // Alt 3: Incremental Edit Window (Start/Count from onTextChanged)
            val t3 = System.nanoTime()
            processChecklistsAlt3IncrementalWindow(doc, newPos - 1, 1)
            timesAlt3[iter] = System.nanoTime() - t3

            // Alt 4: Single Pass Unified Regex
            val t4 = System.nanoTime()
            processChecklistsAlt4SinglePassRegex(doc)
            timesAlt4[iter] = System.nanoTime() - t4

            // Alt 5: Optimal Combined Hybrid (Precheck + Edit Window + Local Paragraph)
            val t5 = System.nanoTime()
            processChecklistsAlt5OptimalCombined(doc, newPos - 1, 1)
            timesAlt5[iter] = System.nanoTime() - t5
        }

        fun avgMs(arr: LongArray): Double = arr.average() / 1_000_000.0
        fun maxMs(arr: LongArray): Double = (arr.maxOrNull() ?: 0L) / 1_000_000.0
        fun minMs(arr: LongArray): Double = (arr.minOrNull() ?: 0L) / 1_000_000.0
        fun speedup(base: Double, alt: Double): Double = if (alt > 0) base / alt else 0.0

        val avgOrig = avgMs(timesOriginal)

        println("==========================================================================================")
        println("               REAL FILE BENCHMARK RESULTS (D:/Projects/blacklist.txt)                    ")
        println("==========================================================================================")
        println(String.format("0. Original (Baseline Entire File)  : %.4f ms (min: %.4f ms, max: %.4f ms) [1.0x]", avgOrig, minMs(timesOriginal), maxMs(timesOriginal)))
        println(String.format("1. Alt 1 (Local Paragraph Scope)    : %.4f ms (min: %.4f ms, max: %.4f ms) [%.1fx FASTER]", avgMs(timesAlt1), minMs(timesAlt1), maxMs(timesAlt1), speedup(avgOrig, avgMs(timesAlt1))))
        println(String.format("2. Alt 2 (Fast Pre-check Filter)    : %.4f ms (min: %.4f ms, max: %.4f ms) [%.1fx FASTER]", avgMs(timesAlt2), minMs(timesAlt2), maxMs(timesAlt2), speedup(avgOrig, avgMs(timesAlt2))))
        println(String.format("3. Alt 3 (Incremental Edit Window)  : %.4f ms (min: %.4f ms, max: %.4f ms) [%.1fx FASTER]", avgMs(timesAlt3), minMs(timesAlt3), maxMs(timesAlt3), speedup(avgOrig, avgMs(timesAlt3))))
        println(String.format("4. Alt 4 (Single-Pass Combined Regex): %.4f ms (min: %.4f ms, max: %.4f ms) [%.1fx FASTER]", avgMs(timesAlt4), minMs(timesAlt4), maxMs(timesAlt4), speedup(avgOrig, avgMs(timesAlt4))))
        println(String.format("5. Alt 5 (OPTIMAL COMBINED HYBRID)  : %.4f ms (min: %.4f ms, max: %.4f ms) [%.1fx FASTER]", avgMs(timesAlt5), minMs(timesAlt5), maxMs(timesAlt5), speedup(avgOrig, avgMs(timesAlt5))))
        println("==========================================================================================\n")
    }

    private fun processChecklistsOriginal(text: String): Int {
        val regexChecked = "^(\\s+)?(-[ \\t]+\\[[xX]\\])"
        val regexUnchecked = "^(\\s+)?(-[ \\t]+\\[[ \\t]?\\])"

        val p1 = Pattern.compile(regexChecked, Pattern.MULTILINE)
        val m1 = p1.matcher(text)
        var matches = 0
        while (m1.find()) { matches++ }

        val p2 = Pattern.compile(regexUnchecked, Pattern.MULTILINE)
        val m2 = p2.matcher(text)
        while (m2.find()) { matches++ }
        return matches
    }

    private fun processChecklistsAlt1LocalRange(text: String, cursorPos: Int): Int {
        val safePos = cursorPos.coerceIn(0, text.length)
        var lineStart = text.lastIndexOf('\n', safePos - 1)
        lineStart = if (lineStart == -1) 0 else lineStart + 1
        var lineEnd = text.indexOf('\n', safePos)
        lineEnd = if (lineEnd == -1) text.length else lineEnd

        val lineText = text.substring(lineStart, lineEnd)
        val regexChecked = "^(\\s+)?(-[ \\t]+\\[[xX]\\])"
        val regexUnchecked = "^(\\s+)?(-[ \\t]+\\[[ \\t]?\\])"

        val p1 = Pattern.compile(regexChecked)
        val m1 = p1.matcher(lineText)
        var matches = 0
        while (m1.find()) { matches++ }

        val p2 = Pattern.compile(regexUnchecked)
        val m2 = p2.matcher(lineText)
        while (m2.find()) { matches++ }
        return matches
    }

    private fun processChecklistsAlt2FastPrecheck(text: String): Int {
        if (!text.contains("[")) {
            return 0
        }
        return processChecklistsOriginal(text)
    }

    private fun processChecklistsAlt3IncrementalWindow(text: String, start: Int, count: Int): Int {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = (start + count).coerceIn(0, text.length)

        var paraStart = text.lastIndexOf('\n', safeStart - 1)
        paraStart = if (paraStart == -1) 0 else paraStart + 1
        var paraEnd = text.indexOf('\n', safeEnd)
        paraEnd = if (paraEnd == -1) text.length else paraEnd

        val editWindow = text.substring(paraStart, paraEnd)
        val regexChecked = "^(\\s+)?(-[ \\t]+\\[[xX]\\])"
        val regexUnchecked = "^(\\s+)?(-[ \\t]+\\[[ \\t]?\\])"

        val p1 = Pattern.compile(regexChecked)
        val m1 = p1.matcher(editWindow)
        var matches = 0
        while (m1.find()) { matches++ }

        val p2 = Pattern.compile(regexUnchecked)
        val m2 = p2.matcher(editWindow)
        while (m2.find()) { matches++ }
        return matches
    }

    private fun processChecklistsAlt4SinglePassRegex(text: String): Int {
        val unifiedRegex = "^(\\s+)?(-[ \\t]+\\[[xX\\s]?\\])"
        val p = Pattern.compile(unifiedRegex, Pattern.MULTILINE)
        val m = p.matcher(text)
        var matches = 0
        while (m.find()) { matches++ }
        return matches
    }

    private fun processChecklistsAlt5OptimalCombined(text: String, start: Int, count: Int): Int {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = (start + count).coerceIn(0, text.length)

        var paraStart = text.lastIndexOf('\n', safeStart - 1)
        paraStart = if (paraStart == -1) 0 else paraStart + 1
        var paraEnd = text.indexOf('\n', safeEnd)
        paraEnd = if (paraEnd == -1) text.length else paraEnd

        val editWindow = text.substring(paraStart, paraEnd)
        if (!editWindow.contains("[")) {
            return 0
        }

        val unifiedRegex = "^(\\s+)?(-[ \\t]+\\[[xX\\s]?\\])"
        val p = Pattern.compile(unifiedRegex)
        val m = p.matcher(editWindow)
        var matches = 0
        while (m.find()) { matches++ }
        return matches
    }
}
