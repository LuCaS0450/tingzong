package cn.musicpilgrimage.app.design

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

/** Neutral light surfaces shared by the music travel interface. */
internal object AppArt {
    val Red=Color(0xFFBD382B)
    val RedInk=Color(0xFF77352F)
    val Blue=Color(0xFF242B27)
    val Yellow=Color(0xFFDEE5ED)
    val Ink=Color(0xFF242B27)
    val Muted=Color(0xFF686E67)
    val Base=Color(0xFFF7F6F2)
    val Paper=Color(0xFFF7F6F2)
    val Edge=Color(0xFF9DABAB)
    val Green=Color(0xFF286257)
    val Sand=Color(0xFFE9E9DF)
    val PaleBlue=Color(0xFFE4E6DB)
}

/** Legacy callers retain semantic surface names; no photographic texture is loaded. */
@Composable internal fun materialBrush(name:String):Brush=SolidColor(when(name) {
    "walnut"->AppArt.Sand
    "aluminum"->AppArt.PaleBlue
    "rubber"->AppArt.Ink
    else->AppArt.Paper
})

@Composable internal fun MaterialPlate(name:String,modifier:Modifier=Modifier,radius:Int=4,
    content:@Composable BoxScope.()->Unit={}) {
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(materialBrush(name)),content=content)
}

internal val ArchivalPaperShape:Shape=RoundedCornerShape(5.dp)

@Composable internal fun PaperStack(modifier:Modifier=Modifier,shape:Shape=ArchivalPaperShape,
    layered:Boolean=true,selected:Boolean=false,clipped:Boolean=false,content:@Composable BoxScope.()->Unit) {
    Box(modifier.fillMaxWidth().padding(end=3.dp,bottom=4.dp)) {
        Surface(Modifier.fillMaxWidth(),color=if(selected)AppArt.PaleBlue else AppArt.Paper,
            shape=shape,border=BorderStroke(.5.dp,if(selected)AppArt.Blue else AppArt.Ink.copy(alpha=.12f)),shadowElevation=2.dp) {
            Box(Modifier.fillMaxWidth(),content=content)
        }
    }
}

@Composable internal fun ArtPaper(modifier:Modifier=Modifier,shape:Shape=ArchivalPaperShape,layered:Boolean=true,
    content:@Composable ColumnScope.()->Unit) {
    PaperStack(modifier,shape,layered) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
