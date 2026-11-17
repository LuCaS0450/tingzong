package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.ApiException
import cn.musicpilgrimage.app.data.UserActionException
import org.junit.Assert.assertEquals
import org.junit.Test

class UserFeedbackTest {
    @Test
    fun backendAndRuntimeDetailsStayOutOfFeedback() {
        assertEquals("操作未完成，请稍后再试",
            FeatureViewModel.friendlyError(ApiException(503, "V2 服务尚未部署", "service_unavailable")))
        assertEquals("操作未完成，请再试一次",
            FeatureViewModel.friendlyError(IllegalStateException("Library document is malformed")))
    }

    @Test
    fun explicitProductFeedbackKeepsTheNextStep() {
        assertEquals("请先打开手机定位",
            FeatureViewModel.friendlyError(UserActionException("请先打开手机定位")))
        assertEquals("登录已失效，请重新登录",
            FeatureViewModel.friendlyError(ApiException(401, "Auth token expired", "auth_required")))
    }
}
