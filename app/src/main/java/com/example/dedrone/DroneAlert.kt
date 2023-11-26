package com.example.dedrone

import android.content.Context
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.LinkedList


private const val MAX_SIZE = 10

class DroneAlert(val context: Context) {

    private var currentMode = MODE.SOUND
    private var mediaPlayer: MediaPlayer? = null
    private val queue = LinkedList<BoundingBox?>()
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?

    fun onDrone(boundingBox: List<BoundingBox>?) {
        boundingBox?.forEach {
            add(it)
        } ?: add(null)
    }

    fun add(boundingBox: BoundingBox?) {
        if (queue.size == MAX_SIZE) {
            queue.removeFirst()
        }
        queue.add(boundingBox)
        checkSum()
    }

    private fun checkSum() {
        var sum = 0
        for (boundingBox in queue) {
            sum += if (boundingBox == null) 0 else 1
        }
        if (sum > 5) {
            alert()
        }
    }

    private fun alert() {
        if (currentMode == MODE.VIBRATE) {
            vibrate()
        } else {
            if (mediaPlayer != null) {
                return
            }
            mediaPlayer = MediaPlayer.create(context, R.raw.alert)
            mediaPlayer?.setOnCompletionListener { mp ->
                mp.release()
                mediaPlayer = null
            }

            mediaPlayer?.start()
        }
    }

    private fun vibrate() {
        vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun stopAlarm() {
        if(currentMode == MODE.VIBRATE){
            vibrator?.cancel()
        } else {
            mediaPlayer?.let {
                it.stop()
                it.release()
                mediaPlayer = null
            }
        }
    }

    fun changeMode(): MODE {
        currentMode = if (currentMode == MODE.SOUND) {
            MODE.VIBRATE
        } else {
            MODE.SOUND
        }
        return currentMode
    }

    enum class MODE {
        SOUND,
        VIBRATE
    }

}


