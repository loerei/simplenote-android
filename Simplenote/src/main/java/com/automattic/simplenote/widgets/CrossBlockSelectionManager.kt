package com.automattic.simplenote.widgets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.automattic.simplenote.R
import com.automattic.simplenote.adapters.BlockEditorConfig
import com.automattic.simplenote.adapters.BlockNoteAdapter
import com.automattic.simplenote.utils.BlockPositionMapper

class CrossBlockSelectionManager(
    private val recyclerView: RecyclerView,
    private val adapter: BlockNoteAdapter,
    private val mapper: BlockPositionMapper
) : RecyclerView.OnItemTouchListener, ActionMode.Callback {

    private var isSelecting = false
    private var startBlockIndex: Int = -1
    private var startLocalOffset: Int = -1
    private var endBlockIndex: Int = -1
    private var endLocalOffset: Int = -1

    private var activeActionMode: ActionMode? = null
    private val highlightSpan = BackgroundColorSpan(0x6633b5e5)

    fun attach() {
        adapter.selectionManager = this
        recyclerView.addOnItemTouchListener(this)
    }

    fun detach() {
        adapter.selectionManager = null
        recyclerView.removeOnItemTouchListener(this)
        clearHighlights()
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) {
            if (activeActionMode != null) {
                clearSelection()
            }
        }
        return isSelecting
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (e.action == MotionEvent.ACTION_UP && !isSelecting) {
            val child = rv.findChildViewUnder(e.x, e.y)
            if (child == null && adapter.blocks.isNotEmpty()) {
                val targetPos = adapter.blocks.lastIndex
                val targetOffset = adapter.blocks[targetPos].content.length
                adapter.focusBlock(targetPos, targetOffset)

                rv.post {
                    val vh = rv.findViewHolderForAdapterPosition(targetPos) as? BlockNoteAdapter.BlockViewHolder
                    if (vh != null) {
                        vh.editText.requestFocus()
                        vh.editText.setSelection(targetOffset.coerceIn(0, vh.editText.text.length))
                        val imm = rv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(vh.editText, 0)
                    }
                }
            }
        }

        if (!isSelecting) return

        when (e.action) {
            MotionEvent.ACTION_MOVE -> {
                val child = rv.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = rv.getChildAdapterPosition(child)
                    if (pos != RecyclerView.NO_POSITION && pos in adapter.blocks.indices) {
                        val editText = child.findViewById<EditText>(R.id.block_edit_text)
                        val offset = if (editText != null && editText.layout != null) {
                            editText.getOffsetForPosition(e.x - child.left, e.y - child.top)
                        } else 0
                        updateSelection(pos, offset)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (hasSelection() && activeActionMode == null) {
                    recyclerView.startActionMode(this)
                }
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    fun startSelection(blockIndex: Int, localOffset: Int) {
        isSelecting = true
        startBlockIndex = blockIndex
        startLocalOffset = localOffset
        endBlockIndex = blockIndex
        endLocalOffset = localOffset
        updateHighlights()
    }

    fun updateSelection(blockIndex: Int, localOffset: Int) {
        if (!isSelecting) return
        endBlockIndex = blockIndex
        endLocalOffset = localOffset
        updateHighlights()
    }

    fun hasSelection(): Boolean {
        return startBlockIndex != -1 && endBlockIndex != -1 &&
                !(startBlockIndex == endBlockIndex && startLocalOffset == endLocalOffset)
    }

    fun clearSelection() {
        clearHighlights()
        isSelecting = false
        startBlockIndex = -1
        startLocalOffset = -1
        endBlockIndex = -1
        endLocalOffset = -1
        activeActionMode?.finish()
        activeActionMode = null
    }

    private fun updateHighlights() {
        clearHighlights()
        if (!hasSelection()) return
        val bounds = getNormalizedBounds()
        for (i in bounds.startBlock..bounds.endBlock) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(i) as? BlockNoteAdapter.BlockViewHolder
            viewHolder?.editText?.let { editText ->
                val text = editText.text
                if (text is Spannable) {
                    val from = if (i == bounds.startBlock) bounds.startOffset else 0
                    val to = if (i == bounds.endBlock) bounds.endOffset else text.length
                    if (from < to && from in 0..text.length && to in 0..text.length) {
                        text.setSpan(highlightSpan, from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
    }

    private fun clearHighlights() {
        for (i in adapter.blocks.indices) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(i) as? BlockNoteAdapter.BlockViewHolder
            viewHolder?.editText?.let { editText ->
                val text = editText.text
                if (text is Spannable) {
                    text.removeSpan(highlightSpan)
                }
            }
        }
    }

    fun getSelectedText(): String {
        if (!hasSelection()) return ""
        val bounds = getNormalizedBounds()
        val sb = StringBuilder()

        for (i in bounds.startBlock..bounds.endBlock) {
            val block = adapter.blocks.getOrNull(i) ?: continue
            val from = if (i == bounds.startBlock) bounds.startOffset else 0
            val to = if (i == bounds.endBlock) bounds.endOffset else block.content.length

            if (from <= to && from in 0..block.content.length && to in 0..block.content.length) {
                sb.append(block.content.substring(from, to))
            }
            if (i < bounds.endBlock && block.hasTrailingNewline) {
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    fun deleteSelectedRange() {
        if (!hasSelection()) return
        val bounds = getNormalizedBounds()
        val sBlock = bounds.startBlock
        val sOffset = bounds.startOffset
        val eBlock = bounds.endBlock
        val eOffset = bounds.endOffset

        if (sBlock == eBlock) {
            val block = adapter.blocks[sBlock]
            val prefix = block.content.substring(0, sOffset)
            val suffix = block.content.substring(eOffset)
            block.content = prefix + suffix
            block.baseContent = block.content
            adapter.notifyItemChanged(sBlock)
            adapter.focusBlock(sBlock, sOffset)
        } else {
            val firstBlock = adapter.blocks[sBlock]
            val lastBlock = adapter.blocks[eBlock]

            val prefix = firstBlock.content.substring(0, sOffset)
            val suffix = lastBlock.content.substring(eOffset)
            val replacement = prefix + suffix

            if (replacement.length <= BlockEditorConfig.MAX_BLOCK_LENGTH) {
                firstBlock.content = replacement
                firstBlock.hasTrailingNewline = lastBlock.hasTrailingNewline
                firstBlock.baseContent = replacement

                val removeCount = eBlock - sBlock
                for (i in 0 until removeCount) {
                    adapter.blocks.removeAt(sBlock + 1)
                }
                adapter.notifyItemChanged(sBlock)
                adapter.notifyItemRangeRemoved(sBlock + 1, removeCount)
                adapter.focusBlock(sBlock, sOffset)
            } else {
                var splitIndex = replacement.lastIndexOf(' ', BlockEditorConfig.MAX_BLOCK_LENGTH)
                if (splitIndex <= 0) {
                    splitIndex = BlockEditorConfig.MAX_BLOCK_LENGTH
                }

                val firstChunk = replacement.substring(0, splitIndex)
                val secondChunk = replacement.substring(splitIndex)

                firstBlock.content = firstChunk
                firstBlock.hasTrailingNewline = false
                firstBlock.baseContent = firstChunk

                val removeCount = eBlock - sBlock - 1
                for (i in 0 until removeCount) {
                    adapter.blocks.removeAt(sBlock + 1)
                }

                if (sBlock + 1 in adapter.blocks.indices) {
                    adapter.blocks[sBlock + 1].content = secondChunk
                    adapter.blocks[sBlock + 1].hasTrailingNewline = lastBlock.hasTrailingNewline
                    adapter.blocks[sBlock + 1].baseContent = secondChunk
                }

                adapter.notifyItemChanged(sBlock)
                adapter.notifyItemChanged(sBlock + 1)
                if (removeCount > 0) {
                    adapter.notifyItemRangeRemoved(sBlock + 1, removeCount)
                }
                adapter.focusBlock(sBlock, sOffset.coerceAtMost(firstChunk.length))
            }
        }
        clearSelection()
    }

    private fun getNormalizedBounds(): NormalizedBounds {
        return if (startBlockIndex < endBlockIndex ||
            (startBlockIndex == endBlockIndex && startLocalOffset <= endLocalOffset)
        ) {
            NormalizedBounds(startBlockIndex, startLocalOffset, endBlockIndex, endLocalOffset)
        } else {
            NormalizedBounds(endBlockIndex, endLocalOffset, startBlockIndex, startLocalOffset)
        }
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(Menu.NONE, android.R.id.copy, Menu.NONE, android.R.string.copy)
        menu.add(Menu.NONE, android.R.id.cut, Menu.NONE, android.R.string.cut)
        activeActionMode = mode
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val context = recyclerView.context
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return when (item.itemId) {
            android.R.id.copy -> {
                val text = getSelectedText()
                clipboard.setPrimaryClip(ClipData.newPlainText("Simplenote", text))
                mode.finish()
                true
            }
            android.R.id.cut -> {
                val text = getSelectedText()
                clipboard.setPrimaryClip(ClipData.newPlainText("Simplenote", text))
                deleteSelectedRange()
                mode.finish()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        clearHighlights()
        activeActionMode = null
    }

    internal data class NormalizedBounds(
        val startBlock: Int,
        val startOffset: Int,
        val endBlock: Int,
        val endOffset: Int
    )
}
