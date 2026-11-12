package com.sparsh.dialer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tiny SharedPreferences-backed settings store exposed as hot StateFlows. */
object Prefs {
    const val DEFAULT_ACCENT = 0xFFFD366E.toInt()
    private lateinit var sp: SharedPreferences

    private val _theme = MutableStateFlow(0)
    private val _accent = MutableStateFlow(DEFAULT_ACCENT)
    private val _haptics = MutableStateFlow(true)
    private val _tones = MutableStateFlow(true)
    private val _onboarded = MutableStateFlow(false)

    /** 0 = follow system, 1 = light, 2 = dark */
    val theme: StateFlow<Int> = _theme
    val accent: StateFlow<Int> = _accent
    val haptics: StateFlow<Boolean> = _haptics
    val tones: StateFlow<Boolean> = _tones
    val onboarded: StateFlow<Boolean> = _onboarded

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = sp.getInt("theme", 0)
        _accent.value = sp.getInt("accent", DEFAULT_ACCENT)
        _haptics.value = sp.getBoolean("haptics", true)
        _tones.value = sp.getBoolean("tones", true)
        _onboarded.value = sp.getBoolean("onboarded", false)
    }

    fun setTheme(v: Int) { _theme.value = v; sp.edit().putInt("theme", v).apply() }
    fun setAccent(v: Int) { _accent.value = v; sp.edit().putInt("accent", v).apply() }
    fun setHaptics(v: Boolean) { _haptics.value = v; sp.edit().putBoolean("haptics", v).apply() }
    fun setTones(v: Boolean) { _tones.value = v; sp.edit().putBoolean("tones", v).apply() }
    fun setOnboarded(v: Boolean) { _onboarded.value = v; sp.edit().putBoolean("onboarded", v).apply() }
}
