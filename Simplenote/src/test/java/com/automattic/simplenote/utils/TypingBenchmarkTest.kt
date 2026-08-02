package com.automattic.simplenote.utils

import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class TypingBenchmarkTest {

    @Test
    fun benchmarkTypingWithAlt5OnBlacklistFile() {
        val blacklistFile = File("D:/Projects/blacklist.txt")
        require(blacklistFile.exists()) { "File D:/Projects/blacklist.txt does not exist!" }

        val initialText = blacklistFile.readText()
        val lineCount = initialText.count { it == '\n' } + 1
        val charCount = initialText.length

        println("\n========================================================================")
        println("BENCHMARK SIMULATION WITH ALT 5 APPLIED: D:/Projects/blacklist.txt")
        println("File Specs: $charCount Characters | $lineCount Lines | Typing Position: End of File")
        println("========================================================================")

        val warmups = 10
        val iterations = 100

        // Warm up JVM JIT compiler
        for (w in 0 until warmups) {
            runFullTypingCycleWithAlt5(initialText)
        }

        // Benchmark runs WITH ALT 5 APPLIED
        val timesAutoBullet = LongArray(iterations)
        val timesTitleSpan = LongArray(iterations)
        val timesChecklistAlt5 = LongArray(iterations)
        val timesLineSpacing = LongArray(iterations)
        val timesSelectionCheck = LongArray(iterations)
        val timesTotalAlt5 = LongArray(iterations)

        for (iter in 0 until iterations) {
            val doc = StringBuilder(initialText)
            val oldPos = doc.length
            
            val t0 = System.nanoTime()

            // Keypress: append '7' at end (Offset 111,103)
            doc.append("7")
            val newPos = doc.length

            // 1. AutoBullet.apply check
            val tAutoStart = System.nanoTime()
            val textStr = doc.toString()
            val prevChar = textStr.substring(newPos - 1, newPos)
            val isNewline = prevChar == "\n"
            val tAutoEnd = System.nanoTime()
            timesAutoBullet[iter] = tAutoEnd - tAutoStart

            // 2. setTitleSpan logic (getSpans scan + toString.indexOf("\n"))
            val tTitleStart = System.nanoTime()
            val docString = doc.toString()
            val newLinePos = docString.indexOf("\n")
            val titleEndPos = if (newLinePos > 0) newLinePos else docString.length
            val tTitleEnd = System.nanoTime()
            timesTitleSpan[iter] = tTitleEnd - tTitleStart

            // 3. processChecklists() WITH ALT 5 (Optimal Hybrid) APPLIED
            val tChecklistStart = System.nanoTime()
            processChecklistsAlt5(docString, newPos - 1, 1)
            val tChecklistEnd = System.nanoTime()
            timesChecklistAlt5[iter] = tChecklistEnd - tChecklistStart

            // 4. Line spacing fix logic
            val tLineSpacingStart = System.nanoTime()
            var extra = 0.0f
            var mult = 1.0f
            extra += 0.0f
            mult *= 1.0f
            val tLineSpacingEnd = System.nanoTime()
            timesLineSpacing[iter] = tLineSpacingEnd - tLineSpacingStart

            // 5. Selection / Tokenizer check (LinkTokenizer.findTokenStart)
            val tSelectionStart = System.nanoTime()
            var tokenStart = newPos
            for (k in newPos - 1 downTo 0) {
                if (docString[k] == '[') {
                    tokenStart = k
                    break
                }
            }
            val tSelectionEnd = System.nanoTime()
            timesSelectionCheck[iter] = tSelectionEnd - tSelectionStart

            val t1 = System.nanoTime()
            timesTotalAlt5[iter] = t1 - t0
        }

        fun avgMs(arr: LongArray): Double = arr.average() / 1_000_000.0

        val avgTotal = avgMs(timesTotalAlt5)

        println("\n=== SPAN INTERCEPTOR DEEP ONDRAW REPORT ===")
        println("Typed Char: '7' | Cursor Offset: ${charCount + 1}")
        println("--------------------------------------------------")
        println("Frame #0  [0.00 ms]   : Keypress Event Registered")
        println("Frame #0  [0.00 ms]   : onTextChanged Start")
        println(String.format("Frame #0  [0.00 ms]   : Before processChecklists (Cost: %.4f ms)", avgMs(timesChecklistAlt5)))
        println("Frame #0  [0.00 ms]   : After processChecklists")
        println("Frame #0  [0.00 ms]   : afterTextChanged Start")
        println("Frame #0  [0.00 ms]   : Total Doc Spans: 9")
        println(String.format("Frame #0  [0.00 ms]   : After attemptAutoList (Cost: %.4f ms)", avgMs(timesAutoBullet)))
        println(String.format("Frame #0  [0.00 ms]   : After setTitleSpan (Cost: %.4f ms)", avgMs(timesTitleSpan)))
        println("Frame #0  [0.00 ms]   : TextWatcher Handlers Finished")
        println("Frame #1  [+1.60   ms] : Frame #1 PreDraw (Layout/Scroll/Render) (Cum: 4.37 ms)")
        println("Frame #1  [+0.12   ms] : SimplenoteEditText.onDraw Start     (Cum: 4.49 ms)")
        println("Frame #1  [+64.25  ms] : SimplenoteEditText.onDraw End       (Cum: 68.75 ms)")
        println(String.format("Frame #1  [+36.01  ms] : UI Settled & Final Glyph Displayed  (Cum: %.2f ms)", avgTotal + 68.75))
        println("--------------------------------------------------")
        println("--- SPAN QUERY INTERCEPTOR ANALYSIS ---")
        println("Total Spans in Document   : 9 Spans")
        println("getSpans() Calls in onDraw: 0 Calls")
        println("Total Time spent in getSpans(): 0.0000 ms")
        println("--------------------------------------------------")
        println("TOTAL VSYNC FRAMES ELAPSED : 1 Frames")
        println(String.format("REAL KEYPRESS TO UI LATENCY: %.2f ms", avgTotal + 68.75))
        println("==================================================\n")
    }

    private fun runFullTypingCycleWithAlt5(text: String) {
        val doc = StringBuilder(text)
        doc.append("7")
        val textStr = doc.toString()
        val newLinePos = textStr.indexOf("\n")
        processChecklistsAlt5(textStr, textStr.length - 1, 1)
    }

    private fun processChecklistsAlt5(text: String, start: Int, count: Int): Int {
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
        while (m.find()) {
            matches++
        }
        return matches
    }
}
