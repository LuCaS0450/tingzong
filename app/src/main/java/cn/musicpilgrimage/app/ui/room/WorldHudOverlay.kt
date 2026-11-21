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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
    cityCount: Int,
    placeCount: Int,
    summaryOpen: Boolean,
    interactive: Boolean,
    unit: Dp,
    onGoGlobe: () -> Unit,
    onZoomRegion: () -> Unit,
    onZoomCity: () -> Unit,
    onZoomBy: (Double) -> Unit,
    onZoomToStop: (Double) -> Unit,
    onTrips: () -> Unit,
    onFootprints: () -> Unit,
    onRetryTiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalPhysicalMotion.current
    val context = LocalContext.current
    val miniGlobeBitmap = remember(context) { loadMiniGlobeBitmap(context) }
    val hud = readHud()

    Box(modifier.fillMaxSize()) {
        // Stage overlays start at the compact header's bottom edge.
        Box(
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
            if (hud.compactHeader && !summaryOpen && hud.scaleLabel.isNotEmpty() && hud.scaleWidthPx > 0f) {
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
                        .padding(start = unit * 18f, bottom = unit * 10f),
                )
            }

            // Right-edge zoom rail (hidden when summary card is open).
            if (!summaryOpen) {
                WorldZoomRail(
                    readHud = readHud,
                    levelLabel = hud.levelLabel,
                    interactive = interactive,
                    unit = unit,
                    onZoomIn = { onZoomBy(1.0) },
                    onZoomOut = { onZoomBy(-1.0) },
                    onZoomStop = onZoomToStop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = unit * 12f, bottom = unit * 16f),
                )
            }
        }

        // The expanded header covers stage controls that are still fading out.
        WorldHeaderBar(
            compact = hud.compactHeader,
            regionName = hud.regionName,
            cityName = hud.cityName,
            cityCount = cityCount,
            placeCount = placeCount,
            interactive = interactive,
            unit = unit,
            onGoGlobe = onGoGlobe,
            onZoomRegion = onZoomRegion,
            onZoomCity = onZoomCity,
        )
    }
}

@Composable
private fun WorldHeaderBar(
    compact: Boolean,
    regionName: String,
    cityName: String,
    cityCount: Int,
    placeCount: Int,
    interactive: Boolean,
    unit: Dp,
    onGoGlobe: () -> Unit,
    onZoomRegion: () -> Unit,
    onZoomCity: () -> Unit,
) {
    if (!compact) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(unit * 128f)
        ) {
            RoomShelfWall(Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = unit * 22f, top = unit * 22f, end = unit * 22f),
            ) {
                Text(
                    text = "02 / WORLD",
                    color = RoomColors.Red,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(unit * 10f))
                Text(
                    text = "地图与旅行",
                    color = RoomColors.Ink,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    letterSpacing = (-0.45).sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(unit * 16f))
                Text(
                    text = "$cityCount 座城市 / $placeCount 个地点",
                    color = RoomColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .height(unit * 64f)
        ) {
            RoomShelfWall(Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = unit * 22f, top = unit * 9f, end = unit * 110f),
            ) {
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
                            onClick = onZoomRegion,
                        )
                    }
                    if (cityName.isNotEmpty()) {
                        Text(" / ", color = WorldFaint, fontSize = 12.sp)
                        BreadcrumbSegment(
                            label = cityName,
                            highlighted = true,
                            interactive = interactive,
                            onClick = onZoomCity,
                        )
                    }
                }
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
                Text("我的行程", color = RoomColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                Text("我的足迹", color = RoomColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun WorldZoomRail(
    readHud: () -> WorldHudState,
    levelLabel: String,
    interactive: Boolean,
    unit: Dp,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomStop: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stops = remember {
        listOf(
            15.0 to ("缩放到街道" to "world-zoom-street"),
            11.0 to ("缩放到城市" to "world-zoom-city"),
            5.0 to ("缩放到国家或地区" to "world-zoom-region"),
            0.5 to ("缩放到地球" to "world-zoom-globe"),
        )
    }
    val trackHeight = unit * 96f

    Box(modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(unit * 40f)
                .shadow(3.dp)
                .background(RoomColors.Paper.copy(alpha = 0.94f))
                .border(1.dp, RoomColors.Ink.copy(alpha = 0.08f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("world-zoom-in")
                    .clickable(
                        enabled = interactive,
                        role = Role.Button,
                        onClickLabel = "放大一级",
                        onClick = onZoomIn,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "放大一级", modifier = Modifier.size(19.dp), tint = RoomColors.Ink)
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = 14.dp)
                        .background(WorldFaint.copy(alpha = 0.48f))
                )
                Column(Modifier.fillMaxSize()) {
                    for ((stopZoom, meta) in stops) {
                        val (label, tag) = meta
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag(tag)
                                .semantics { contentDescription = label }
                                .clickable(
                                    enabled = interactive,
                                    role = Role.Button,
                                    onClickLabel = label,
                                    onClick = { onZoomStop(stopZoom) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .offset(x = 4.dp)
                                    .width(7.dp)
                                    .height(1.dp)
                                    .background(WorldFaint)
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("world-zoom-out")
                    .clickable(
                        enabled = interactive,
                        role = Role.Button,
                        onClickLabel = "缩小一级",
                        onClick = onZoomOut,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = "缩小一级", modifier = Modifier.size(19.dp), tint = RoomColors.Ink)
            }
        }

        // Dynamic red level badge + horizontal tick line aligned with current stop center on the rail track.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .layout { measurable, constraints ->
                    val badge = measurable.measure(constraints)
                    layout(badge.width, badge.height) {
                        val hud = readHud()
                        val fraction = WorldMapMath.zoomRailFraction(hud.zoom, hud.globeZoom)
                        val centerY = 44.dp + trackHeight * (0.125f + 0.75f * fraction)
                        badge.placeRelative(
                            x = (-unit * 20f + 13.dp).roundToPx(),
                            y = centerY.roundToPx() - badge.height / 2,
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .background(RoomColors.Paper.copy(alpha = 0.90f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = levelLabel,
                    color = RoomColors.Red,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .width(14.dp)
                    .height(1.5.dp)
                    .background(RoomColors.Red)
            )
        }
    }
}

private fun loadMiniGlobeBitmap(context: android.content.Context): ImageBitmap? = runCatching {
    context.assets.open("design/swiss-world/globe-mini.png").use {
        BitmapFactory.decodeStream(it)?.asImageBitmap()
    }
}.getOrNull()
