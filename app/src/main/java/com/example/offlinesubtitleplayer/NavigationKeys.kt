package com.example.offlinesubtitleplayer

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable data class PlayerKey(val videoUri: String, val videoTitle: String) : NavKey
