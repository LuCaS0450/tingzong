package cn.musicpilgrimage.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.musicpilgrimage.app.data.EmailOtpController
import cn.musicpilgrimage.app.data.EmailOtpGateway
import cn.musicpilgrimage.app.performance.LibraryDatabase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicAccountViewModel(app:Application):AndroidViewModel(app) {
    private val services=(app as PilgrimageApplication).services
    internal val otp=EmailOtpController(object:EmailOtpGateway {
        override suspend fun send(email:String,register:Boolean)=services.sendEmailCode(email,register)
        override suspend fun verify(email:String,code:String)=services.verifyEmailCode(email,code)
    },viewModelScope)
    init {viewModelScope.launch {var previous=services.userId.value;services.userId.collect{if(it!=previous){otp.reset();previous=it}}}}
    internal val performances=services.userId.flatMapLatest { owner->
        LibraryDatabase.open(app).performance().results(owner?.let{"user:$it"}?:LibraryDatabase.guestOwner(app))
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
}
