package app.qingjian.inputmethod.service.input

import android.os.SystemClock
import android.view.inputmethod.InputConnection
import java.util.ArrayDeque

/**
 * 输入法自己的撤销/重做记录。
 *
 * 只通过 InputConnection 的光标前后文本、commitText 与 deleteSurroundingText
 * 读写宿主文本；不假设宿主提供撤销接口，也不使用全量 ExtractedText。
 */
class InputEditHistory(
    private val limit: Int = 50,
    private val deleteMergeWindowMs: Long = 500L,
) {
    enum class Operation { INSERT, DELETE, REPLACE }
    enum class Result { SUCCESS, EMPTY, MISMATCH, FAILED }

    /** 光标前后各保留固定窗口；位置为窗口内 UTF-16 偏移，不伪装成宿主绝对偏移。 */
    data class CursorState(
        val before: String,
        val after: String,
        val beforeCursorPosition: Int = before.length,
        val afterCursorPosition: Int = 0,
    )

    /** 一次上屏/删除/替换。成对标点分别记录光标前、光标后的片段。 */
    data class Part(
        val insertedBefore: String,
        val insertedAfter: String,
        val deletedBefore: String,
        val deletedAfter: String,
    )

    data class Snapshot(
        val insertedBefore: String,
        val insertedAfter: String,
        val deletedBefore: String,
        val deletedAfter: String,
        val beforeState: CursorState,
        val afterState: CursorState,
        val operation: Operation,
        val timestamp: Long,
        /** 一次按键可能由多个连续 InputConnection 写入组成，撤销时按逆序回放。 */
        val parts: List<Part> = emptyList(),
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var groupDepth = 0
    private val groupParts = mutableListOf<Part>()
    private var groupBeforeState: CursorState? = null
    private var groupAfterState: CursorState? = null
    private var groupTimestamp = 0L

    fun beginGroup() {
        if (groupDepth++ == 0) {
            groupParts.clear()
            groupBeforeState = null
            groupAfterState = null
            groupTimestamp = SystemClock.uptimeMillis()
        }
    }

    fun endGroup() {
        if (groupDepth == 0) return
        if (--groupDepth != 0) return
        val before = groupBeforeState
        val after = groupAfterState
        val parts = groupParts.toList()
        groupParts.clear()
        groupBeforeState = null
        groupAfterState = null
        if (before == null || after == null || parts.isEmpty()) return
        val hasDelete = parts.any { it.deletedBefore.isNotEmpty() || it.deletedAfter.isNotEmpty() }
        val hasInsert = parts.any { it.insertedBefore.isNotEmpty() || it.insertedAfter.isNotEmpty() }
        pushSnapshot(
            Snapshot(
                "", "", "", "", before, after,
                when {
                    hasDelete && hasInsert -> Operation.REPLACE
                    hasDelete -> Operation.DELETE
                    else -> Operation.INSERT
                },
                groupTimestamp,
                parts,
            ),
        )
    }

    fun capture(connection: InputConnection): CursorState? = runCatching {
        val before = connection.getTextBeforeCursor(CONTEXT_UNITS, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(CONTEXT_UNITS, 0)?.toString() ?: return null
        CursorState(before, after)
    }.getOrNull()

    fun record(
        before: CursorState?,
        after: CursorState?,
        deletedBefore: String = "",
        deletedAfter: String = "",
        insertedBefore: String = "",
        insertedAfter: String = "",
        timestamp: Long = SystemClock.uptimeMillis(),
    ) {
        if (before == null || after == null) {
            // 宿主拒绝提供完整光标上下文时，宁可禁用旧历史，也不保留可能过期的撤销点。
            clear()
            return
        }
        if (deletedBefore.isEmpty() && deletedAfter.isEmpty() &&
            insertedBefore.isEmpty() && insertedAfter.isEmpty()
        ) return

        // 新编辑使撤销后的重做历史失效。
        redoStack.clear()
        val operation = when {
            deletedBefore.isNotEmpty() || deletedAfter.isNotEmpty() ->
                if (insertedBefore.isNotEmpty() || insertedAfter.isNotEmpty()) Operation.REPLACE else Operation.DELETE
            else -> Operation.INSERT
        }
        if (groupDepth > 0) {
            redoStack.clear()
            if (groupBeforeState == null) groupBeforeState = before
            groupAfterState = after
            groupParts += Part(insertedBefore, insertedAfter, deletedBefore, deletedAfter)
            groupTimestamp = timestamp
            return
        }
        pushSnapshot(
            Snapshot(
                insertedBefore, insertedAfter, deletedBefore, deletedAfter,
                before, after, operation, timestamp,
            ),
        )
    }

    fun undo(connection: InputConnection): Result {
        val edit = undoStack.peekLast() ?: return Result.EMPTY
        if (!matches(connection, edit.afterState)) {
            undoStack.removeLast()
            return Result.MISMATCH
        }
        val applied = runCatching {
            connection.beginBatchEdit()
            try {
                applyInverse(connection, edit)
            } finally {
                connection.endBatchEdit()
            }
        }.getOrDefault(false)
        if (!applied) {
            clear()
            return Result.FAILED
        }
        val restoredState = capture(connection) ?: run { clear(); return Result.FAILED }
        undoStack.removeLast()
        // 记录实际恢复后的光标上下文，供重做前再次校验。
        redoStack.addLast(edit.copy(beforeState = restoredState))
        return Result.SUCCESS
    }

    fun redo(connection: InputConnection): Result {
        val edit = redoStack.peekLast() ?: return Result.EMPTY
        if (!matches(connection, edit.beforeState)) {
            redoStack.removeLast()
            return Result.MISMATCH
        }
        val applied = runCatching {
            connection.beginBatchEdit()
            try {
                applyForward(connection, edit)
            } finally {
                connection.endBatchEdit()
            }
        }.getOrDefault(false)
        if (!applied) return Result.FAILED
        val appliedState = capture(connection) ?: run { clear(); return Result.FAILED }
        redoStack.removeLast()
        undoStack.addLast(edit.copy(afterState = appliedState))
        while (undoStack.size > limit) undoStack.removeFirst()
        return Result.SUCCESS
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        groupDepth = 0
        groupParts.clear()
        groupBeforeState = null
        groupAfterState = null
    }

    private fun pushSnapshot(next: Snapshot) {
        val previous = undoStack.peekLast()
        if (next.parts.isEmpty() && next.operation == Operation.DELETE &&
            previous != null && previous.parts.isEmpty() && previous.operation == Operation.DELETE &&
            previous.insertedBefore.isEmpty() && previous.insertedAfter.isEmpty() &&
            previous.deletedAfter.isEmpty() && next.deletedAfter.isEmpty() &&
            next.beforeState == previous.afterState &&
            next.timestamp - previous.timestamp in 0L..deleteMergeWindowMs
        ) {
            undoStack.removeLast()
            undoStack.addLast(
                previous.copy(
                    deletedBefore = next.deletedBefore + previous.deletedBefore,
                    afterState = next.afterState,
                    timestamp = next.timestamp,
                ),
            )
        } else {
            undoStack.addLast(next)
        }
        while (undoStack.size > limit) undoStack.removeFirst()
    }

    private fun matches(connection: InputConnection, expected: CursorState): Boolean {
        val actual = capture(connection) ?: return false
        return actual.before == expected.before &&
            actual.after == expected.after &&
            actual.beforeCursorPosition == expected.beforeCursorPosition &&
            actual.afterCursorPosition == expected.afterCursorPosition
    }

    private fun applyInverse(connection: InputConnection, edit: Snapshot): Boolean {
        val parts = edit.parts.ifEmpty {
            listOf(Part(edit.insertedBefore, edit.insertedAfter, edit.deletedBefore, edit.deletedAfter))
        }
        for (part in parts.asReversed()) {
            if (part.insertedBefore.isNotEmpty() || part.insertedAfter.isNotEmpty()) {
                if (!connection.deleteSurroundingText(part.insertedBefore.length, part.insertedAfter.length)) return false
            }
            if (part.deletedBefore.isNotEmpty() && !connection.commitText(part.deletedBefore, 1)) return false
            // newCursorPosition=0 将光标留在恢复的右侧片段之前（即原成对标点的中间）。
            if (part.deletedAfter.isNotEmpty() && !connection.commitText(part.deletedAfter, 0)) return false
        }
        return true
    }

    private fun applyForward(connection: InputConnection, edit: Snapshot): Boolean {
        val parts = edit.parts.ifEmpty {
            listOf(Part(edit.insertedBefore, edit.insertedAfter, edit.deletedBefore, edit.deletedAfter))
        }
        for (part in parts) {
            if (part.deletedBefore.isNotEmpty() || part.deletedAfter.isNotEmpty()) {
                if (!connection.deleteSurroundingText(part.deletedBefore.length, part.deletedAfter.length)) return false
            }
            if (part.insertedBefore.isNotEmpty() && !connection.commitText(part.insertedBefore, 1)) return false
            if (part.insertedAfter.isNotEmpty() && !connection.commitText(part.insertedAfter, 0)) return false
        }
        return true
    }

    private companion object {
        const val CONTEXT_UNITS = 256
    }
}
