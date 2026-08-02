package com.automattic.simplenote.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TypingTracer {

    private static class FrameLog {
        int frameNumber;
        double frameTimeMs;
        double cumulativeMs;
        String event;

        FrameLog(int frameNumber, double frameTimeMs, double cumulativeMs, String event) {
            this.frameNumber = frameNumber;
            this.frameTimeMs = frameTimeMs;
            this.cumulativeMs = cumulativeMs;
            this.event = event;
        }
    }

    private static long startTimeNs = 0;
    private static String currentTypedChar = "";
    private static int currentCursorPos = 0;
    private static boolean isTracing = false;

    private static int frameCount = 0;
    private static long lastFrameTimeNs = 0;
    private static final List<FrameLog> frameLogs = new ArrayList<>();
    private static ViewTreeObserver.OnPreDrawListener preDrawListener = null;
    private static Runnable settlingTimeoutRunnable = null;

    // Deep OnDraw Sub-Statistics
    private static final Map<String, Integer> spanDrawCounts = new HashMap<>();
    private static final Map<String, Long> spanDrawTimesNs = new HashMap<>();

    // Span Interceptor Statistics
    private static int totalGetSpansCalls = 0;
    private static long totalGetSpansDurationNs = 0;
    private static int totalDocumentSpansCount = 0;
    private static final Map<String, Integer> getSpansClassCounts = new HashMap<>();
    private static final Map<String, Long> getSpansClassTimesNs = new HashMap<>();

    public static synchronized void resetDrawSubStats() {
        spanDrawCounts.clear();
        spanDrawTimesNs.clear();
        totalGetSpansCalls = 0;
        totalGetSpansDurationNs = 0;
        totalDocumentSpansCount = 0;
        getSpansClassCounts.clear();
        getSpansClassTimesNs.clear();
    }

    public static synchronized void recordSpanDraw(String spanName, long timeNs) {
        if (!isTracing) return;
        int currentCount = spanDrawCounts.containsKey(spanName) ? spanDrawCounts.get(spanName) : 0;
        spanDrawCounts.put(spanName, currentCount + 1);

        long currentTime = spanDrawTimesNs.containsKey(spanName) ? spanDrawTimesNs.get(spanName) : 0L;
        spanDrawTimesNs.put(spanName, currentTime + timeNs);
    }

    public static synchronized <T> T[] interceptGetSpans(Editable editable, int start, int end, Class<T> type) {
        if (!isTracing || editable == null) {
            return editable != null ? editable.getSpans(start, end, type) : null;
        }

        long t0 = System.nanoTime();
        T[] result = editable.getSpans(start, end, type);
        long dt = System.nanoTime() - t0;

        totalGetSpansCalls++;
        totalGetSpansDurationNs += dt;

        if (totalDocumentSpansCount == 0) {
            Object[] allSpans = editable.getSpans(0, editable.length(), Object.class);
            totalDocumentSpansCount = (allSpans != null) ? allSpans.length : 0;
        }

        String className = type.getSimpleName();
        int count = getSpansClassCounts.containsKey(className) ? getSpansClassCounts.get(className) : 0;
        getSpansClassCounts.put(className, count + 1);

        long classTime = getSpansClassTimesNs.containsKey(className) ? getSpansClassTimesNs.get(className) : 0L;
        getSpansClassTimesNs.put(className, classTime + dt);

        return result;
    }

    public static synchronized void start(String typedChar, int cursorPos) {
        startTimeNs = System.nanoTime();
        lastFrameTimeNs = startTimeNs;
        currentTypedChar = typedChar;
        currentCursorPos = cursorPos;
        frameCount = 0;
        frameLogs.clear();
        resetDrawSubStats();
        isTracing = true;

        frameLogs.add(new FrameLog(0, 0.0, 0.0, "Keypress Event Registered"));
    }

    public static synchronized void mark(String label) {
        if (!isTracing) return;
        long now = System.nanoTime();
        double stepMs = (now - lastFrameTimeNs) / 1_000_000.0;
        double cumMs = (now - startTimeNs) / 1_000_000.0;
        frameLogs.add(new FrameLog(frameCount, stepMs, cumMs, label));
        lastFrameTimeNs = now;
    }

    public static synchronized void finishAndCopyToClipboard(final Context context, final View targetView) {
        if (!isTracing || context == null || targetView == null) return;
        mark("TextWatcher Handlers Finished");

        final ViewTreeObserver vto = targetView.getViewTreeObserver();
        if (!vto.isAlive()) return;

        // Clean up previous listener if any
        if (preDrawListener != null) {
            try { vto.removeOnPreDrawListener(preDrawListener); } catch (Exception ignored) {}
        }

        preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                long now = System.nanoTime();
                frameCount++;
                double stepMs = (now - lastFrameTimeNs) / 1_000_000.0;
                double cumMs = (now - startTimeNs) / 1_000_000.0;
                frameLogs.add(new FrameLog(frameCount, stepMs, cumMs, "Frame #" + frameCount + " PreDraw (Layout/Scroll/Render)"));
                lastFrameTimeNs = now;

                scheduleSettlingCheck(context, targetView, this);
                return true;
            }
        };

        vto.addOnPreDrawListener(preDrawListener);
    }

    private static synchronized void scheduleSettlingCheck(final Context context, final View targetView, final ViewTreeObserver.OnPreDrawListener listener) {
        if (settlingTimeoutRunnable != null) {
            targetView.removeCallbacks(settlingTimeoutRunnable);
        }

        settlingTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (TypingTracer.class) {
                    if (!isTracing) return;
                    isTracing = false;

                    try {
                        if (targetView.getViewTreeObserver().isAlive()) {
                            targetView.getViewTreeObserver().removeOnPreDrawListener(listener);
                        }
                    } catch (Exception ignored) {}

                    long now = System.nanoTime();
                    double stepMs = (now - lastFrameTimeNs) / 1_000_000.0;
                    double cumMs = (now - startTimeNs) / 1_000_000.0;
                    frameLogs.add(new FrameLog(frameCount, stepMs, cumMs, "UI Settled & Final Glyph Displayed"));

                    buildAndCopyReport(context);
                }
            }
        };

        targetView.postDelayed(settlingTimeoutRunnable, 100);
    }

    private static synchronized void buildAndCopyReport(Context context) {
        if (frameLogs.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("=== SPAN INTERCEPTOR DEEP ONDRAW REPORT ===\n");
        sb.append("Typed Char: '").append(currentTypedChar).append("' | Cursor Offset: ").append(currentCursorPos).append("\n");
        sb.append("--------------------------------------------------\n");

        for (FrameLog log : frameLogs) {
            if (log.frameNumber == 0) {
                sb.append(String.format("Frame #0  [0.00 ms]   : %s\n", log.event));
            } else {
                sb.append(String.format("Frame #%-2d [+%-6.2f ms] : %-35s (Cum: %.2f ms)\n", log.frameNumber, log.frameTimeMs, log.event, log.cumulativeMs));
            }
        }

        sb.append("--------------------------------------------------\n");
        sb.append("--- SPAN QUERY INTERCEPTOR ANALYSIS ---\n");
        sb.append("Total Spans in Document   : ").append(totalDocumentSpansCount).append(" Spans\n");
        sb.append("getSpans() Calls in onDraw: ").append(totalGetSpansCalls).append(" Calls\n");
        sb.append(String.format("Total Time spent in getSpans(): %.4f ms\n", totalGetSpansDurationNs / 1_000_000.0));

        if (!getSpansClassCounts.isEmpty()) {
            sb.append("\nBreakdown by Span Class Queried:\n");
            for (Map.Entry<String, Integer> entry : getSpansClassCounts.entrySet()) {
                String className = entry.getKey();
                int count = entry.getValue();
                long totalNs = getSpansClassTimesNs.containsKey(className) ? getSpansClassTimesNs.get(className) : 0L;
                double totalMs = totalNs / 1_000_000.0;
                sb.append(String.format("  - %-25s : Called %-3d times | Total: %.4f ms\n", className, count, totalMs));
            }
        }

        double totalMs = frameLogs.get(frameLogs.size() - 1).cumulativeMs;
        int totalFrames = frameCount;

        sb.append("--------------------------------------------------\n");
        sb.append(String.format("TOTAL VSYNC FRAMES ELAPSED : %d Frames\n", totalFrames));
        sb.append(String.format("REAL KEYPRESS TO UI LATENCY: %.2f ms\n", totalMs));
        sb.append("==================================================\n");

        String report = sb.toString();

        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("DeepSpanTypingTraceReport", report);
                clipboard.setPrimaryClip(clip);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
