package com.omarkarimli.innerdiscox.models.body

import com.omarkarimli.innerdiscox.models.Context
import kotlinx.serialization.Serializable

@Serializable
internal data class SearchBody(
    val context: Context,
    val query: String? = null,
    val params: String? = null,
)
