package cn.musicpilgrimage.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.SwissWebDetail
import org.json.JSONObject

/** Read-only visual fixture for the approved itinerary; never opens an account or writes a plan. */
class SwissDetailReviewActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.light(android.graphics.Color.rgb(247,246,242),android.graphics.Color.rgb(247,246,242)))
        val data=JSONObject(assets.open("swiss-detail-review.json").bufferedReader().use{it.readText()})
        setContent{PilgrimageTheme{SwissWebDetail(data){action,_->
            if(action=="back")finish()
            else if(action!="hero-ready")Toast.makeText(this,"只读界面样本，不保存修改。",Toast.LENGTH_SHORT).show()
        }}}
    }
}
