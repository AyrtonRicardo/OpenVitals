package tech.mmarca.openvitals.features.circumference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.domain.model.CircumferenceSite
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.theme.Spacing

/** How a part looks on the figure. */
enum class BodyMapTileState {
    /** No value yet: a dashed band and a muted hint. */
    EMPTY,

    /** A value: a solid band. */
    FILLED,

    /** Saving would replace the value this part already has on the chosen day. */
    REPLACES,
}

/**
 * One part's tile. [value] is the main line ("86.5 cm"); [detail] the small line under it (a
 * change, a date, or for an empty tile the last value). [selected] marks the part being typed or
 * looked at, whatever its [state], so a part that would be replaced still shows it is the one
 * selected. [description] is what a screen reader says.
 */
@Immutable
data class BodyMapTile(
    val site: CircumferenceSite,
    val value: String?,
    val detail: String?,
    val state: BodyMapTileState,
    val description: String,
    val selected: Boolean = false,
)

private val TileGap = 8.dp
private val BandStroke = 2.dp
private val SelectedBandStroke = 3.dp
private val LeaderStroke = 1.5.dp
private val AnchorDot = 3.dp
/** How far a leader keeps from another part's dot, and from its tile's corners. */
private val LeaderClearance = 6.dp
private val TileBorder = 1.dp
private val SelectedTileBorder = 2.dp
/** How far outside a tape band a tap still selects that part, in viewport units. */
private const val TapTolerance = 10f
private val DashIntervals = floatArrayOf(9f, 9f)

/**
 * The body figure, facing the viewer: the outline from the drawing, a tape band around each part,
 * and a tile per part beside it, joined by a line. Tiles are buttons. When they would not fit
 * beside the figure (large text, a narrow screen) they move into a grid under it, each right part
 * beside its left, and the lines are left out.
 */
@Composable
fun BodyMap(
    tiles: List<BodyMapTile>,
    onSelect: (CircumferenceSite) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = bodyMapIsCompact(maxWidth.value, LocalDensity.current.fontScale)
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BodyFigure(
                    tiles = tiles,
                    leaders = emptyMap(),
                    onSelect = onSelect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(BodyMapGeometry.ViewportWidth / BodyMapGeometry.ViewportHeight),
                )
                val bySite = tiles.associateBy { it.site }
                BodyMapGeometry.gridRows().map { row -> row.mapNotNull(bySite::get) }.filter { it.isNotEmpty() }.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        row.forEach { tile -> BodyMapTileCard(tile, onSelect, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            BesideLayout(tiles = tiles, onSelect = onSelect)
        }
    }
}

/**
 * The figure with the tiles beside it. The tiles are measured first and the figure composed after,
 * with their places, so a leader line is drawn in the same frame as the tile it points at.
 */
@Composable
private fun BesideLayout(tiles: List<BodyMapTile>, onSelect: (CircumferenceSite) -> Unit) {
    val density = LocalDensity.current
    val gapPx = with(density) { TileGap.toPx() }
    val clearancePx = with(density) { LeaderClearance.toPx() }
    val placements = remember { PlacementCache() }
    SubcomposeLayout { constraints ->
        val width = constraints.maxWidth
        val figureHeight = (BodyMapGeometry.ViewportHeight * width / BodyMapGeometry.ViewportWidth).roundToInt()
        val tileWidth = (BodyMapGeometry.TileWidth * width / BodyMapGeometry.ViewportWidth).roundToInt()
        val cards = subcompose(BodyMapSlot.TILES) { tiles.forEach { tile -> BodyMapTileCard(tile, onSelect) } }
            .map { it.measure(Constraints(minWidth = tileWidth, maxWidth = tileWidth)) }
        val heights = tiles.indices.associate { tiles[it].site to cards[it].height.toFloat() }
        val placed = placements.get(heights, width) { BodyMapGeometry.placeTiles(heights, width.toFloat(), gapPx, clearancePx) }
        val leaders = placed.mapValues { (site, box) -> BodyMapGeometry.leader(site, box, width.toFloat(), clearancePx) }
        val figure = subcompose(BodyMapSlot.FIGURE) { BodyFigure(tiles = tiles, leaders = leaders, onSelect = onSelect) }
            .single()
            .measure(Constraints.fixed(width, figureHeight))
        val height = max(figureHeight, placed.values.maxOfOrNull { (it.top + it.height).roundToInt() } ?: 0)
        layout(width, height) {
            figure.place(0, 0)
            cards.forEachIndexed { index, card ->
                placed[tiles[index].site]?.let { card.place(it.left.roundToInt(), it.top.roundToInt()) }
            }
        }
    }
}

private enum class BodyMapSlot { TILES, FIGURE }

/**
 * The last placement, kept while the width and the tile heights stay the same: choosing an order
 * tries up to 5040 per column, and typing a value remeasures the tiles without changing either.
 */
private class PlacementCache {
    private var key: Pair<Map<CircumferenceSite, Float>, Int>? = null
    private var value: Map<CircumferenceSite, BodyMapGeometry.TileBox> = emptyMap()

    fun get(
        heights: Map<CircumferenceSite, Float>,
        width: Int,
        compute: () -> Map<CircumferenceSite, BodyMapGeometry.TileBox>,
    ): Map<CircumferenceSite, BodyMapGeometry.TileBox> {
        val wanted = heights to width
        if (wanted != key) {
            value = compute()
            key = wanted
        }
        return value
    }
}

@Composable
private fun BodyFigure(
    tiles: List<BodyMapTile>,
    leaders: Map<CircumferenceSite, BodyMapGeometry.Leader>,
    onSelect: (CircumferenceSite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BodyMapColors(
        outline = MaterialTheme.colorScheme.surfaceVariant,
        idle = MaterialTheme.colorScheme.onSurfaceVariant,
        filled = MaterialTheme.colorScheme.secondary,
        selected = MaterialTheme.colorScheme.primary,
        replaces = MaterialTheme.colorScheme.error,
    )
    val density = LocalDensity.current
    val strokes = with(density) {
        BodyMapStrokes(BandStroke.toPx(), SelectedBandStroke.toPx(), LeaderStroke.toPx(), AnchorDot.toPx())
    }
    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.body_map_front),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(colors.outline),
            modifier = Modifier.matchParentSize(),
        )
        // Tapping a part on the figure selects it, as its tile does; the tiles stay the accessible way in.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(onSelect) {
                    detectTapGestures { tap ->
                        val scale = size.width / BodyMapGeometry.ViewportWidth
                        BodyMapGeometry.siteAt(tap.x / scale, tap.y / scale, TapTolerance)?.let(onSelect)
                    }
                },
        ) {
            val scale = size.width / BodyMapGeometry.ViewportWidth
            tiles.forEach { tile ->
                val geometry = BodyMapGeometry.site(tile.site)
                val color = colors.forTile(tile)
                drawBand(geometry, tile, color, scale, strokes)
                leaders[tile.site]?.let { drawLeader(it, color, strokes) }
            }
        }
    }
}

@Immutable
private data class BodyMapColors(val outline: Color, val idle: Color, val filled: Color, val selected: Color, val replaces: Color) {
    /** A replace warning shows over the selection, which keeps the thicker stroke. */
    fun forTile(tile: BodyMapTile): Color = when {
        tile.state == BodyMapTileState.REPLACES -> replaces
        tile.selected -> selected
        tile.state == BodyMapTileState.FILLED -> filled
        else -> idle
    }
}

private data class BodyMapStrokes(val band: Float, val selectedBand: Float, val leader: Float, val dot: Float)

private fun DrawScope.drawBand(site: BodyMapSite, tile: BodyMapTile, color: Color, scale: Float, strokes: BodyMapStrokes) {
    val halfWidth = site.bandHalfWidth * scale
    val halfHeight = site.bandHalfHeight * scale
    drawOval(
        color = color,
        topLeft = Offset(site.anchorX * scale - halfWidth, site.anchorY * scale - halfHeight),
        size = Size(halfWidth * 2, halfHeight * 2),
        style = Stroke(
            width = if (tile.selected) strokes.selectedBand else strokes.band,
            pathEffect = if (tile.state == BodyMapTileState.EMPTY && !tile.selected) PathEffect.dashPathEffect(DashIntervals) else null,
        ),
    )
}

/** A straight line from the tile to the near end of its part's band, with a dot there. */
private fun DrawScope.drawLeader(leader: BodyMapGeometry.Leader, color: Color, strokes: BodyMapStrokes) {
    val end = Offset(leader.endX, leader.endY)
    drawLine(color, Offset(leader.startX, leader.startY), end, strokes.leader)
    drawCircle(color, strokes.dot, end)
}

@Composable
private fun BodyMapTileCard(tile: BodyMapTile, onSelect: (CircumferenceSite) -> Unit, modifier: Modifier = Modifier) {
    val selected = tile.selected
    val borderColor = when {
        tile.state == BodyMapTileState.REPLACES -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        tile.state == BodyMapTileState.FILLED -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    OpenVitalsCard(
        onClick = { onSelect(tile.site) },
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = tile.description
            role = Role.Button
            this.selected = selected
        },
    ) {
        // On the content, not the card: a clickable card is laid out at least as tall as a touch
        // target, and a border on its modifier outlined that larger box instead of the card.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(if (selected) SelectedTileBorder else TileBorder, borderColor, MaterialTheme.shapes.medium)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        ) {
            // Two lines: with the side in it, a name ("Right upper arm") is wider than a tile.
            Text(
                text = stringResource(tile.site.titleRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            tile.value?.let {
                Text(text = it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            tile.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tile.state == BodyMapTileState.REPLACES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
