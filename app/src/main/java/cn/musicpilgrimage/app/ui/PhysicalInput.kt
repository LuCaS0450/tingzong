package cn.musicpilgrimage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField as MaterialOutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.design.materialBrush

/** Shared paper field: the real input/IME/selection behavior remains Material's. */
@Composable internal fun OutlinedTextField(
 value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,
 enabled:Boolean=true,readOnly:Boolean=false,
 label:(@Composable ()->Unit)?=null,placeholder:(@Composable ()->Unit)?=null,
 leadingIcon:(@Composable ()->Unit)?=null,trailingIcon:(@Composable ()->Unit)?=null,
 supportingText:(@Composable ()->Unit)?=null,isError:Boolean=false,
 visualTransformation:VisualTransformation=VisualTransformation.None,
 keyboardOptions:KeyboardOptions=KeyboardOptions.Default,keyboardActions:KeyboardActions=KeyboardActions.Default,
 singleLine:Boolean=false,maxLines:Int=if(singleLine)1 else Int.MAX_VALUE,minLines:Int=1,
 shape:Shape=RoundedCornerShape(5.dp)
) {
 MaterialOutlinedTextField(value,onValueChange,
  modifier.background(materialBrush("paper"),shape),
  enabled=enabled,readOnly=readOnly,label=label,placeholder=placeholder,leadingIcon=leadingIcon,trailingIcon=trailingIcon,
  supportingText=supportingText,isError=isError,visualTransformation=visualTransformation,
  keyboardOptions=keyboardOptions,keyboardActions=keyboardActions,singleLine=singleLine,maxLines=maxLines,minLines=minLines,shape=shape,
  colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Ink,unfocusedBorderColor=Line,focusedLabelColor=Ink))
}
