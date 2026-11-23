package tech.mmarca.openvitals.features.circumference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.domain.model.CircumferenceSite.CHEST
import tech.mmarca.openvitals.domain.model.CircumferenceSite.HIPS
import tech.mmarca.openvitals.domain.model.CircumferenceSite.LEFT_CALF
import tech.mmarca.openvitals.domain.model.CircumferenceSite.LEFT_FOREARM
import tech.mmarca.openvitals.domain.model.CircumferenceSite.LEFT_THIGH
import tech.mmarca.openvitals.domain.model.CircumferenceSite.LEFT_UPPER_ARM
import tech.mmarca.openvitals.domain.model.CircumferenceSite.NECK
import tech.mmarca.openvitals.domain.model.CircumferenceSite.RIGHT_CALF
import tech.mmarca.openvitals.domain.model.CircumferenceSite.RIGHT_FOREARM
import tech.mmarca.openvitals.domain.model.CircumferenceSite.RIGHT_THIGH
import tech.mmarca.openvitals.domain.model.CircumferenceSite.RIGHT_UPPER_ARM
import tech.mmarca.openvitals.domain.model.CircumferenceSite.SHOULDERS
import tech.mmarca.openvitals.domain.model.CircumferenceSite.WAIST
import tech.mmarca.openvitals.domain.preferences.UnitSystem

class BodyMapGeometryTest {

    @Test fun `tiles that would overlap are pushed apart`() {
        assertEquals(
            listOf(80f, 128f, 176f),
            BodyMapGeometry.spreadTiles(listOf(100f, 110f, 120f), tileHeight = 40f, gap = 8f, maxY = 470f),
        )
    }

    @Test fun `tiles near the bottom are pushed up`() {
        assertEquals(
            listOf(382f, 430f),
            BodyMapGeometry.spreadTiles(listOf(440f, 450f), tileHeight = 40f, gap = 8f, maxY = 470f),
        )
    }

    @Test fun `tiles near the top stay inside`() {
        assertEquals(
            listOf(0f, 48f),
            BodyMapGeometry.spreadTiles(listOf(5f, 10f), tileHeight = 40f, gap = 8f, maxY = 470f),
        )
    }

    @Test fun `the person's right limb parts and neck, chest, waist sit on the screen's left`() {
        val bySide = BodyMapGeometry.sites.groupBy({ it.side }, { it.site })

        assertEquals(setOf(NECK, CHEST, WAIST, RIGHT_UPPER_ARM, RIGHT_FOREARM, RIGHT_THIGH, RIGHT_CALF), bySide.getValue(TileSide.LEFT).toSet())
        assertEquals(setOf(SHOULDERS, HIPS, LEFT_UPPER_ARM, LEFT_FOREARM, LEFT_THIGH, LEFT_CALF), bySide.getValue(TileSide.RIGHT).toSet())
    }

    @Test fun `every part has a place, and a tap on it selects that part`() {
        assertEquals(CircumferenceSite.entries.toSet(), BodyMapGeometry.sites.map { it.site }.toSet())
        BodyMapGeometry.sites.forEach { site ->
            assertEquals(site.site, BodyMapGeometry.siteAt(site.anchorX, site.anchorY, tolerance = 10f))
        }
    }

    @Test fun `a right part is drawn left of its left part, as on a figure facing you`() {
        listOf(
            RIGHT_UPPER_ARM to LEFT_UPPER_ARM,
            RIGHT_FOREARM to LEFT_FOREARM,
            RIGHT_THIGH to LEFT_THIGH,
            RIGHT_CALF to LEFT_CALF,
        ).forEach { (right, left) ->
            assertTrue("$right", BodyMapGeometry.site(right).anchorX < BodyMapGeometry.site(left).anchorX)
        }
    }

    @Test fun `a tap on a part's tape band selects that part`() {
        val waist = BodyMapGeometry.site(CircumferenceSite.WAIST)
        assertEquals(CircumferenceSite.WAIST, BodyMapGeometry.siteAt(waist.anchorX + 20f, waist.anchorY + 2f, tolerance = 10f))
    }

    @Test fun `where two bands overlap, the nearest part wins`() {
        val shoulders = BodyMapGeometry.site(SHOULDERS)
        val arm = BodyMapGeometry.site(RIGHT_UPPER_ARM)
        // Between the shoulder band's end and the upper arm's band, nearer the arm.
        assertEquals(RIGHT_UPPER_ARM, BodyMapGeometry.siteAt(arm.anchorX, arm.anchorY - 6f, tolerance = 10f))
        assertEquals(SHOULDERS, BodyMapGeometry.siteAt(shoulders.anchorX, shoulders.anchorY, tolerance = 10f))
    }

    @Test fun `a tap away from every band selects nothing`() {
        assertEquals(null, BodyMapGeometry.siteAt(10f, 10f, tolerance = 10f))
    }

    @Test fun `the figure keeps the tiles beside it on a 360 dp phone and moves them under it only when cramped`() {
        assertEquals(false, bodyMapIsCompact(widthDp = 296f, fontScale = 1f))
        assertEquals(true, bodyMapIsCompact(widthDp = 270f, fontScale = 1f))
        assertEquals(true, bodyMapIsCompact(widthDp = 400f, fontScale = 1.5f))
    }

    @Test fun `a change has a direction, and one that rounds to zero is no change`() {
        assertEquals(ChangeDirection.SMALLER, changeDirection(-1.5, UnitSystem.METRIC))
        assertEquals(ChangeDirection.LARGER, changeDirection(0.5, UnitSystem.METRIC))
        assertEquals(ChangeDirection.SAME, changeDirection(0.01, UnitSystem.METRIC))
        assertEquals(ChangeDirection.UNKNOWN, changeDirection(null, UnitSystem.METRIC))
    }

    @Test fun `in inches a change too small to show is no change, so there is no arrow over 0,0 in`() {
        // 0.1 cm is 0.04 in, shown as 0.0 in.
        assertEquals(ChangeDirection.LARGER, changeDirection(0.1, UnitSystem.METRIC))
        assertEquals(ChangeDirection.SAME, changeDirection(0.1, UnitSystem.IMPERIAL))
        assertEquals(ChangeDirection.SAME, changeDirection(-0.1, UnitSystem.IMPERIAL))
        // 0.2 cm is 0.08 in, shown as 0.1 in.
        assertEquals(ChangeDirection.LARGER, changeDirection(0.2, UnitSystem.IMPERIAL))
        assertEquals(ChangeDirection.SMALLER, changeDirection(-0.2, UnitSystem.IMPERIAL))
    }

    @Test fun `the grid puts the middle parts first, then each right part beside its left`() {
        assertEquals(
            listOf(
                listOf(NECK, SHOULDERS), listOf(CHEST, WAIST), listOf(HIPS),
                listOf(RIGHT_UPPER_ARM, LEFT_UPPER_ARM), listOf(RIGHT_FOREARM, LEFT_FOREARM),
                listOf(RIGHT_THIGH, LEFT_THIGH), listOf(RIGHT_CALF, LEFT_CALF),
            ),
            BodyMapGeometry.gridRows(),
        )
    }

    @Test fun `seven tall tiles on a small phone's figure stay apart and inside the area`() {
        // 296 dp wide is a scale of 0.96: two-line tiles 64 px tall, gap 8, figure 452 px.
        val left = BodyMapGeometry.sites.filter { it.side == TileSide.LEFT }.sortedBy { it.anchorY }
        val area = maxOf(452f, left.size * 72f)
        val tops = BodyMapGeometry.spreadTiles(left.map { it.anchorY * 0.96f }, tileHeight = 64f, gap = 8f, maxY = area)

        assertEquals(7, tops.size)
        tops.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b - a >= 72f - 0.01f) }
        assertTrue(tops.first() >= 0f && tops.last() + 64f <= area + 0.01f)
    }

    @Test fun `no leader crosses another or passes another part's dot, on any phone, empty or filled`() {
        val clearance = 6f
        layouts().forEach { (label, width, heights) ->
            val boxes = BodyMapGeometry.placeTiles(heights, width, gap = 8f, clearance = clearance)
            val leaders = boxes.mapValues { (site, box) -> BodyMapGeometry.leader(site, box, width, clearance) }
            leaders.entries.toList().let { all ->
                for (a in all.indices) for (b in all.indices) {
                    if (a == b) continue
                    val (siteA, one) = all[a]
                    val (siteB, other) = all[b]
                    if (a < b) assertFalse("$label: $siteA crosses $siteB", crosses(one, other))
                    assertTrue(
                        "$label: $siteA passes ${siteB}'s dot",
                        distance(other.endX, other.endY, one) >= clearance,
                    )
                }
            }
        }
    }

    @Test fun `tiles never overlap and stay beside the figure or below it`() {
        layouts().forEach { (label, width, heights) ->
            val boxes = BodyMapGeometry.placeTiles(heights, width, gap = 8f, clearance = 6f)
            assertEquals(label, CircumferenceSite.entries.toSet(), boxes.keys)
            boxes.values.groupBy { it.left }.values.forEach { column ->
                column.sortedBy { it.top }.zipWithNext().forEach { (upper, lower) ->
                    assertTrue("$label: $upper overlaps $lower", lower.top >= upper.top + upper.height + 8f - 0.01f)
                }
                assertTrue(label, column.all { it.top >= 0f })
            }
        }
    }

    /** Phone widths in px at density 1, and tile heights: empty, filled, with a comparison date, mixed. */
    private fun layouts(): List<Triple<String, Float, Map<CircumferenceSite, Float>>> =
        listOf(296f, 328f, 380f, 420f).flatMap { width ->
            listOf(48f, 62f, 76f, 92f).map { height ->
                Triple("width $width, tiles $height", width, CircumferenceSite.entries.associateWith { height })
            } + Triple(
                "width $width, mixed tiles",
                width,
                CircumferenceSite.entries.associateWith { listOf(48f, 76f, 92f)[it.ordinal % 3] },
            )
        }

    private fun crosses(a: BodyMapGeometry.Leader, b: BodyMapGeometry.Leader): Boolean {
        fun turn(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = turn(b.startX, b.startY, b.endX, b.endY, a.startX, a.startY)
        val d2 = turn(b.startX, b.startY, b.endX, b.endY, a.endX, a.endY)
        val d3 = turn(a.startX, a.startY, a.endX, a.endY, b.startX, b.startY)
        val d4 = turn(a.startX, a.startY, a.endX, a.endY, b.endX, b.endY)
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    private fun distance(x: Float, y: Float, line: BodyMapGeometry.Leader): Float {
        val dx = line.endX - line.startX
        val dy = line.endY - line.startY
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((x - line.startX) * dx + (y - line.startY) * dy) / length).coerceIn(0f, 1f)
        return kotlin.math.hypot(x - line.startX - t * dx, y - line.startY - t * dy)
    }
}
