package com.automattic.simplenote.adapters

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.automattic.simplenote.R
import com.automattic.simplenote.models.Block
import com.automattic.simplenote.widgets.CrossBlockSelectionManager

object BlockEditorConfig {
    const val SYNC_DEBOUNCE_MS = 300L
    const val MAX_BLOCK_LENGTH = 4000
    const val UI_CHUNK_BATCH_SIZE = 50
    const val UI_FRAME_DELAY_MS = 16L
}

class BlockNoteAdapter(
    val blocks: MutableList<Block> = mutableListOf(),
    private val onBlockContentChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<BlockNoteAdapter.BlockViewHolder>() {

    companion object {
        private const val TAG = "SIMPLENOTE_PERF_ADAPTER"
        private const val TAG_CURSOR = "SIMPLENOTE_PERF_CURSOR"
    }

    var activeFocusedBlockId: String? = null
    var pendingFocusCursorOffset: Int? = null
    var selectionManager: CrossBlockSelectionManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var attachedRecyclerView: RecyclerView? = null

    // DYNAMIC ACCELERATION & CHUNK SIZE TRACKER FOR CROSS-BLOCK CONTINUOUS DELETE
    var isDeleteKeyCurrentlyPressed: Boolean = false
    var lastDeleteTimestamp: Long = 0L
    var deleteHoldStartTimestamp: Long = 0L
    var lastMergeTimestamp: Long = 0L
    var lastMeasuredDeleteIntervalMs: Long = 54L
    var lastDeleteChunkSize: Int = 1
    private var activeBridgeRunnable: Runnable? = null

    private fun updateDeleteVelocityTracker(pos: Int, selectionStart: Int) {
        val now = System.currentTimeMillis()
        if (deleteHoldStartTimestamp == 0L) {
            deleteHoldStartTimestamp = now
            Log.d(TAG_CURSOR, "[DELETE_SESSION] Giữ (0.000s) | Pos: $pos | SelStart: $selectionStart")
        } else {
            val elapsedSec = (now - deleteHoldStartTimestamp) / 1000.0
            Log.d(TAG_CURSOR, "[DELETE_SESSION] Event (+${String.format("%.3f", elapsedSec)}s) | Pos: $pos | SelStart: $selectionStart")
        }

        if (lastDeleteTimestamp > 0L) {
            val delta = now - lastDeleteTimestamp
            if (delta in 20L..200L) {
                lastMeasuredDeleteIntervalMs = delta
            }
        }
        lastDeleteTimestamp = now
        isDeleteKeyCurrentlyPressed = true
    }

    private fun stopContinuousDeleteBridge(pos: Int = -1, selectionStart: Int = -1) {
        val now = System.currentTimeMillis()
        // Ignore synthetic ACTION_UP sent by Android IME within 150ms of a block merge!
        if (lastMergeTimestamp > 0L && (now - lastMergeTimestamp) <= 150L) {
            Log.d(TAG_CURSOR, "[DELETE_SESSION] Ignored synthetic IME ACTION_UP (+${now - lastMergeTimestamp}ms after merge)")
            return
        }

        if (deleteHoldStartTimestamp > 0L) {
            val elapsedSec = (now - deleteHoldStartTimestamp) / 1000.0
            Log.d(TAG_CURSOR, "[DELETE_SESSION] Thả (+${String.format("%.3f", elapsedSec)}s) | Pos: $pos | SelStart: $selectionStart")
            deleteHoldStartTimestamp = 0L
        }
        isDeleteKeyCurrentlyPressed = false
        activeBridgeRunnable?.let { mainHandler.removeCallbacks(it) }
        activeBridgeRunnable = null
        lastDeleteTimestamp = 0L
    }

    private fun checkAndBridgeContinuousDelete(targetPos: Int, startOffset: Int) {
        val now = System.currentTimeMillis()
        val isUserHoldingDelete = isDeleteKeyCurrentlyPressed || (now - lastDeleteTimestamp) <= 250L

        if (isUserHoldingDelete && targetPos in blocks.indices && startOffset > 0) {
            activeBridgeRunnable?.let { mainHandler.removeCallbacks(it) }
            val interval = lastMeasuredDeleteIntervalMs.coerceIn(25L, 100L)
            val chunkSize = lastDeleteChunkSize.coerceIn(1, 10)
            Log.d(TAG_CURSOR, "[CONTINUOUS_DELETE_BRIDGE] Triggered for Pos: $targetPos | StartOffset: $startOffset | Interval: ${interval}ms | ChunkSize: $chunkSize")

            val runnable = object : Runnable {
                override fun run() {
                    val currentNow = System.currentTimeMillis()
                    if (isDeleteKeyCurrentlyPressed && targetPos in blocks.indices) {
                        val block = blocks[targetPos]
                        val vh = attachedRecyclerView?.findViewHolderForAdapterPosition(targetPos) as? BlockViewHolder
                        if (vh != null && vh.editText.selectionStart > 0) {
                            val currentSel = vh.editText.selectionStart
                            val editable = vh.editText.text
                            if (editable != null && currentSel <= editable.length && currentSel > 0) {
                                val deleteLen = chunkSize.coerceAtMost(currentSel)
                                editable.delete(currentSel - deleteLen, currentSel)
                                block.content = editable.toString()
                                block.baseContent = block.content
                                vh.editText.setSelection(currentSel - deleteLen)
                                val elapsedSec = if (deleteHoldStartTimestamp > 0L) (currentNow - deleteHoldStartTimestamp) / 1000.0 else 0.0
                                Log.d(TAG_CURSOR, "[DELETE_SESSION] Bridge Event (+${String.format("%.3f", elapsedSec)}s) | Pos: $targetPos | NewOffset: ${currentSel - deleteLen}")
                                mainHandler.postDelayed(this, interval)
                            }
                        }
                    } else {
                        Log.d(TAG_CURSOR, "[CONTINUOUS_DELETE_BRIDGE] Auto-stopped (User released key or timeout)")
                        stopContinuousDeleteBridge()
                    }
                }
            }
            activeBridgeRunnable = runnable
            mainHandler.postDelayed(runnable, interval)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.itemAnimator = null
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun setBlocks(newBlocks: List<Block>) {
        val t0 = System.nanoTime()
        blocks.clear()
        blocks.addAll(newBlocks)
        safeNotifyDataSetChanged()
        val durationMs = (System.nanoTime() - t0) / 1_000_000.0
        Log.d(TAG, "[setBlocks] Blocks: ${blocks.size} | Duration: ${String.format("%.3f", durationMs)} ms")
    }

    override fun getItemCount(): Int = blocks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_block_note, parent, false)
        return BlockViewHolder(view)
    }

    override fun onBindViewHolder(holder: BlockViewHolder, position: Int) {
        val block = blocks[position]
        holder.bind(block, position)
    }

    override fun onViewRecycled(holder: BlockViewHolder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    fun focusBlock(targetPosition: Int, cursorOffset: Int) {
        val t0 = System.nanoTime()
        if (targetPosition !in blocks.indices) return
        val oldActiveId = activeFocusedBlockId
        val targetBlock = blocks[targetPosition]

        activeFocusedBlockId = targetBlock.id
        pendingFocusCursorOffset = cursorOffset

        val oldPos = blocks.indexOfFirst { it.id == oldActiveId }
        if (oldPos != -1 && oldPos != targetPosition) {
            safeNotifyItemChanged(oldPos)
        }
        safeNotifyItemChanged(targetPosition)

        // SYNCHRONOUS FOCUS & CURSOR SELECTION FOR ATTACHED VIEWHOLDER
        attachedRecyclerView?.let { rv ->
            val vh = rv.findViewHolderForAdapterPosition(targetPosition) as? BlockViewHolder
            vh?.let { holder ->
                holder.editText.requestFocus()
                val safeOffset = cursorOffset.coerceIn(0, holder.editText.text.length)
                holder.editText.setSelection(safeOffset)
                Log.d(TAG_CURSOR, "[EVENT_FOCUS_SYNC] Pos: $targetPosition | TargetOffset: $cursorOffset | AppliedOffset: $safeOffset | BlockId: ${targetBlock.id}")
            }
        }

        val durationMs = (System.nanoTime() - t0) / 1_000_000.0
        Log.d(TAG, "[focusBlock] Thread: ${Thread.currentThread().name} | OldPos: $oldPos -> TargetPos: $targetPosition | CursorOffset: $cursorOffset | Duration: ${String.format("%.3f", durationMs)} ms")
    }

    private fun notifyBatchItemRangeInserted(startPosition: Int, totalInserted: Int, chunkSize: Int = BlockEditorConfig.UI_CHUNK_BATCH_SIZE) {
        val initialBatchSize = chunkSize.coerceAtMost(totalInserted)
        safeNotifyItemRangeInserted(startPosition, initialBatchSize)

        if (totalInserted > initialBatchSize) {
            var currentOffset = initialBatchSize

            fun scheduleNextBatch() {
                mainHandler.postDelayed({
                    if (currentOffset < totalInserted) {
                        val count = chunkSize.coerceAtMost(totalInserted - currentOffset)
                        safeNotifyItemRangeInserted(startPosition + currentOffset, count)
                        currentOffset += count
                        scheduleNextBatch()
                    }
                }, BlockEditorConfig.UI_FRAME_DELAY_MS)
            }
            scheduleNextBatch()
        }
    }

    inner class BlockViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val editText: EditText = itemView.findViewById(R.id.block_edit_text)
        private var textWatcher: TextWatcher? = null
        private var onKeyListener: View.OnKeyListener? = null
        private var boundBlockId: String? = null

        fun bind(block: Block, position: Int) {
            unbind()
            boundBlockId = block.id

            editText.setText(block.content)

            textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val currentPos = adapterPosition
                    if (currentPos == RecyclerView.NO_POSITION || currentPos !in blocks.indices) return
                    val currentBlock = blocks[currentPos]
                    val newText = s?.toString() ?: ""

                    // TRACK ACCELERATED DELETION CHUNK SIZE
                    if (before > 0 && count == 0) {
                        lastDeleteChunkSize = before
                    }

                    if (currentBlock.content != newText) {
                        val t0 = System.nanoTime()
                        if (newText.contains('\n')) {
                            val lines = newText.split("\n")
                            val originalTrailing = currentBlock.hasTrailingNewline
                            currentBlock.content = lines[0]
                            currentBlock.hasTrailingNewline = true
                            currentBlock.baseContent = lines[0]

                            editText.removeTextChangedListener(textWatcher)
                            editText.setText(lines[0])
                            editText.addTextChangedListener(textWatcher)

                            var insertedCount = 0
                            for (i in 1 until lines.size) {
                                val isLast = (i == lines.lastIndex)
                                val lineStr = lines[i]
                                if (lineStr.length <= BlockEditorConfig.MAX_BLOCK_LENGTH) {
                                    val newB = Block(
                                        content = lineStr,
                                        hasTrailingNewline = if (isLast) originalTrailing else true,
                                        baseContent = lineStr
                                    )
                                    blocks.add(currentPos + ++insertedCount, newB)
                                } else {
                                    var offset = 0
                                    while (offset < lineStr.length) {
                                        val remaining = lineStr.length - offset
                                        val isChunkLast = (remaining <= BlockEditorConfig.MAX_BLOCK_LENGTH)
                                        val end = if (isChunkLast) lineStr.length else {
                                            var idx = lineStr.lastIndexOf(' ', offset + BlockEditorConfig.MAX_BLOCK_LENGTH)
                                            if (idx <= offset) offset + BlockEditorConfig.MAX_BLOCK_LENGTH else idx
                                        }
                                        val chunkText = lineStr.substring(offset, end)
                                        val chunkTrailing = if (isChunkLast) (if (isLast) originalTrailing else true) else false
                                        val chunkBlock = Block(
                                            content = chunkText,
                                            hasTrailingNewline = chunkTrailing,
                                            baseContent = chunkText
                                        )
                                        blocks.add(currentPos + ++insertedCount, chunkBlock)
                                        offset = end
                                    }
                                }
                            }
                            safeNotifyItemChanged(currentPos)
                            if (insertedCount > 0) {
                                notifyBatchItemRangeInserted(currentPos + 1, insertedCount)
                            }

                            val lastBlockIndex = currentPos + insertedCount
                            val lastOffset = blocks[lastBlockIndex].content.length
                            focusBlock(lastBlockIndex, lastOffset)

                            val durationMs = (System.nanoTime() - t0) / 1_000_000.0
                            Log.d(TAG, "[onTextChanged] MULTILINE PASTE CHUNKED | Lines: ${lines.size} | InsertedBlocks: $insertedCount | Duration: ${String.format("%.3f", durationMs)} ms")

                            notifyContentChanged()
                            return
                        }

                        currentBlock.content = newText

                        if (currentBlock.content.length > BlockEditorConfig.MAX_BLOCK_LENGTH) {
                            splitSoftChunkIfNeeded(currentPos)
                        }

                        val durationMs = (System.nanoTime() - t0) / 1_000_000.0
                        Log.d(TAG, "[onTextChanged] SINGLE LINE EDIT | Pos: $currentPos | NewLength: ${newText.length} | Duration: ${String.format("%.3f", durationMs)} ms")
                        notifyContentChanged()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            }
            editText.addTextChangedListener(textWatcher)

            editText.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in blocks.indices) {
                    val selStart = editText.selectionStart.coerceAtLeast(0)
                    selectionManager?.startSelection(pos, selStart)
                }
                false
            }

            onKeyListener = View.OnKeyListener { _, keyCode, event ->
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos !in blocks.indices) return@OnKeyListener false

                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    val selectionStart = editText.selectionStart
                    val selectionEnd = editText.selectionEnd

                    if (event.action == KeyEvent.ACTION_DOWN) {
                        updateDeleteVelocityTracker(pos, selectionStart)
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        stopContinuousDeleteBridge(pos, selectionStart)
                    }

                    Log.d(TAG_CURSOR, "[EVENT_KEY_DEL] Action: ${event.action} | Pos: $pos | SelStart: $selectionStart | SelEnd: $selectionEnd | TextLen: ${editText.text.length}")

                    if (event.action == KeyEvent.ACTION_DOWN && selectionStart == 0 && selectionEnd == 0 && pos > 0) {
                        finishImeComposition()
                        handleBackspaceAtStart(pos)
                        true
                    } else false
                } else if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    val selectionStart = editText.selectionStart
                    val selectionEnd = editText.selectionEnd
                    finishImeComposition()
                    handleEnterKey(pos, selectionStart, selectionEnd)
                    true
                } else if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    val selectionStart = editText.selectionStart
                    val selectionEnd = editText.selectionEnd
                    val len = editText.text.length
                    if (selectionStart == len && selectionEnd == len && pos < blocks.size - 1) {
                        finishImeComposition()
                        handleForwardDeleteAtEnd(pos)
                        true
                    } else false
                } else false
            }
            editText.setOnKeyListener(onKeyListener)

            editText.setOnFocusChangeListener { _, hasFocus ->
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in blocks.indices) {
                    if (hasFocus) {
                        Log.d(TAG, "[onFocusChangeListener] FOCUS GAINED | Pos: $pos | BlockId: ${blocks[pos].id}")
                        activeFocusedBlockId = blocks[pos].id
                        blocks[pos].baseContent = blocks[pos].content
                    } else {
                        Log.d(TAG, "[onFocusChangeListener] FOCUS LOST | Pos: $pos | BlockId: ${blocks[pos].id}")
                    }
                }
            }

            if (block.id == activeFocusedBlockId) {
                editText.requestFocus()
                val targetOffset = pendingFocusCursorOffset ?: editText.text.length
                val safeOffset = targetOffset.coerceIn(0, editText.text.length)
                editText.setSelection(safeOffset)
                Log.d(TAG_CURSOR, "[EVENT_BIND_SELECTION] Pos: $position | SafeOffset: $safeOffset | BlockContentLen: ${editText.text.length}")
                pendingFocusCursorOffset = null
            }
        }

        private fun finishImeComposition() {
            val text = editText.text
            if (text is android.text.Spannable) {
                android.view.inputmethod.BaseInputConnection.removeComposingSpans(text)
            }
        }

        fun unbind() {
            textWatcher?.let { editText.removeTextChangedListener(it) }
            textWatcher = null
            editText.onFocusChangeListener = null
            editText.setOnKeyListener(null)
            editText.setOnLongClickListener(null)
            boundBlockId = null
        }
    }

    private fun notifyContentChanged() {
        Log.d(TAG, "[notifyContentChanged] Triggering onBlockContentChanged callback")
        onBlockContentChanged?.invoke()
    }

    private fun handleEnterKey(pos: Int, selectionStart: Int, selectionEnd: Int) {
        val block = blocks[pos]
        var text = block.content

        val sStart = selectionStart.coerceIn(0, text.length)
        val sEnd = selectionEnd.coerceIn(sStart, text.length)

        if (sStart != sEnd) {
            text = text.substring(0, sStart) + text.substring(sEnd)
        }

        val originalTrailingNewline = block.hasTrailingNewline
        block.content = text.substring(0, sStart)
        block.hasTrailingNewline = true
        block.baseContent = block.content

        val newBlock = Block(
            content = text.substring(sStart),
            hasTrailingNewline = originalTrailingNewline,
            baseContent = text.substring(sStart)
        )

        blocks.add(pos + 1, newBlock)
        safeNotifyItemChanged(pos)
        safeNotifyItemInserted(pos + 1)
        focusBlock(pos + 1, 0)
        notifyContentChanged()
    }

    fun handleBackspaceAtStart(pos: Int) {
        if (pos <= 0 || pos !in blocks.indices) return
        val prevBlock = blocks[pos - 1]
        val currentBlock = blocks[pos]
        lastMergeTimestamp = System.currentTimeMillis()

        if (!prevBlock.hasTrailingNewline) {
            if (prevBlock.content.isNotEmpty()) {
                prevBlock.content = prevBlock.content.dropLast(1)
                prevBlock.baseContent = prevBlock.content
                safeNotifyItemChanged(pos - 1)
                focusBlock(pos - 1, prevBlock.content.length)
                checkAndBridgeContinuousDelete(pos - 1, prevBlock.content.length)
                notifyContentChanged()
            }
            return
        }

        // Fast-path: When deleting at start of a block while the preceding block is an empty line, remove empty line and merge with line above!
        if (prevBlock.content.isEmpty() && pos > 1) {
            blocks.removeAt(pos - 1)
            safeNotifyItemRemoved(pos - 1)
            handleBackspaceAtStart(pos - 1)
            return
        }

        val mergedContent = prevBlock.content + currentBlock.content
        val prevLength = prevBlock.content.length

        Log.d(TAG_CURSOR, "[EVENT_MERGE_BACKSPACE] Pos: $pos -> Merging with Pos: ${pos - 1} | PrevContent: '${prevBlock.content}' | CurrentContent: '${currentBlock.content}' | TargetOffset: $prevLength")

        if (mergedContent.length <= BlockEditorConfig.MAX_BLOCK_LENGTH) {
            prevBlock.content = mergedContent
            prevBlock.hasTrailingNewline = currentBlock.hasTrailingNewline
            prevBlock.baseContent = mergedContent

            blocks.removeAt(pos)
            safeNotifyItemChanged(pos - 1)
            safeNotifyItemRemoved(pos)
            focusBlock(pos - 1, prevLength)
            checkAndBridgeContinuousDelete(pos - 1, prevLength)
        } else {
            var splitIndex = mergedContent.lastIndexOf(' ', BlockEditorConfig.MAX_BLOCK_LENGTH)
            if (splitIndex <= 0) {
                splitIndex = BlockEditorConfig.MAX_BLOCK_LENGTH
            }

            val firstChunk = mergedContent.substring(0, splitIndex)
            val secondChunk = mergedContent.substring(splitIndex)

            prevBlock.content = firstChunk
            prevBlock.hasTrailingNewline = false
            prevBlock.baseContent = firstChunk

            currentBlock.content = secondChunk
            currentBlock.baseContent = secondChunk

            safeNotifyItemChanged(pos - 1)
            safeNotifyItemChanged(pos)
            focusBlock(pos - 1, prevLength.coerceAtMost(firstChunk.length))
            checkAndBridgeContinuousDelete(pos - 1, prevLength.coerceAtMost(firstChunk.length))
        }
        notifyContentChanged()
    }

    private fun handleForwardDeleteAtEnd(pos: Int) {
        val currentBlock = blocks[pos]
        val nextBlock = blocks[pos + 1]

        if (!currentBlock.hasTrailingNewline) {
            if (nextBlock.content.isNotEmpty()) {
                nextBlock.content = nextBlock.content.substring(1)
                nextBlock.baseContent = nextBlock.content
                safeNotifyItemChanged(pos + 1)
                notifyContentChanged()
            }
            return
        }

        val mergedContent = currentBlock.content + nextBlock.content
        if (mergedContent.length <= BlockEditorConfig.MAX_BLOCK_LENGTH) {
            currentBlock.content = mergedContent
            currentBlock.hasTrailingNewline = nextBlock.hasTrailingNewline
            currentBlock.baseContent = mergedContent

            blocks.removeAt(pos + 1)
            safeNotifyItemChanged(pos)
            safeNotifyItemRemoved(pos + 1)
        } else {
            var splitIndex = mergedContent.lastIndexOf(' ', BlockEditorConfig.MAX_BLOCK_LENGTH)
            if (splitIndex <= 0) {
                splitIndex = BlockEditorConfig.MAX_BLOCK_LENGTH
            }

            val firstChunk = mergedContent.substring(0, splitIndex)
            val secondChunk = mergedContent.substring(splitIndex)

            currentBlock.content = firstChunk
            currentBlock.hasTrailingNewline = false
            currentBlock.baseContent = firstChunk

            nextBlock.content = secondChunk
            nextBlock.baseContent = secondChunk

            safeNotifyItemChanged(pos)
            safeNotifyItemChanged(pos + 1)
        }
        notifyContentChanged()
    }

    private fun splitSoftChunkIfNeeded(startPos: Int) {
        var currentPos = startPos
        var splitLoops = 0
        while (currentPos in blocks.indices && blocks[currentPos].content.length > BlockEditorConfig.MAX_BLOCK_LENGTH) {
            splitLoops++
            val block = blocks[currentPos]

            var splitIndex = block.content.lastIndexOf(' ', BlockEditorConfig.MAX_BLOCK_LENGTH)
            if (splitIndex <= 0) {
                splitIndex = BlockEditorConfig.MAX_BLOCK_LENGTH
            }

            val firstChunk = block.content.substring(0, splitIndex)
            val secondChunk = block.content.substring(splitIndex)

            val originalTrailingNewline = block.hasTrailingNewline

            block.content = firstChunk
            block.hasTrailingNewline = false
            block.baseContent = firstChunk

            val newBlock = Block(
                content = secondChunk,
                hasTrailingNewline = originalTrailingNewline,
                baseContent = secondChunk
            )

            blocks.add(currentPos + 1, newBlock)

            safeNotifyItemChanged(currentPos)
            safeNotifyItemInserted(currentPos + 1)

            currentPos++
        }

        Log.d(TAG, "[splitSoftChunkIfNeeded] StartPos: $startPos | Split Loops: $splitLoops")

        val firstBlockPartLength = blocks[startPos].content.length
        val currentOffset = pendingFocusCursorOffset ?: firstBlockPartLength
        if (currentOffset > firstBlockPartLength) {
            focusBlock(startPos + 1, currentOffset - firstBlockPartLength)
        } else {
            focusBlock(startPos, currentOffset)
        }
        notifyContentChanged()
    }

    private fun safeNotifyDataSetChanged() {
        try {
            notifyDataSetChanged()
        } catch (_: Throwable) {}
    }

    private fun safeNotifyItemChanged(position: Int) {
        try {
            notifyItemChanged(position)
        } catch (_: Throwable) {}
    }

    private fun safeNotifyItemInserted(position: Int) {
        try {
            notifyItemInserted(position)
        } catch (_: Throwable) {}
    }

    private fun safeNotifyItemRemoved(position: Int) {
        try {
            notifyItemRemoved(position)
        } catch (_: Throwable) {}
    }

    private fun safeNotifyItemRangeInserted(positionStart: Int, itemCount: Int) {
        try {
            notifyItemRangeInserted(positionStart, itemCount)
        } catch (_: Throwable) {}
    }

    private fun safeNotifyItemRangeRemoved(positionStart: Int, itemCount: Int) {
        try {
            notifyItemRangeRemoved(positionStart, itemCount)
        } catch (_: Throwable) {}
    }
}
