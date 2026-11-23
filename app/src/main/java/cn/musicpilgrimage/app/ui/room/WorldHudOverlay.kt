package cn.musicpilgrimage.app.ui.room

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion

private val WorldFaint = Color(0xFF9A9E95)

/**
 * Pure Compose HUD for the World wall (`header`, `breadcrumb`, `mini globe`, `zoom rail`,
 * `scale bar`, `dock`, `gesture hint`, and non-blocking `loading/retry` pill).
 */
@Composable
internal fun WorldHudOverlay(
    readHud: () -> WorldHudState,
    summaryOpen: Boolean,
    interactive: Boolean,
    unit: Dp,
    onGoGlobe: () -> Unit,
    onZoomBy: (Double) -> Unit,
    onZoomToStop: (WorldZoomStop) -> Unit,
    onTrips: () -> Unit,
    onFootprints: () -> Unit,
    onRetryTiles: () -> Unit,
    onPlaces: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalPhysicalMotion.current
    val context = LocalContext.current
    val miniGlobeBitmap = remember(context) { loadMiniGlobeBitmap(context) }
    val hud = readHud()

    Box(modifier.fillMaxSize()) {
        // Stage overlays start at the compact header's bottom edge.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(top = unit * 64f)
        ) {
            // Top thin progress strip & non-blocking tile status pill.
            if (hud.progressVisible) {
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(2.dp)
                        .background(RoomColors.Red)
                        .align(Alignment.TopStart)
                )
            }
            if (hud.loadingVisible) {
                WorldLoadingPill(
                    text = hud.loadingText,
                    error = hud.loadingError,
                    interactive = interactive,
                    onRetry = onRetryTiles,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = unit * 14f),
                )
            }

            // Mini globe quick-return button (z >= 4).
            AnimatedVisibility(
                visible = hud.beyondGlobe,
                enter = if (motion) fadeIn() else EnterTransition.None,
                exit = if (motion) fadeOut() else ExitTransition.None,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = unit * 12f, top = unit * 12f),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(RoomColors.Paper)
                        .testTag("world-mini-globe")
                        .clickable(
                            enabled = interactive,
                            role = Role.Button,
                            onClickLabel = "以当前位置回到完整地球",
                            onClick = onGoGlobe,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    miniGlobeBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = "以当前位置回到完整地球",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(RoomColors.Red)
                            .border(1.dp, RoomColors.Paper, CircleShape)
                    )
                }
            }

            // Bottom gesture hint at rest globe overview.
            if (!hud.enlarged && !summaryOpen) {
                Text(
                    text = "拖动旋转 · 双指或双击放大",
                    color = RoomColors.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = unit * 96f)
                        .testTag("world-gesture-hint"),
                )
            }

            // Scale bar (z >= 3 when summary closed).
            if (hud.compactHeader && !summaryOpen && !hud.streetOffline &&
                hud.scaleLabel.isNotEmpty() && hud.scaleWidthPx > 0f
            ) {
                WorldScaleBar(
                    label = hud.scaleLabel,
                    widthPx = hud.scaleWidthPx,
                    satelliteBackdrop = hud.satelliteScale,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = unit * 14f, bottom = unit * 70f),
                )
            }

            // Bottom-left Trips & Footprints dock (hidden when summary card is open).
            if (!summaryOpen) {
                WorldBottomDock(
                    enlarged = hud.enlarged,
                    interactive = interactive,
                    onTrips = onTrips,
                    onFootprints = onFootprints,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = SwissWallStartInset - 10.dp, bottom = 10.dp),
                )
            }

            // Right-edge zoom rail (hidden when summary card is open).
            if (!summaryOpen) {
                WorldZoomRail(
                    readHud = readHud,
                    hud = hud,
                    interactive = interactive,
                    stopHeight = ((maxHeight - 116.dp) / 4).coerceIn(22.dp, 48.dp),
                    onZoomIn = { onZoomBy(1.0) },
                    onZoomOut = { onZoomBy(-1.0) },
                    onZoomStop = onZoomToStop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = SwissWallEndInset, bottom = 16.dp),
                )
            }
        }

        // The expanded header covers stage controls that are still fading out.
        WorldHeaderBar(
            compact = hud.compactHeader,
            regionName = hud.regionName,
            cityName = hud.cityName,
            interactive = interactive,
            unit = unit,
            onGoGlobe = onGoGlobe,
            onZoomToStop = onZoomToStop,
            onPlaces = onPlaces,
            onSearch = onSearch,
        )
    }
}

@Composable
private fun WorldHeaderBar(
    compact: Boolean,
    regionName: String,
    cityName: String,
    interactive: Boolean,
    unit: Dp,
    onGoGlobe: () -> Unit,
    onZoomToStop: (WorldZoomStop) -> Unit,
    onPlaces: () -> Unit,
    onSearch: () -> Unit,
) {
    if (!compact) {
        Box(Modifier.fillMaxWidth()) {
            RoomShelfWall(Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                SwissWallHeader(1, onSearch, searchEnabled = interactive, searchTag = "world-search")
                WorldPlacesButton(interactive, onPlaces, Modifier.padding(end = SwissWallEndInset))
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .height(unit * 64f)
        ) {
            RoomShelfWall(Modifier.matchParentSize())
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = SwissWallStartInset, end = SwissWallEndInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "02",
                            color = RoomColors.Red,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "地图与旅行",
                            color = RoomColors.Ink,
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .height(unit * 24f)
                            .testTag("world-breadcrumb"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BreadcrumbSegment(
                            label = "世界",
                            highlighted = regionName.isEmpty() && cityName.isEmpty(),
                            interactive = interactive,
                            onClick = onGoGlobe,
                        )
                        if (regionName.isNotEmpty()) {
                            Text(" / ", color = WorldFaint, fontSize = 12.sp)
                            BreadcrumbSegment(
                                label = regionName,
                                highlighted = cityName.isEmpty(),
                                interactive = interactive,
                                onClick = { onZoomToStop(WorldZoomStop.Region) },
                            )
                        }
                        if (cityName.isNotEmpty()) {
                            Text(" / ", color = WorldFaint, fontSize = 12.sp)
                            BreadcrumbSegment(
                                label = cityName,
                                highlighted = true,
                                interactive = interactive,
                                onClick = { onZoomToStop(WorldZoomStop.City) },
                            )
                        }
                    }
                }
                WorldPlacesButton(interactive, onPlaces)
                SwissWallSearchButton(onSearch, interactive, "world-search")
            }
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                thickness = 1.dp,
                color = RoomColors.Line,
            )
        }
    }
}

@Composable
private fun WorldPlacesButton(interactive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SwissTextButton(
        onClick,
        modifier.heightIn(min = 48.dp).testTag("world-place-list"),
        enabled = interactive,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, null, Modifier.size(18.dp), tint = RoomColors.Ink)
        Spacer(Modifier.width(5.dp))
        Text("地点", color = RoomColors.Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BreadcrumbSegment(
    label: String,
    highlighted: Boolean,
    interactive: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = 24.dp)
            .clickable(enabled = interactive, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (highlighted) RoomColors.Ink else RoomColors.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorldLoadingPill(
    text: String,
    error: Boolean,
    interactive: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .shadow(3.dp)
            .background(RoomColors.Paper.copy(alpha = 0.94f))
            .then(
                if (error) {
                    Modifier.clickable(enabled = interactive, role = Role.Button, onClick = onRetry)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("world-loading-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (error) RoomColors.Muted else RoomColors.Red)
        )
        Text(
            text = text,
            color = RoomColors.Ink,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun WorldScaleBar(
    label: String,
    widthPx: Float,
    satelliteBackdrop: Boolean,
    modifier: Modifier = Modifier,
) {
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    Column(
        modifier
            .then(
                if (satelliteBackdrop) {
                    Modifier
                        .background(RoomColors.Paper.copy(alpha = 0.90f))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
            )
            .testTag("world-scale-bar"),
    ) {
        Text(
            text = label,
            color = RoomColors.Ink,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(3.dp))
        Canvas(Modifier.width(widthDp).height(5.dp)) {
            val stroke = 1.dp.toPx()
            drawLine(RoomColors.Ink, Offset(0f, 0f), Offset(0f, size.height), stroke)
            drawLine(RoomColors.Ink, Offset(0f, size.height), Offset(size.width, size.height), stroke)
            drawLine(RoomColors.Ink, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
        }
    }
}

@Composable
private fun WorldBottomDock(
    enlarged: Boolean,
    interactive: Boolean,
    onTrips: () -> Unit,
    onFootprints: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (enlarged) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(vertical = 10.dp)
                    .shadow(2.dp)
                    .background(RoomColors.Paper.copy(alpha = 0.94f))
                    .border(1.dp, RoomColors.Ink.copy(alpha = 0.08f))
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .testTag("world-dock-trips")
                    .clickable(enabled = interactive, role = Role.Button, onClick = onTrips)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("行程", color = RoomColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (enlarged) {
                VerticalDivider(
                    modifier = Modifier.height(20.dp),
                    thickness = 1.dp,
                    color = RoomColors.Line,
                )
            }
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .testTag("world-dock-footprints")
                    .clickable(enabled = interactive, role = Role.Button, onClick = onFootprints)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("足迹", color = RoomColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun WorldZoomRail(
    readHud: () -> WorldHudState,
    hud: WorldHudState,
    interactive: Boolean,
    stopHeight: Dp,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomStop: (WorldZoomStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(60.dp)
            .shadow(2.dp)
            .background(RoomColors.Paper.copy(alpha = 0.98f))
            .border(1.dp, RoomColors.Line),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WorldZoomButton(enlarge = true, enabled = interactive && hud.zoom < WorldMaxZoom - 0.01, onClick = onZoomIn)
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.matchParentSize()) {
                val x = 11.dp.toPx()
                val yEnd = size.height * WorldZoomStop.Globe.trackFraction
                drawLine(WorldFaint, Offset(x, 0f), Offset(x, yEnd), 1.dp.toPx())
                for (stop in WorldZoomStop.entries) {
                    val y = stop.trackFraction * size.height
                    drawLine(WorldFaint, Offset(x, y), Offset(x + 4.dp.toPx(), y), 1.dp.toPx())
                }
                val camera = readHud()
                val y = (size.height * WorldMapMath.zoomRailFraction(camera.zoom, camera.globeZoom))
                    .coerceAtLeast(1.dp.toPx())
                drawLine(RoomColors.Red, Offset(x - 3.dp.toPx(), y), Offset(x + 6.dp.toPx(), y), 2.dp.toPx())
            }
            Column {
                for (stop in WorldZoomStop.entries) {
                    val active = stop.label == hud.levelLabel
                    Box(
                        Modifier.fillMaxWidth().height(stopHeight).testTag(stop.tag)
                            .semantics { selected = active }
                            .clickable(
                                enabled = interactive,
                                role = Role.Button,
                                onClickLabel = "缩放到${stop.label}",
                                onClick = { onZoomStop(stop) },
                            )
                            .padding(start = 22.dp, end = 5.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            if (stop == WorldZoomStop.Region) "地区" else stop.label,
                            color = if (active) RoomColors.Red else RoomColors.Muted,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        WorldZoomButton(enlarge = false, enabled = interactive && hud.zoom > hud.globeZoom + 0.01, onClick = onZoomOut)
    }
}

@Composable
private fun WorldZoomButton(enlarge: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = if (enlarge) "放大一级" else "缩小一级"
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .testTag(if (enlarge) "world-zoom-in" else "world-zoom-out")
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (enlarge) Icons.Outlined.Add else Icons.Outlined.Remove,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) RoomColors.Ink else WorldFaint,
        )
    }
}

private fun loadMiniGlobeBitmap(context: android.content.Context): ImageBitmap? = runCatching {
    context.assets.open("design/swiss-world/globe-mini.png").use {
        BitmapFactory.decodeStream(it)?.asImageBitmap()
    }
}.getOrNull()
