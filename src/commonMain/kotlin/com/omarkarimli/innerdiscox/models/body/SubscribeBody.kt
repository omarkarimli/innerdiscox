package com.omarkarimli.innerdiscox.models.body

import com.omarkarimli.innerdiscox.models.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class SubscribeBody(
    val context: Context,
    val channelIds: List<String>,
    val params: String? = null,
)
