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
            val dir = pendingDir(context)
            val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
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
                setAudioEncodingBitRate(24000) // ~3 KB/sec -> 20s note fits inside fast MessageClient!
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            Log.i(TAG, "Started persistent voice recording -> ${file.name}")
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
            outputFile = null
            if (file != null && file.exists() && file.length() > 500) {
                Log.i(TAG, "Saved persistent voice recording: ${file.name} (${file.length()} bytes)")
                file
            } else {
                runCatching { file?.delete() }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
            runCatching { mr.release() }
            runCatching { outputFile?.delete() }
            outputFile = null
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

        fun pendingDir(context: Context): File {
            val dir = File(context.filesDir, "pending_voice")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun listPendingFiles(context: Context): List<File> {
            return pendingDir(context)
                .listFiles { f -> f.isFile && f.name.endsWith(".m4a") && f.length() > 500 }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        fun deletePendingFile(file: File) {
            runCatching { if (file.exists()) file.delete() }
        }
    }
}
