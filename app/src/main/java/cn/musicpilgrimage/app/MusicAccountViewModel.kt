package cn.musicpilgrimage.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.musicpilgrimage.app.data.EmailOtpController
import cn.musicpilgrimage.app.data.EmailOtpGateway
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MusicAccountViewModel(app:Application):AndroidViewModel(app) {
    private val services=(app as PilgrimageApplication).services
    internal val otp=EmailOtpController(object:EmailOtpGateway {
        override suspend fun send(email:String,register:Boolean)=services.sendEmailCode(email,register)
        override suspend fun verify(email:String,code:String)=services.verifyEmailCode(email,code)
    },viewModelScope)
    init {viewModelScope.launch {var previous=services.userId.value;services.userId.collect{if(it!=previous){otp.reset();previous=it}}}}
}
