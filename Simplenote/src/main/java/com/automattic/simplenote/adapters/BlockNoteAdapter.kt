package com.automattic.simplenote.adapters

import android.text.Editable
import android.text.TextWatcher
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
}

class BlockNoteAdapter(
    val blocks: MutableList<Block> = mutableListOf(),
    private val onBlockContentChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<BlockNoteAdapter.BlockViewHolder>() {

    var activeFocusedBlockId: String? = null
    var pendingFocusCursorOffset: Int? = null
    var selectionManager: CrossBlockSelectionManager? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.itemAnimator = null
    }

    fun setBlocks(newBlocks: List<Block>) {
        blocks.clear()
        blocks.addAll(newBlocks)
        safeNotifyDataSetChanged()
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

                    if (currentBlock.content != newText) {
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
                                safeNotifyItemRangeInserted(currentPos + 1, insertedCount)
                            }

                            val lastBlockIndex = currentPos + insertedCount
                            val lastOffset = blocks[lastBlockIndex].content.length
                            focusBlock(lastBlockIndex, lastOffset)

                            onBlockContentChanged?.invoke()
                            return
                        }

                        currentBlock.content = newText

                        if (currentBlock.content.length > BlockEditorConfig.MAX_BLOCK_LENGTH) {
                            splitSoftChunkIfNeeded(currentPos)
                        }

                        onBlockContentChanged?.invoke()
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
                if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos !in blocks.indices) return@OnKeyListener false

                val selectionStart = editText.selectionStart
                val selectionEnd = editText.selectionEnd

                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        finishImeComposition()
                        handleEnterKey(pos, selectionStart, selectionEnd)
                        true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        if (selectionStart == 0 && selectionEnd == 0 && pos > 0) {
                            finishImeComposition()
                            handleBackspaceAtStart(pos)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_FORWARD_DEL -> {
                        val len = editText.text.length
                        if (selectionStart == len && selectionEnd == len && pos < blocks.size - 1) {
                            finishImeComposition()
                            handleForwardDeleteAtEnd(pos)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val layout = editText.layout
                        if (selectionStart >= 0 && layout != null && layout.getLineForOffset(selectionStart) == 0 && pos > 0) {
                            val prevLen = blocks[pos - 1].content.length
                            focusBlock(pos - 1, prevLen)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val layout = editText.layout
                        if (selectionStart >= 0 && layout != null && layout.getLineForOffset(selectionStart) == layout.lineCount - 1 && pos < blocks.size - 1) {
                            focusBlock(pos + 1, 0)
                            true
                        } else false
                    }
                    else -> false
                }
            }
            editText.setOnKeyListener(onKeyListener)

            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos in blocks.indices) {
                        activeFocusedBlockId = blocks[pos].id
                        blocks[pos].baseContent = blocks[pos].content
                    }
                }
            }

            if (block.id == activeFocusedBlockId) {
                editText.requestFocus()
                pendingFocusCursorOffset?.let { offset ->
                    val safeOffset = offset.coerceIn(0, editText.text.length)
                    editText.setSelection(safeOffset)
                    pendingFocusCursorOffset = null
                }
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

    fun focusBlock(position: Int, cursorOffset: Int) {
        if (position in blocks.indices) {
            val targetBlock = blocks[position]
            activeFocusedBlockId = targetBlock.id
            pendingFocusCursorOffset = cursorOffset
            safeNotifyItemChanged(position)
        }
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
        onBlockContentChanged?.invoke()
    }

    private fun handleBackspaceAtStart(pos: Int) {
        val prevBlock = blocks[pos - 1]
        val currentBlock = blocks[pos]

        if (!prevBlock.hasTrailingNewline) {
            if (prevBlock.content.isNotEmpty()) {
                prevBlock.content = prevBlock.content.dropLast(1)
                prevBlock.baseContent = prevBlock.content
                safeNotifyItemChanged(pos - 1)
                focusBlock(pos - 1, prevBlock.content.length)
                onBlockContentChanged?.invoke()
            }
            return
        }

        val prevLength = prevBlock.content.length
        val mergedContent = prevBlock.content + currentBlock.content
        if (mergedContent.length <= BlockEditorConfig.MAX_BLOCK_LENGTH) {
            prevBlock.content = mergedContent
            prevBlock.hasTrailingNewline = currentBlock.hasTrailingNewline
            prevBlock.baseContent = mergedContent

            blocks.removeAt(pos)
            safeNotifyItemChanged(pos - 1)
            safeNotifyItemRemoved(pos)
            focusBlock(pos - 1, prevLength)
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
        }
        onBlockContentChanged?.invoke()
    }

    private fun handleForwardDeleteAtEnd(pos: Int) {
        val currentBlock = blocks[pos]
        val nextBlock = blocks[pos + 1]

        if (!currentBlock.hasTrailingNewline) {
            if (nextBlock.content.isNotEmpty()) {
                nextBlock.content = nextBlock.content.drop(1)
                nextBlock.baseContent = nextBlock.content
                safeNotifyItemChanged(pos + 1)
                onBlockContentChanged?.invoke()
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
        onBlockContentChanged?.invoke()
    }

    private fun splitSoftChunkIfNeeded(startPos: Int) {
        var currentPos = startPos
        while (currentPos in blocks.indices && blocks[currentPos].content.length > BlockEditorConfig.MAX_BLOCK_LENGTH) {
            val block = blocks[currentPos]

            var splitIndex = block.content.lastIndexOf(' ', BlockEditorConfig.MAX_BLOCK_LENGTH)
            if (splitIndex <= 0) {
                splitIndex = BlockEditorConfig.MAX_BLOCK_LENGTH
            }

            val originalTrailing = block.hasTrailingNewline
            val firstPart = block.content.substring(0, splitIndex)
            val secondPart = block.content.substring(splitIndex)

            block.content = firstPart
            block.hasTrailingNewline = false
            block.baseContent = firstPart

            val newChunk = Block(
                content = secondPart,
                hasTrailingNewline = originalTrailing,
                baseContent = secondPart
            )

            blocks.add(currentPos + 1, newChunk)
            safeNotifyItemChanged(currentPos)
            safeNotifyItemInserted(currentPos + 1)

            currentPos++
        }

        val firstBlockPartLength = blocks[startPos].content.length
        val currentOffset = pendingFocusCursorOffset ?: firstBlockPartLength
        if (currentOffset > firstBlockPartLength) {
            focusBlock(startPos + 1, currentOffset - firstBlockPartLength)
        } else {
            focusBlock(startPos, currentOffset)
        }
        onBlockContentChanged?.invoke()
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
