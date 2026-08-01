package com.automattic.simplenote.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Custom WebView component hosting the CodeMirror 6 editor engine.
 * Includes render process crash recovery and explicit lifecycle teardown
 * to prevent activity context memory leaks.
 */
class CodeMirrorEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var isDestroyedCleanly = false
    var onRenderProcessCrashListener: (() -> Unit)? = null

    init {
        configureSettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureSettings() {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRenderProcessCrashListener?.invoke()
                return true
            }
        }
    }

    fun initializeBridge(editorBridge: EditorBridge) {
        addJavascriptInterface(editorBridge, EditorBridge.JAVASCRIPT_INTERFACE_NAME)
        loadUrl("file:///android_asset/editor/editor.html")
    }

    @JvmOverloads
    fun loadNote(noteId: String, content: String, cursorAnchor: Int = 0, cursorHead: Int = 0) {
        val escapedContent = escapeJsString(content)
        val jsCall = "window.SimplenoteEditorBridge && window.SimplenoteEditorBridge.loadNote('$noteId', '$escapedContent', $cursorAnchor, $cursorHead);"
        evaluateJavascript(jsCall, null)
    }

    fun setTheme(themeName: String) {
        val jsCall = "window.SimplenoteEditorBridge && window.SimplenoteEditorBridge.setTheme('$themeName');"
        evaluateJavascript(jsCall, null)
    }

    fun flushPendingChanges() {
        evaluateJavascript("window.SimplenoteEditorBridge && window.SimplenoteEditorBridge.flushPendingChanges();", null)
    }

    fun setSecuringContent(securing: Boolean) {
        evaluateJavascript("window.SimplenoteEditorBridge && window.SimplenoteEditorBridge.setSecuringContent($securing);", null)
    }

    override fun destroy() {
        if (isDestroyedCleanly) return
        isDestroyedCleanly = true

        try {
            stopLoading()
            removeJavascriptInterface(EditorBridge.JAVASCRIPT_INTERFACE_NAME)
            loadUrl("about:blank")
            onRenderProcessCrashListener = null
        } catch (e: Exception) {
            // Ignore teardown exceptions
        }

        super.destroy()
    }

    private fun escapeJsString(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
