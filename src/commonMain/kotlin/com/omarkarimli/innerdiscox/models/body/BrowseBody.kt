package com.omarkarimli.innerdiscox.models.body

import com.omarkarimli.innerdiscox.models.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class BrowseBody(
    val context: Context,
    val browseId: String? = null,
    val params: String? = null,
    val continuation: String? = null,
)
