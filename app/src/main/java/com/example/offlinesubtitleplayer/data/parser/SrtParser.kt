package com.example.offlinesubtitleplayer.data.parser

import android.util.Log
import com.example.offlinesubtitleplayer.domain.model.SubtitleCue
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object SrtParser {
    private const val TAG = "SrtParser"

    /**
     * Parses an input stream of an SRT file into a list of SubtitleCues.
     */
    fun parse(inputStream: InputStream): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val reader = BufferedReader(InputStreamReader(inputStream))
        
        var line: String?
        var state = ParserState.INDEX
        var currentId = 0
        var currentStartTime = 0L
        var currentEndTime = 0L
        val currentText = StringBuilder()

        try {
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                
                when (state) {
                    ParserState.INDEX -> {
                        if (trimmed.isEmpty()) continue
                        currentId = trimmed.toIntOrNull() ?: 0
                        state = ParserState.TIMECODE
                    }
                    ParserState.TIMECODE -> {
                        if (trimmed.isEmpty()) {
                            state = ParserState.INDEX
                            continue
                        }
                        
                        val timeParts = trimmed.split("-->")
                        if (timeParts.size == 2) {
                            try {
                                currentStartTime = parseTimecode(timeParts[0])
                                currentEndTime = parseTimecode(timeParts[1])
                                state = ParserState.TEXT
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing timecode on line: $trimmed", e)
                                state = ParserState.INDEX // Reset
                            }
                        } else {
                            state = ParserState.INDEX // Reset on malformed
                        }
                    }
                    ParserState.TEXT -> {
                        if (trimmed.isEmpty()) {
                            // Subtitle block finished
                            if (currentText.isNotEmpty()) {
                                cues.add(
                                    SubtitleCue(
                                        id = currentId,
                                        startTimeMs = currentStartTime,
                                        endTimeMs = currentEndTime,
                                        text = currentText.toString().trim()
                                    )
                                )
                                currentText.clear()
                            }
                            state = ParserState.INDEX
                        } else {
                            if (currentText.isNotEmpty()) {
                                currentText.append("\n")
                            }
                            currentText.append(trimmed)
                        }
                    }
                }
            }
            
            // Add final cue if stream ends without an empty line
            if (currentText.isNotEmpty()) {
                cues.add(
                    SubtitleCue(
                        id = currentId,
                        startTimeMs = currentStartTime,
                        endTimeMs = currentEndTime,
                        text = currentText.toString().trim()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing SRT stream", e)
        } finally {
            try {
                reader.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseTimecode(timecode: String): Long {
        val cleaned = timecode.trim().replace(',', '.')
        val parts = cleaned.split(":")
        if (parts.size != 3) throw IllegalArgumentException("Malformed time format")
        
        val hours = parts[0].toLong()
        val minutes = parts[1].toLong()
        
        val secondsParts = parts[2].split(".")
        val seconds = secondsParts[0].toLong()
        
        val milliseconds = if (secondsParts.size > 1) {
            secondsParts[1].padEnd(3, '0').take(3).toLong()
        } else {
            0L
        }
        
        return (hours * 3600000L) + (minutes * 60000L) + (seconds * 1000L) + milliseconds
    }

    private enum class ParserState {
        INDEX,
        TIMECODE,
        TEXT
    }
}
