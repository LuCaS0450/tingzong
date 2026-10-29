package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

// Material 3's full-radius button token does not inherit MaterialTheme.shapes.
// Route every room action through these defaults so forms and details stay consistent.
@Composable internal fun SwissButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RectangleShape,
    colors:ButtonColors=ButtonDefaults.buttonColors(),elevation:ButtonElevation?=ButtonDefaults.buttonElevation(),
    border:BorderStroke?=null,contentPadding:PaddingValues=ButtonDefaults.ContentPadding,
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = Button(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)

@Composable internal fun SwissOutlinedButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RectangleShape,
    colors:ButtonColors=ButtonDefaults.outlinedButtonColors(),elevation:ButtonElevation?=null,
    border:BorderStroke?=ButtonDefaults.outlinedButtonBorder(enabled),contentPadding:PaddingValues=ButtonDefaults.ContentPadding,
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = OutlinedButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)

@Composable internal fun SwissTextButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RectangleShape,
    colors:ButtonColors=ButtonDefaults.textButtonColors(),elevation:ButtonElevation?=null,
    border:BorderStroke?=null,contentPadding:PaddingValues=ButtonDefaults.TextButtonContentPadding,
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = TextButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)

@Composable internal fun SwissTonalButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RectangleShape,
    colors:ButtonColors=ButtonDefaults.filledTonalButtonColors(),elevation:ButtonElevation?=ButtonDefaults.filledTonalButtonElevation(),
    border:BorderStroke?=null,contentPadding:PaddingValues=ButtonDefaults.ContentPadding,
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = FilledTonalButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)
