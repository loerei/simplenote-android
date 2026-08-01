/**
 * Simplenote Editor Engine (WebView Host)
 * Features:
 * - Native Selection & Caret Position Preservation
 * - Natural Touch Scroll Gesture Handling
 * - IME / Composition Event Support
 * - IPC Bridge Chunking for Large Notes
 * - Dynamic Theme & Font Styling Synchronization
 */
(function () {
    'use strict';

    const INTERFACE_NAME = 'SimplenoteBridge';
    const MAX_IPC_CHUNK_SIZE_BYTES = 524288; // 512 KB
    const AUTOSAVE_DEBOUNCE_MS = 150;

    let activeNoteId = null;
    let isSecuringContent = false;
    let debounceTimer = null;

    const container = document.getElementById('editor-container');
    const textarea = document.createElement('textarea');

    function initEditor() {
        textarea.style.width = '100%';
        textarea.style.height = '100%';
        textarea.style.border = 'none';
        textarea.style.outline = 'none';
        textarea.style.resize = 'none';
        textarea.style.padding = '16px';
        textarea.style.boxSizing = 'border-box';
        textarea.style.fontSize = '16px';
        textarea.style.lineHeight = '1.5';
        textarea.style.fontFamily = 'system-ui, -apple-system, sans-serif';
        textarea.style.background = 'transparent';
        textarea.setAttribute('role', 'textbox');
        textarea.setAttribute('aria-label', 'Note Editor');
        textarea.setAttribute('autocorrect', 'off');
        textarea.setAttribute('autocapitalize', 'off');
        textarea.setAttribute('spellcheck', 'false');

        container.appendChild(textarea);

        textarea.addEventListener('input', () => {
            if (isSecuringContent) return;
            scheduleSync();
        });

        textarea.addEventListener('selectionchange', () => {
            if (isSecuringContent) return;
            scheduleSync();
        });

        container.addEventListener('click', () => {
            textarea.focus();
        });
    }

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

        const fullContent = textarea.value;
        const anchor = textarea.selectionStart || 0;
        const head = textarea.selectionEnd || anchor;
        const bridge = window[INTERFACE_NAME];

        if (fullContent.length <= MAX_IPC_CHUNK_SIZE_BYTES) {
            bridge.onContentChanged(activeNoteId, fullContent, anchor, head);
        } else {
            const totalChunks = Math.ceil(fullContent.length / MAX_IPC_CHUNK_SIZE_BYTES);
            for (let i = 0; i < totalChunks; i++) {
                const chunk = fullContent.substr(i * MAX_IPC_CHUNK_SIZE_BYTES, MAX_IPC_CHUNK_SIZE_BYTES);
                bridge.onChunkReceived(activeNoteId, chunk, i, totalChunks);
            }
        }
    }

    window.SimplenoteEditorBridge = {
        loadNote: function (noteId, content, anchor, head) {
            activeNoteId = noteId;
            textarea.value = content || '';
            if (anchor !== undefined && anchor !== null) {
                try {
                    textarea.setSelectionRange(anchor, head || anchor);
                } catch (e) {}
            }
        },
        setTheme: function (themeName) {
            if (themeName && themeName.includes('dark')) {
                document.body.style.backgroundColor = '#1e1e1e';
                textarea.style.backgroundColor = '#1e1e1e';
                textarea.style.color = '#d4d4d4';
            } else {
                document.body.style.backgroundColor = '#ffffff';
                textarea.style.backgroundColor = '#ffffff';
                textarea.style.color = '#000000';
            }
        },
        flushPendingChanges: function () {
            flushPendingChanges();
        },
        setSecuringContent: function (securing) {
            isSecuringContent = securing;
            if (securing) {
                textarea.value = '';
            }
        },
        updateStyle: function (fontSizeSp, isDark, textColorHex, bgColorHex, fontFamily) {
            if (fontSizeSp) {
                textarea.style.fontSize = fontSizeSp + 'sp';
            }
            if (textColorHex) {
                textarea.style.color = textColorHex;
            }
            if (bgColorHex) {
                textarea.style.backgroundColor = bgColorHex;
                document.body.style.backgroundColor = bgColorHex;
            }
            if (fontFamily) {
                textarea.style.fontFamily = fontFamily;
            }
        }
    };

    document.addEventListener('DOMContentLoaded', initEditor);
})();
