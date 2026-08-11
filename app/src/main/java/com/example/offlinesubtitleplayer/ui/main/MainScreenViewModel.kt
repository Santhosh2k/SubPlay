package com.example.offlinesubtitleplayer.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinesubtitleplayer.domain.model.VideoItem
import com.example.offlinesubtitleplayer.domain.usecase.GetLocalVideosUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel(private val getLocalVideosUseCase: GetLocalVideosUseCase) : ViewModel() {
    val uiState: StateFlow<MainScreenUiState> =
        getLocalVideosUseCase()
            .map<List<VideoItem>, MainScreenUiState> { MainScreenUiState.Success(it) }
            .catch { emit(MainScreenUiState.Error(it)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = MainScreenUiState.Loading
            )
}

sealed interface MainScreenUiState {
    object Loading : MainScreenUiState
    data class Error(val throwable: Throwable) : MainScreenUiState
    data class Success(val videos: List<VideoItem>) : MainScreenUiState
}
