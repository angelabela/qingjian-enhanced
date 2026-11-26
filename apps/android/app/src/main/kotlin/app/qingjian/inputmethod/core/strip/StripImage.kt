package app.qingjian.inputmethod.core.strip

import android.graphics.RectF
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** Rust layout snapshot: geometry and hit regions only; glyphs are drawn by Android Canvas. */
class StripImage(
    val frameId: Long, val width: Int, val height: Int, val candidatesTop: Int,
    val background: Int, val candidateIndices: List<Int>, val hits: List<StripHit>, val emoji: Set<Int>,
    val preeditEdges: List<Float>, val preeditTop: Float, val preeditBottom: Float,
) {
    fun caretAt(x: Float, y: Float): Int {
        if (preeditEdges.isEmpty() || y >= maxOf(preeditBottom, candidatesTop.toFloat())) return -1
        return preeditEdges.indices.minBy { abs(preeditEdges[it] - x) }
    }
    fun caretNear(x: Float): Int = if (preeditEdges.isEmpty()) -1 else preeditEdges.indices.minBy { abs(preeditEdges[it] - x) }
    fun candidateAt(x: Float): Int = hits.firstOrNull { it.sense == null && x >= it.rect.left && x < it.rect.right }?.candidate ?: -1
    fun wordRect(index: Int): RectF? {
        val cell = hits.firstOrNull { it.candidate == index && it.sense == null }?.rect ?: return null
        val noteTop = hits.filter { it.candidate == index && it.sense != null }.minOfOrNull { it.rect.top }
        return RectF(cell.left, cell.top, cell.right, noteTop ?: cell.bottom)
    }
    companion object {
        fun parse(meta: JSONObject): StripImage {
            val hits = meta.getJSONArray("hits")
            val candidateIndicesJson = meta.getJSONArray("candidate_indices")
            val emoji = meta.getJSONArray("emoji")
            val edges = meta.optJSONArray("preedit_edges") ?: JSONArray()
            val width = meta.getInt("width")
            val height = meta.getInt("height")
            require(width > 0 && height > 0) { "Invalid strip layout size: ${width}x${height}" }
            val candidateIndices = List(candidateIndicesJson.length()) { candidateIndicesJson.getInt(it) }
            require(candidateIndices.distinct().size == candidateIndices.size && candidateIndices.all { it >= 0 }) {
                "Invalid candidate index list"
            }
            val parsedHits = List(hits.length()) { i ->
                val h = hits.getJSONObject(i)
                val x = h.getDouble("x").toFloat()
                val y = h.getDouble("y").toFloat()
                val w = h.getDouble("width").toFloat()
                val ht = h.getDouble("height").toFloat()
                require(x.isFinite() && y.isFinite() && w.isFinite() && ht.isFinite() &&
                    w > 0f && ht > 0f && x >= 0f && y >= 0f &&
                    x + w <= width + 1f && y + ht <= height + 1f) {
                    "Invalid strip hit rectangle at index $i"
                }
                StripHit(h.getInt("candidate"), if (h.isNull("sense")) null else h.getInt("sense"), RectF(x, y, x + w, y + ht))
            }
            require(candidateIndices.all { index -> parsedHits.any { it.candidate == index && it.sense == null } }) {
                "Missing primary hit rectangle for candidate"
            }
            return StripImage(
                frameId = meta.getLong("frame_id"), width = width, height = height,
                candidatesTop = meta.getInt("candidates_top"), background = meta.getLong("background").toInt(),
                candidateIndices = candidateIndices,
                hits = parsedHits,
                emoji = List(emoji.length(), emoji::getInt).toSet(),
                preeditEdges = List(edges.length()) { edges.getDouble(it).toFloat() },
                preeditTop = meta.optDouble("preedit_top").toFloat(),
                preeditBottom = meta.optDouble("preedit_bottom").toFloat(),
            )
        }
    }
}
