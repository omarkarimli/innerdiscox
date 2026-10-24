package com.omarkarimli.innerdiscox.models.body

import com.omarkarimli.innerdiscox.models.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class CreatePlaylistBody(
    val context: Context,
    val title: String,
    val privacyStatus: String,
    val videoIds: List<String>? = null,
)
