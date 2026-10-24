package com.omarkarimli.innerdiscox.models.body

import com.omarkarimli.innerdiscox.models.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class GetQueueBody(
    val context: Context,
    val videoIds: List<String>?,
    val playlistId: String?,
)
