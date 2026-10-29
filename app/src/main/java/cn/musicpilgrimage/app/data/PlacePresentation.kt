package cn.musicpilgrimage.app.data

data class PlaceHeading(val title:String,val qualifier:String="")

/** Display only: retain the full official/catalogue name for search, navigation and accessibility. */
fun Place.heading():PlaceHeading = placeHeading(name,id)

fun placeHeading(name:String,id:String=""):PlaceHeading = when {
 id=="beverly-hills-hotel-reference" || name=="Beverly Hills Hotel（外部参考点）" -> PlaceHeading("Beverly Hills Hotel","外部参考点 · 封面取景")
 id=="lido-yucca-exterior-reference" || name=="Lido Apartments（Yucca Street外部参考）" -> PlaceHeading("Lido Apartments","Yucca Street · 建筑外部参考")
 else -> {
  val match=Regex("^(.+?)[（(]([^（）()]*(?:参考|预约进入)[^（）()]*)[）)]$").matchEntire(name)
  if(match==null)PlaceHeading(name) else PlaceHeading(match.groupValues[1].trim(),match.groupValues[2].trim())
 }
}

/** Only remove a whole, identical sentence already present in the visible place information. */
fun Place.sourceNote(scene:Scene):String {
 val sentences=description.split(Regex("(?<=[。！？])")).map(String::trim).filter(String::isNotBlank)
 return sentences.fold(scene.note){note,sentence->if(sentence.length>=12)note.replace(sentence,"")else note}.trim()
}
