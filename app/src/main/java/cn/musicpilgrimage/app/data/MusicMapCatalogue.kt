package cn.musicpilgrimage.app.data

import java.text.Collator
import java.util.Locale

internal data class MusicMapCity(val key:String,val name:String,val country:String,val places:List<Place>)

/** City boundaries come from the reviewed catalogue, never from artwork pixels. */
internal fun musicMapCities(catalog:Catalog,work:MusicWork?):List<MusicMapCity> {
    val links=work?.places?.associateBy{it.place.id}
    val places=catalog.publicOnly().places.filter {p->
        p.latitude.isFinite()&&p.longitude.isFinite()&&p.latitude in -90.0..90.0&&p.longitude in -180.0..180.0&&
            (links==null||p.id in links)
    }.map {p->if(links==null)p else p.copy(scenes=p.scenes.filter{s->links[p.id]!!.scenes.any{it.id==s.id}})}.filter{it.scenes.isNotEmpty()}
    val collator=Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    return places.groupBy{it.country+"::"+it.city}.map{(key,rows)->MusicMapCity(key,rows.first().city,rows.first().country,rows)}
        .sortedWith{a,b->collator.compare(a.name,b.name).takeIf{it!=0} ?: collator.compare(a.country,b.country)}
}

internal fun Place.mapScene(preferred:MusicWork?=null):Scene? {
    val ids=preferred?.places?.find{it.place.id==id}?.scenes?.map{it.id}.orEmpty()
    val visible=scenes.filter{it.publiclyVisible&&it.imageApproved}
    return visible.firstOrNull{it.id in ids} ?: visible.firstOrNull{it.referenceKind!="release_cover"} ?: visible.firstOrNull()
}
