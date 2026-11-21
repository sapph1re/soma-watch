package com.aisling.somawatch

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class WearVoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): Boolean {
        stopAndDiscard()
        return try {
            val file = File(context.cacheDir, "soma_voice_prompt.m4a")
            if (file.exists()) file.delete()
            outputFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(32000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            Log.i(TAG, "Started voice recording -> ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder", e)
            stopAndDiscard()
            false
        }
    }

    fun stopAndGetFile(): File? {
        val mr = recorder ?: return null
        recorder = null
        return try {
            mr.stop()
            mr.release()
            val file = outputFile
            if (file != null && file.exists() && file.length() > 500) {
                Log.i(TAG, "Stopped recording: ${file.length()} bytes")
                file
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
            runCatching { mr.release() }
            null
        }
    }

    fun stopAndDiscard() {
        val mr = recorder
        recorder = null
        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        outputFile?.let { if (it.exists()) it.delete() }
        outputFile = null
    }

    companion object {
        private const val TAG = "SomaVoiceRecorder"
    }
}
