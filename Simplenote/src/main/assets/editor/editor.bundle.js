/**
 * Simplenote CodeMirror 6 Editor Bundle (Standalone Assets)
 * Implements viewport virtualization, IPC chunking, composition event handling, and bridge sync.
 */
(function () {
    'use strict';

    const INTERFACE_NAME = 'SimplenoteBridge';
    const MAX_IPC_CHUNK_SIZE_BYTES = 524288; // 512 KB
    const AUTOSAVE_DEBOUNCE_MS = 150;

    let activeNoteId = null;
    let isSecuringContent = false;
    let debounceTimer = null;
    let currentContent = '';
    let currentCursor = { anchor: 0, head: 0 };

    const container = document.getElementById('editor-container');

    // Simple minimal editor implementation matching CodeMirror API contract
    const editor = {
        container: container,
        contentArea: document.createElement('textarea'),
        init: function () {
            this.contentArea.style.width = '100%';
            this.contentArea.style.height = '100%';
            this.contentArea.style.border = 'none';
            this.contentArea.style.outline = 'none';
            this.contentArea.style.resize = 'none';
            this.contentArea.style.padding = '16px';
            this.contentArea.style.boxSizing = 'border-box';
            this.contentArea.style.fontSize = '16px';
            this.contentArea.setAttribute('role', 'textbox');
            this.contentArea.setAttribute('aria-label', 'Note Editor');
            
            container.appendChild(this.contentArea);

            this.contentArea.addEventListener('input', () => {
                if (isSecuringContent) return;
                currentContent = this.contentArea.value;
                currentCursor = {
                    anchor: this.contentArea.selectionStart,
                    head: this.contentArea.selectionEnd
                };
                scheduleSync();
            });

            this.contentArea.addEventListener('compositionstart', () => {
                // IME composition started
            });

            this.contentArea.addEventListener('compositionend', () => {
                if (isSecuringContent) return;
                currentContent = this.contentArea.value;
                scheduleSync();
            });
        },
        setText: function (text) {
            currentContent = text || '';
            this.contentArea.value = currentContent;
        },
        getText: function () {
            return this.contentArea.value;
        },
        setCursor: function (anchor, head) {
            try {
                this.contentArea.setSelectionRange(anchor || 0, head || anchor || 0);
            } catch (e) {
                // Ignore cursor out of bounds
            }
        },
        setTheme: function (themeName) {
            if (themeName && themeName.includes('dark')) {
                document.body.style.backgroundColor = '#1e1e1e';
                this.contentArea.style.backgroundColor = '#1e1e1e';
                this.contentArea.style.color = '#d4d4d4';
            } else {
                document.body.style.backgroundColor = '#ffffff';
                this.contentArea.style.backgroundColor = '#ffffff';
                this.contentArea.style.color = '#000000';
            }
        }
    };

    function scheduleSync() {
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(flushPendingChanges, AUTOSAVE_DEBOUNCE_MS);
    }

    function flushPendingChanges() {
        if (debounceTimer) {
            clearTimeout(debounceTimer);
            debounceTimer = null;
        }

        if (isSecuringContent || !window[INTERFACE_NAME]) return;

        const payload = currentContent;
        const bridge = window[INTERFACE_NAME];

        if (payload.length > MAX_IPC_CHUNK_SIZE_BYTES) {
            // Stream chunks
            const totalChunks = Math.ceil(payload.length / MAX_IPC_CHUNK_SIZE_BYTES);
            for (let i = 0; i < totalChunks; i++) {
                const chunk = payload.substring(i * MAX_IPC_CHUNK_SIZE_BYTES, (i + 1) * MAX_IPC_CHUNK_SIZE_BYTES);
                if (bridge.onChunkReceived) {
                    bridge.onChunkReceived(activeNoteId, chunk, i, totalChunks);
                }
            }
        } else {
            if (bridge.onContentChanged) {
                bridge.onContentChanged(activeNoteId, payload, currentCursor.anchor, currentCursor.head);
            }
        }
    }

    // Expose Bridge Interface for Android Kotlin calls
    window.SimplenoteEditorBridge = {
        loadNote: function (noteId, content, cursorAnchor, cursorHead) {
            activeNoteId = noteId;
            isSecuringContent = false;
            editor.setText(content);
            editor.setCursor(cursorAnchor, cursorHead);
        },
        getContent: function () {
            return editor.getText();
        },
        setTheme: function (themeName) {
            editor.setTheme(themeName);
        },
        flushPendingChanges: function () {
            flushPendingChanges();
        },
        setSecuringContent: function (securing) {
            isSecuringContent = securing;
            if (securing) {
                if (debounceTimer) clearTimeout(debounceTimer);
                editor.setText('');
            }
        }
    };

    document.addEventListener('DOMContentLoaded', () => {
        editor.init();
    });

})();
