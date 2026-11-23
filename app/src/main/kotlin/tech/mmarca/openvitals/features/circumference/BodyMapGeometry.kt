package tech.mmarca.openvitals.features.circumference

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import tech.mmarca.openvitals.domain.model.CircumferenceSite

/**
 * Whether the tiles go under the figure instead of beside it: when the text is large, or the
 * figure is narrower than a small phone's card leaves it (296 dp on a 360 dp screen fits beside).
 */
fun bodyMapIsCompact(widthDp: Float, fontScale: Float): Boolean =
    fontScale > CompactFontScale || widthDp < CompactWidthDp

private const val CompactFontScale = 1.3f
private const val CompactWidthDp = 280f

/** Which side of the figure a part's tile sits on. */
enum class TileSide { LEFT, RIGHT }

/**
 * Where a part's tape goes on the figure, in the drawing's viewport units, how large its tape band
 * is drawn, and the side its tile sits on.
 */
data class BodyMapSite(
    val site: CircumferenceSite,
    val anchorX: Float,
    val anchorY: Float,
    val bandHalfWidth: Float,
    val bandHalfHeight: Float,
    val side: TileSide,
)

/**
 * The figure's geometry. The anchors repeat the `anchor_<site>` markers inside
 * `res/drawable/body_map_front.xml` (drawn from `res-src/body_map_front.svg`); BodyMapAnchorTest
 * fails when the two disagree, so a change to the drawing comes with a change here.
 */
object BodyMapGeometry {
    const val ViewportWidth = 308f
    const val ViewportHeight = 470f

    /** A tile's width in viewport units; the figure scales with the screen, the tiles with it. */
    const val TileWidth = 82f

    /**
     * The figure faces the viewer: the person's right side is drawn on the screen's left, and
     * its tiles sit in the left column with the neck, chest and waist.
     */
    val sites: List<BodyMapSite> = listOf(
        BodyMapSite(CircumferenceSite.NECK, 154f, 92f, 12f, 4f, TileSide.LEFT),
        BodyMapSite(CircumferenceSite.SHOULDERS, 154f, 116f, 58f, 6f, TileSide.RIGHT),
        BodyMapSite(CircumferenceSite.CHEST, 154f, 158f, 48f, 7f, TileSide.LEFT),
        BodyMapSite(CircumferenceSite.WAIST, 154f, 226f, 32f, 6f, TileSide.LEFT),
        BodyMapSite(CircumferenceSite.HIPS, 154f, 282f, 46f, 7f, TileSide.RIGHT),
        BodyMapSite(CircumferenceSite.RIGHT_UPPER_ARM, 95f, 172f, 9f, 4f, TileSide.LEFT),
        BodyMapSite(CircumferenceSite.LEFT_UPPER_ARM, 213f, 172f, 9f, 4f, TileSide.RIGHT),
        BodyMapSite(CircumferenceSite.RIGHT_FOREARM, 94f, 245f, 8f, 4f, TileSide.LEFT),
        BodyMapSite(CircumferenceSite.LEFT_FOREARM, 214f, 245f, 8f, 4f, TileSide.RIGHT),
        BodyMapSite(CircumferenceSite.RIGHT_THIGH, 133f, 346f, 18f, 5f, TileSide.LEFT),
        BodyMapSite(CircumferenceSite.LEFT_THIGH, 175f, 346f, 18f, 5f, TileSide.RIGHT),
        BodyMapSite(CircumferenceSite.RIGHT_CALF, 134f, 410f, 13f, 4f, TileSide.LEFT),
        BodyMapSite(CircumferenceSite.LEFT_CALF, 174f, 410f, 13f, 4f, TileSide.RIGHT),
    )

    private val bySite = sites.associateBy { it.site }

    private val middleParts = listOf(
        CircumferenceSite.NECK, CircumferenceSite.SHOULDERS, CircumferenceSite.CHEST,
        CircumferenceSite.WAIST, CircumferenceSite.HIPS,
    )
    private val sidePairs = listOf(
        CircumferenceSite.RIGHT_UPPER_ARM to CircumferenceSite.LEFT_UPPER_ARM,
        CircumferenceSite.RIGHT_FOREARM to CircumferenceSite.LEFT_FOREARM,
        CircumferenceSite.RIGHT_THIGH to CircumferenceSite.LEFT_THIGH,
        CircumferenceSite.RIGHT_CALF to CircumferenceSite.LEFT_CALF,
    )

    /**
     * The compact grid's rows: the middle parts two by two, then each right part beside its left,
     * on the same sides as on the figure.
     */
    fun gridRows(): List<List<CircumferenceSite>> =
        middleParts.chunked(2) + sidePairs.map { (right, left) -> listOf(right, left) }

    fun site(site: CircumferenceSite): BodyMapSite = bySite.getValue(site)

    /**
     * The part whose tape band (grown by [tolerance] on every side) holds the point, nearest
     * band centre first where two overlap; null away from every band. Viewport units.
     */
    fun siteAt(x: Float, y: Float, tolerance: Float): CircumferenceSite? =
        sites.filter { site ->
            val dx = (x - site.anchorX) / (site.bandHalfWidth + tolerance)
            val dy = (y - site.anchorY) / (site.bandHalfHeight + tolerance)
            dx * dx + dy * dy <= 1f
        }.minByOrNull { site ->
            val dx = x - site.anchorX
            val dy = y - site.anchorY
            dx * dx + dy * dy
        }?.site

    /**
     * Tile tops for tiles that want to be centred on [wantedCenters] (top to bottom), pushed apart
     * so that none overlaps the next by less than [gap] and all stay within 0 to [maxY]. Any unit.
     */
    fun spreadTiles(wantedCenters: List<Float>, tileHeight: Float, gap: Float, maxY: Float): List<Float> {
        val tops = FloatArray(wantedCenters.size)
        wantedCenters.forEachIndexed { index, center ->
            val wanted = max(0f, center - tileHeight / 2)
            tops[index] = if (index == 0) wanted else max(wanted, tops[index - 1] + tileHeight + gap)
        }
        var limit = maxY - tileHeight
        for (index in tops.indices.reversed()) {
            tops[index] = min(tops[index], limit)
            limit = tops[index] - tileHeight - gap
        }
        return tops.toList()
    }

    /** Where a tile sits, in px. */
    data class TileBox(val left: Float, val top: Float, val width: Float, val height: Float)

    /** A straight line from a tile's inner edge to the near end of its part's band, in px. */
    data class Leader(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

    /**
     * Each part's tile beside a figure [width] px wide: its side's column, as level with its part
     * as the tiles' heights allow, [gap] px apart. Within a column the tiles take the order whose
     * leaders neither cross nor pass within [clearance] px of another part's dot, the one nearest
     * top-to-bottom part order among those; the arm parts sit beside the column, so plain
     * top-to-bottom order would cross the chest and waist lines.
     */
    fun placeTiles(heights: Map<CircumferenceSite, Float>, width: Float, gap: Float, clearance: Float): Map<CircumferenceSite, TileBox> {
        val scale = width / ViewportWidth
        val tileWidth = TileWidth * scale
        val placed = mutableMapOf<CircumferenceSite, TileBox>()
        TileSide.entries.forEach { side ->
            val byHeight = sites.filter { it.side == side && it.site in heights }.sortedBy { it.anchorY }
            if (byHeight.isEmpty()) return@forEach
            placed += bestColumn(byHeight, heights, side, width, tileWidth, gap, scale, clearance)
        }
        return placed
    }

    /** At most [MaxOrderedColumn] tiles are reordered (5040 orders); a longer column keeps part order. */
    private fun bestColumn(
        byHeight: List<BodyMapSite>,
        heights: Map<CircumferenceSite, Float>,
        side: TileSide,
        width: Float,
        tileWidth: Float,
        gap: Float,
        scale: Float,
        clearance: Float,
    ): Map<CircumferenceSite, TileBox> {
        fun layout(order: List<BodyMapSite>) = column(order, heights, side, width, tileWidth, gap, scale)
        val natural = layout(byHeight)
        if (byHeight.size > MaxOrderedColumn || faults(natural, width, clearance) == 0) return natural
        var best = natural
        var bestScore = faults(natural, width, clearance) to 0
        permutations(byHeight).forEach { order ->
            val boxes = layout(order)
            val score = faults(boxes, width, clearance) to order.withIndex().sumOf { (i, site) -> abs(i - byHeight.indexOf(site)) }
            if (score.first < bestScore.first || (score.first == bestScore.first && score.second < bestScore.second)) {
                best = boxes
                bestScore = score
            }
        }
        return best
    }

    /** Leaders that cross, plus leaders passing within [clearance] of another part's dot. */
    private fun faults(boxes: Map<CircumferenceSite, TileBox>, width: Float, clearance: Float): Int {
        val leaders = boxes.map { (site, box) -> leader(site, box, width, clearance) }
        var count = 0
        for (a in leaders.indices) for (b in leaders.indices) {
            if (a == b) continue
            if (a < b && leaders[a].crosses(leaders[b])) count++
            if (leaders[a].distanceTo(leaders[b].endX, leaders[b].endY) < clearance) count++
        }
        return count
    }

    private fun <T> permutations(items: List<T>): Sequence<List<T>> = sequence {
        if (items.size <= 1) {
            yield(items)
            return@sequence
        }
        items.indices.forEach { index ->
            val rest = items.toMutableList().apply { removeAt(index) }
            permutations(rest).forEach { yield(listOf(items[index]) + it) }
        }
    }

    private const val MaxOrderedColumn = 7

    private fun Leader.crosses(other: Leader): Boolean {
        fun turn(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = turn(other.startX, other.startY, other.endX, other.endY, startX, startY)
        val d2 = turn(other.startX, other.startY, other.endX, other.endY, endX, endY)
        val d3 = turn(startX, startY, endX, endY, other.startX, other.startY)
        val d4 = turn(startX, startY, endX, endY, other.endX, other.endY)
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    private fun Leader.distanceTo(x: Float, y: Float): Float {
        val dx = endX - startX
        val dy = endY - startY
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((x - startX) * dx + (y - startY) * dy) / length).coerceIn(0f, 1f)
        return hypot(x - startX - t * dx, y - startY - t * dy)
    }

    private fun column(
        order: List<BodyMapSite>,
        heights: Map<CircumferenceSite, Float>,
        side: TileSide,
        width: Float,
        tileWidth: Float,
        gap: Float,
        scale: Float,
    ): Map<CircumferenceSite, TileBox> {
        val tileHeight = order.maxOf { heights.getValue(it.site) }
        val tops = spreadTiles(
            wantedCenters = order.map { it.anchorY * scale },
            tileHeight = tileHeight,
            gap = gap,
            maxY = max(ViewportHeight * scale, order.size * (tileHeight + gap)),
        )
        val left = if (side == TileSide.LEFT) 0f else width - tileWidth
        return order.withIndex().associate { (index, site) ->
            site.site to TileBox(left, tops[index], tileWidth, heights.getValue(site.site))
        }
    }

    /** The line from [box] to [site]'s band, leaving the tile level with the band where it can. */
    fun leader(site: CircumferenceSite, box: TileBox, width: Float, clearance: Float): Leader {
        val geometry = site(site)
        val scale = width / ViewportWidth
        val left = geometry.side == TileSide.LEFT
        val endX = (geometry.anchorX + if (left) -geometry.bandHalfWidth else geometry.bandHalfWidth) * scale
        val endY = geometry.anchorY * scale
        val inset = min(clearance, box.height / 2)
        return Leader(
            startX = if (left) box.left + box.width else box.left,
            startY = endY.coerceIn(box.top + inset, box.top + box.height - inset),
            endX = endX,
            endY = endY,
        )
    }
}
