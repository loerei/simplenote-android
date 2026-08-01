package com.automattic.simplenote.widgets

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * JavaScript Bridge for CodeMirror 6 Editor communication.
 * All incoming JavascriptInterface callbacks execute on the "JavaBridge" background thread
 * and are explicitly dispatched onto the Main UI Thread (Looper.getMainLooper()).
 */
class EditorBridge(
    private var activeNoteId: String?,
    private val listener: OnEditorContentChangeListener
) {

    interface OnEditorContentChangeListener {
        fun onContentUpdated(noteId: String, content: String, cursorAnchor: Int, cursorHead: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chunkBuffer = StringBuilder()

    fun updateActiveNoteId(noteId: String?) {
        activeNoteId = noteId
        chunkBuffer.setLength(0)
    }

    @JavascriptInterface
    fun onContentChanged(noteId: String?, content: String?, cursorAnchor: Int, cursorHead: Int) {
        if (noteId.isNullOrEmpty() || noteId != activeNoteId || content == null) {
            return
        }

        mainHandler.post {
            if (noteId == activeNoteId) {
                listener.onContentUpdated(noteId, content, cursorAnchor, cursorHead)
            }
        }
    }

    @JavascriptInterface
    fun onChunkReceived(noteId: String?, chunk: String?, chunkIndex: Int, totalChunks: Int) {
        if (noteId.isNullOrEmpty() || noteId != activeNoteId || chunk == null) {
            return
        }

        mainHandler.post {
            if (noteId == activeNoteId) {
                if (chunkIndex == 0) {
                    chunkBuffer.setLength(0)
                }
                chunkBuffer.append(chunk)

                if (chunkIndex == totalChunks - 1) {
                    val fullContent = chunkBuffer.toString()
                    chunkBuffer.setLength(0)
                    listener.onContentUpdated(noteId, fullContent, 0, 0)
                }
            }
        }
    }

    companion object {
        const val JAVASCRIPT_INTERFACE_NAME = "SimplenoteBridge"
        const val AUTOSAVE_DEBOUNCE_MS = 150L
        const val MAX_IPC_CHUNK_SIZE_BYTES = 524288 // 512 KB
    }
}
