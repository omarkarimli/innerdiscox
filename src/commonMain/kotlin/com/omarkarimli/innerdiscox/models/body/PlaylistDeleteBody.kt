package com.omarkarimli.innerdiscox.models.body

import com.omarkarimli.innerdiscox.models.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class PlaylistDeleteBody(
    val context: Context,
    val playlistId: String,
)
