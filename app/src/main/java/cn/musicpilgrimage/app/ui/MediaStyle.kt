package cn.musicpilgrimage.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import cn.musicpilgrimage.app.design.*

internal val MapMediaShape=RoundedCornerShape(4.dp)
internal val ArtworkShape=RoundedCornerShape(5.dp)
internal val ThumbnailArtworkShape=RoundedCornerShape(3.dp)

/** Soft depth around media without the previous hard pixel offset. */
internal fun Modifier.mediaShadow(shape:Shape,compact:Boolean=false):Modifier=shadow(if(compact)1.dp else 2.dp,shape,clip=false)
