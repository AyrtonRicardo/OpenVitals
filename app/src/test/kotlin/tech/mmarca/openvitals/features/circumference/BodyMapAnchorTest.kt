package tech.mmarca.openvitals.features.circumference

import java.awt.geom.Path2D
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import tech.mmarca.openvitals.domain.model.CircumferenceSite

/**
 * The figure is a drawing a person edits; the code places tiles and tape bands from a table.
 * Each part's invisible `anchor_<site>` marker in the drawing must match the table.
 */
class BodyMapAnchorTest {

    private val drawing: Element by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(DrawingPath))
            .documentElement
    }

    private fun Element.android(name: String) = getAttributeNS(AndroidNamespace, name)

    private val anchors: Map<String, Pair<Float, Float>> by lazy {
        val paths = drawing.getElementsByTagName("path")
        (0 until paths.length).map { paths.item(it) as Element }
            .filter { it.android("name").startsWith("anchor_") }
            .associate { path ->
                val (x, y) = MovePoint.matchEntire(path.android("pathData").trim())
                    ?.destructured
                    ?: error("${path.android("name")} is not a single \"M x,y\" point")
                path.android("name") to (x.toFloat() to y.toFloat())
            }
    }

    @Test fun `every site has one anchor in the drawing and they match the table`() {
        assertEquals(
            CircumferenceSite.entries.map { "anchor_" + it.name.lowercase() }.toSet(),
            anchors.keys,
        )
        BodyMapGeometry.sites.forEach { site ->
            val (x, y) = anchors.getValue("anchor_" + site.site.name.lowercase())
            assertEquals("${site.site} x", site.anchorX, x, 0.5f)
            assertEquals("${site.site} y", site.anchorY, y, 0.5f)
        }
    }

    @Test fun `the drawing's viewport is the table's`() {
        assertEquals(BodyMapGeometry.ViewportWidth, drawing.android("viewportWidth").toFloat(), 0f)
        assertEquals(BodyMapGeometry.ViewportHeight, drawing.android("viewportHeight").toFloat(), 0f)
    }

    @Test fun `the outline stays clear of the tile columns, so no tile covers a hand or an arm`() {
        val paths = drawing.getElementsByTagName("path")
        val outline = (0 until paths.length).map { paths.item(it) as Element }
            .filterNot { it.android("name").startsWith("anchor_") }
        val left = BodyMapGeometry.TileWidth
        val right = BodyMapGeometry.ViewportWidth - BodyMapGeometry.TileWidth
        outline.forEach { path ->
            outlineXs(path.android("pathData")).forEach { x ->
                assert(x > left && x < right) { "${path.android("name")} reaches x=$x, under a tile (tiles end at $left and start at $right)" }
            }
        }
    }

    @Test fun `every anchor lies inside the outline, so its band wraps the drawn part`() {
        val paths = drawing.getElementsByTagName("path")
        val outline = (0 until paths.length).map { paths.item(it) as Element }
            .filterNot { it.android("name").startsWith("anchor_") }
            .map { outlineShape(it.android("pathData")) }
        BodyMapGeometry.sites.forEach { site ->
            assert(outline.any { it.contains(site.anchorX.toDouble(), site.anchorY.toDouble()) }) {
                "${site.site}'s anchor (${site.anchorX}, ${site.anchorY}) is outside the drawing"
            }
        }
    }

    @Test fun `the arms narrow at the elbow and the legs at the knee, so forearm and calf bands sit on something`() {
        val paths = drawing.getElementsByTagName("path")
        val outline = (0 until paths.length).map { paths.item(it) as Element }
            .filterNot { it.android("name").startsWith("anchor_") }
            .map { outlineShape(it.android("pathData")) }
        fun limb(site: CircumferenceSite): Path2D.Float {
            val anchor = BodyMapGeometry.site(site)
            return outline.single { it.contains(anchor.anchorX.toDouble(), anchor.anchorY.toDouble()) }
        }
        fun Path2D.Float.widthAt(y: Float): Float =
            (0..(BodyMapGeometry.ViewportWidth * 2).toInt()).count { contains(it / 2.0, y.toDouble()) } / 2f

        listOf(
            Triple(CircumferenceSite.RIGHT_UPPER_ARM, CircumferenceSite.RIGHT_FOREARM, ElbowY),
            Triple(CircumferenceSite.LEFT_UPPER_ARM, CircumferenceSite.LEFT_FOREARM, ElbowY),
            Triple(CircumferenceSite.RIGHT_THIGH, CircumferenceSite.RIGHT_CALF, KneeY),
            Triple(CircumferenceSite.LEFT_THIGH, CircumferenceSite.LEFT_CALF, KneeY),
        ).forEach { (upper, lower, jointY) ->
            val shape = limb(lower)
            val joint = shape.widthAt(jointY)
            assert(joint < shape.widthAt(BodyMapGeometry.site(upper).anchorY)) { "$upper is no wider than the joint ($joint)" }
            assert(joint < shape.widthAt(BodyMapGeometry.site(lower).anchorY)) { "$lower is no wider than the joint ($joint)" }
        }
    }

    /** The path as a shape; an arc is drawn as a line to its end, close enough for the head. */
    private fun outlineShape(pathData: String): Path2D.Float = Path2D.Float().apply {
        Command.findAll(pathData).forEach { match ->
            val n = Number.findAll(match.groupValues[2]).map { it.value.toFloat() }.toList()
            when (match.groupValues[1]) {
                "M" -> moveTo(n[0], n[1])
                "L" -> n.chunked(2).forEach { lineTo(it[0], it[1]) }
                "C" -> n.chunked(6).forEach { curveTo(it[0], it[1], it[2], it[3], it[4], it[5]) }
                "A" -> n.chunked(7).forEach { lineTo(it[5], it[6]) }
                "Z" -> closePath()
            }
        }
    }

    /** Every x a path passes through or bends towards; a curve stays inside its control points. */
    private fun outlineXs(pathData: String): List<Float> =
        Command.findAll(pathData).flatMap { match ->
            val numbers = Number.findAll(match.groupValues[2]).map { it.value.toFloat() }.toList()
            when (match.groupValues[1]) {
                // rx, ry, rotation, two flags, then the end point.
                "A" -> numbers.chunked(7).map { it[5] }
                "Z" -> emptyList()
                else -> numbers.chunked(2).map { it[0] }
            }
        }.toList()

    private companion object {
        const val DrawingPath = "src/main/res/drawable/body_map_front.xml"
        const val AndroidNamespace = "http://schemas.android.com/apk/res/android"
        const val ElbowY = 206f
        const val KneeY = 382f
        val Command = Regex("""([MLCAZ])([^MLCAZ]*)""")
        val Number = Regex("""-?[\d.]+""")
        val MovePoint = Regex("""M\s*(-?[\d.]+)[ ,]+(-?[\d.]+)""")
    }
}
