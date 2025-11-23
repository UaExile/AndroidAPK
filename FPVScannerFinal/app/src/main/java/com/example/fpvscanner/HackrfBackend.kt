package com.example.fpvscanner

import android.util.Log

object HackrfBackend {

    private const val TAG = "HackrfBackend"

    init {
        System.loadLibrary("hackrfbackend")
    }

    private external fun nativeInit(): Boolean
    private external fun nativeOpen(): Boolean
    private external fun nativeClose()
    private external fun nativeMeasurePower(freqHz: Long, sampleRate: Int, sampleCount: Int): Double

    private var initialized = false
    private var opened = false

    fun init(): Boolean {
        if (!initialized) {
            initialized = nativeInit()
            Log.i(TAG, "nativeInit = $initialized")
        }
        return initialized
    }

    fun open(): Boolean {
        if (!initialized) init()
        if (!opened) {
            opened = nativeOpen()
            Log.i(TAG, "nativeOpen = $opened")
        }
        return opened
    }

    fun close() {
        if (opened) {
            nativeClose()
            opened = false
        }
    }

    fun measurePower(
        freqHz: Long,
        sampleRate: Int = 2_000_000,
        sampleCount: Int = 65_536
    ): Double {
        if (!open()) return Double.NEGATIVE_INFINITY
        return nativeMeasurePower(freqHz, sampleRate, sampleCount)
    }
}
