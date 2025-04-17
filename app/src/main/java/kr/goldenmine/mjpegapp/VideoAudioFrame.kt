package kr.goldenmine.mjpegapp

import android.graphics.Bitmap
import kotlin.math.abs

class VideoAudioFrame(
    val videoArray: Bitmap,
    val audioArray: ByteArray,
    val videoTimestamp: Long,
    val audioTimestamp: Long,
){
    override fun toString(): String {
        val videoTimeStampInMS = videoTimestamp / 1_000_000
        val audioTimeStampInMS = audioTimestamp / 1_000_000
        val diffInMS = abs(videoTimeStampInMS - audioTimeStampInMS)
        return "VideoAudioFrame(video=${videoArray.width}x${videoArray.height}, audio=${audioArray.size}, time=$videoTimeStampInMS $audioTimeStampInMS $diffInMS)"
    }
}