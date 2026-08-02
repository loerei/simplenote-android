package com.automattic.simplenote.models

import java.util.UUID

data class Block(
    val id: String = UUID.randomUUID().toString(),
    var content: String = "",
    var hasTrailingNewline: Boolean = true,
    var baseContent: String = content
) {
    val totalLength: Int
        get() = content.length + if (hasTrailingNewline) 1 else 0
}
