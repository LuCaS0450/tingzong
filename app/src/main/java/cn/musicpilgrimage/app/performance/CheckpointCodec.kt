package cn.musicpilgrimage.app.performance

import org.json.JSONArray
import org.json.JSONObject

internal object CheckpointCodec {
    fun encode(judge:RhythmJudge,musicUs:Long)=JSONObject().put("combo",judge.combo).put("bestCombo",judge.bestCombo).put("wrongInputs",judge.wrongInputs)
        .put("progress",JSONArray(judge.progress.mapIndexed{i,r->JSONObject().put("state",r.judgment.name)
            .put("startedUs",r.startedUs).put("heldUs",r.heldUs+(r.holdingFromUs?.let{(minOf(musicUs,judge.targets[i].endUs)-it).coerceAtLeast(0)} ?: 0))
            .put("attack",r.attack.name)})).toString()
    fun decode(targets:List<Target>,value:String,speed:Double=1.0):RhythmJudge {
        val json=JSONObject(value);val array=json.getJSONArray("progress");require(array.length()==targets.size)
        val rows=List(array.length()){i->array.getJSONObject(i).let{r->
            TargetProgress(Judgment.valueOf(r.getString("state")),r.getLong("startedUs"),r.getLong("heldUs"),null,Judgment.valueOf(r.getString("attack")))
        }}
        require(rows.all{it.startedUs>=0 && it.heldUs>=0})
        return RhythmJudge(targets,rows,speed).apply {
            combo=json.getInt("combo");bestCombo=json.getInt("bestCombo");wrongInputs=json.getInt("wrongInputs")
            require(combo>=0 && bestCombo>=combo && wrongInputs>=0)
        }
    }
}
