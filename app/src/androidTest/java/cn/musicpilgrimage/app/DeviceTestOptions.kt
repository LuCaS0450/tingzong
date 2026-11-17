package cn.musicpilgrimage.app

import androidx.test.platform.app.InstrumentationRegistry

/** Select these scenarios explicitly once the device, fixtures, or service are ready. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class ExtendedDeviceTest

internal fun shouldCaptureReviewScreenshot(name: String): Boolean {
    val selection = InstrumentationRegistry.getArguments().getString("captureScreenshots").orEmpty()
    return selection == "true" || name in selection.split(',')
}
