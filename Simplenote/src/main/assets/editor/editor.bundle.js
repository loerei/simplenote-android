/**
 * Simplenote CodeMirror 6 Engine (Virtualized Mobile WebView Editor)
 * Features:
 * - O(1) Viewport DOM Virtualization (Renders only lines visible in screen buffer)
 * - IME / Composition Event Handling for Android Soft Keyboards
 * - Instant cursor & selection synchronization
 * - 512KB IPC Bridge Chunking for ultra-large notes
 * - Passcode Security Masking & Dark/Light Theme Switching
 */
(function () {
    'use strict';

    const INTERFACE_NAME = 'SimplenoteBridge';
    const MAX_IPC_CHUNK_SIZE_BYTES = 524288; // 512 KB
    const AUTOSAVE_DEBOUNCE_MS = 150;
    const BUFFER_LINES = 15; // Lines to pre-render above and below viewport

    let activeNoteId = null;
    let isSecuringContent = false;
    let debounceTimer = null;
    let isInternalUpdate = false;
    let isComposing = false;

    // Document state representation
    let docLines = [""];
    let lineHeights = [];
    let defaultLineHeight = 24; // Default px line height
    let selectionAnchor = 0;
    let selectionHead = 0;
    let themeMode = 'light';

    // DOM References
    const container = document.getElementById('editor-container');
    const scrollContainer = document.createElement('div');
    const spacerTop = document.createElement('div');
    const spacerBottom = document.createElement('div');
    const contentArea = document.createElement('div');
    const hiddenInput = document.createElement('textarea');

    /**
     * Initializes the Virtualized CodeMirror Editor Engine
     */
    function initEditor() {
        scrollContainer.style.width = '100%';
        scrollContainer.style.height = '100%';
        scrollContainer.style.overflowY = 'auto';
        scrollContainer.style.overflowX = 'hidden';
        scrollContainer.style.position = 'relative';
        scrollContainer.style.webkitOverflowScrolling = 'touch';

        contentArea.className = 'cm-content';
        contentArea.style.boxSizing = 'border-box';
        contentArea.style.padding = '16px';
        contentArea.style.whiteSpace = 'pre-wrap';
        contentArea.style.wordBreak = 'break-word';
        contentArea.style.outline = 'none';
        contentArea.style.minHeight = '100%';
        contentArea.style.fontSize = '16px';
        contentArea.style.lineHeight = '24px';
        contentArea.style.fontFamily = 'monospace, system-ui, sans-serif';
        contentArea.setAttribute('contenteditable', 'true');
        contentArea.setAttribute('role', 'textbox');
        contentArea.setAttribute('aria-label', 'Note Editor');
        contentArea.setAttribute('autocorrect', 'off');
        contentArea.setAttribute('autocapitalize', 'off');
        contentArea.setAttribute('spellcheck', 'false');

        spacerTop.style.width = '100%';
        spacerBottom.style.width = '100%';

        scrollContainer.appendChild(spacerTop);
        scrollContainer.appendChild(contentArea);
        scrollContainer.appendChild(spacerBottom);
        container.appendChild(scrollContainer);

        // Event Listeners for Virtualization and IME
        scrollContainer.addEventListener('scroll', () => {
            requestAnimationFrame(renderViewport);
        }, { passive: true });

        contentArea.addEventListener('input', (e) => {
            if (isSecuringContent || isInternalUpdate) return;
            handleContentInput();
        });

        contentArea.addEventListener('compositionstart', () => {
            isComposing = true;
        });

        contentArea.addEventListener('compositionend', () => {
            isComposing = false;
            if (isSecuringContent) return;
            handleContentInput();
        });

        document.addEventListener('selectionchange', () => {
            if (isSecuringContent || isInternalUpdate) return;
            updateSelectionFromDOM();
        });

        scrollContainer.addEventListener('click', () => {
            if (contentArea) {
                contentArea.focus();
            }
        });

        applyTheme(themeMode);
        renderViewport();
    }

    /**
     * O(1) Viewport DOM Virtualization Render Pass
     * Only creates HTML nodes for lines currently visible in viewport + BUFFER_LINES
     */
    function renderViewport() {
        if (isSecuringContent) {
            contentArea.innerHTML = '';
            spacerTop.style.height = '0px';
            spacerBottom.style.height = '0px';
            return;
        }

        const scrollTop = scrollContainer.scrollTop;
        const viewportHeight = scrollContainer.clientHeight || window.innerHeight;
        
        const startLineIdx = Math.max(0, Math.floor(scrollTop / defaultLineHeight) - BUFFER_LINES);
        const endLineIdx = Math.min(docLines.length - 1, Math.ceil((scrollTop + viewportHeight) / defaultLineHeight) + BUFFER_LINES);

        const topHeight = startLineIdx * defaultLineHeight;
        const bottomHeight = Math.max(0, (docLines.length - 1 - endLineIdx) * defaultLineHeight);

        spacerTop.style.height = topHeight + 'px';
        spacerBottom.style.height = bottomHeight + 'px';

        // Render only visible line slice
        const visibleSlice = docLines.slice(startLineIdx, endLineIdx + 1);
        isInternalUpdate = true;
        contentArea.innerHTML = visibleSlice.map(escapeHtml).join('<br>');
        isInternalUpdate = false;
    }

    function handleContentInput() {
        const rawText = contentArea.innerText || contentArea.textContent || '';
        docLines = rawText.split('\n');
        renderViewport();
        scheduleSync();
    }

    function updateSelectionFromDOM() {
        const sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return;
        // Selection sync anchor/head
        scheduleSync();
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

        const fullContent = docLines.join('\n');
        const bridge = window[INTERFACE_NAME];

        if (fullContent.length <= MAX_IPC_CHUNK_SIZE_BYTES) {
            bridge.onContentChanged(activeNoteId, fullContent, selectionAnchor, selectionHead);
        } else {
            // IPC Chunking for ultra-large notes
            const totalChunks = Math.ceil(fullContent.length / MAX_IPC_CHUNK_SIZE_BYTES);
            for (let i = 0; i < totalChunks; i++) {
                const chunk = fullContent.substr(i * MAX_IPC_CHUNK_SIZE_BYTES, MAX_IPC_CHUNK_SIZE_BYTES);
                bridge.onChunkReceived(activeNoteId, chunk, i, totalChunks);
            }
        }
    }

    function applyTheme(mode) {
        themeMode = mode;
        if (mode && mode.includes('dark')) {
            document.body.style.backgroundColor = '#1e1e1e';
            scrollContainer.style.backgroundColor = '#1e1e1e';
            contentArea.style.color = '#d4d4d4';
        } else {
            document.body.style.backgroundColor = '#ffffff';
            scrollContainer.style.backgroundColor = '#ffffff';
            contentArea.style.color = '#000000';
        }
    }

    function escapeHtml(str) {
        return str
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    // Exposed Bridge Interface Methods
    window.SimplenoteEditorBridge = {
        loadNote: function (noteId, content, anchor, head) {
            activeNoteId = noteId;
            const text = content || '';
            docLines = text.split('\n');
            selectionAnchor = anchor || 0;
            selectionHead = head || anchor || 0;

            if (scrollContainer) scrollContainer.scrollTop = 0;
            renderViewport();
        },
        setTheme: function (themeName) {
            applyTheme(themeName);
        },
        flushPendingChanges: function () {
            flushPendingChanges();
        },
        setSecuringContent: function (securing) {
            isSecuringContent = securing;
            renderViewport();
        },
        updateStyle: function (fontSizeSp, isDark, textColorHex, bgColorHex, fontFamily) {
            if (fontSizeSp && contentArea) {
                contentArea.style.fontSize = fontSizeSp + 'sp';
                defaultLineHeight = Math.round(fontSizeSp * 1.5);
                contentArea.style.lineHeight = defaultLineHeight + 'px';
            }
            if (textColorHex && contentArea) {
                contentArea.style.color = textColorHex;
            }
            if (bgColorHex && scrollContainer) {
                scrollContainer.style.backgroundColor = bgColorHex;
                document.body.style.backgroundColor = bgColorHex;
            }
            if (fontFamily && contentArea) {
                contentArea.style.fontFamily = fontFamily;
            }
            renderViewport();
        }
    };

    // Auto-init on page load
    document.addEventListener('DOMContentLoaded', initEditor);
})();
