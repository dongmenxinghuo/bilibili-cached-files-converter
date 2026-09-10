package com.example.bilicache

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Transcoder {

    /**
     * 用硬件编码器把视频转成 H.264。
     * @param onProgress 回调 0~100
     */
    suspend fun transcode(
        context: Context,
        input: File,
        output: File,
        onProgress: (Float) -> Unit = {}
    ) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        suspendCancellableCoroutine<Unit> { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })

            val item = MediaItem.fromUri(input.toURI().toString())
            transformer.start(item, output.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }
}
