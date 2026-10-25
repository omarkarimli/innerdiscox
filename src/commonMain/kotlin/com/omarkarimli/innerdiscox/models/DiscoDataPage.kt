package com.omarkarimli.innerdiscox.models

import kotlinx.serialization.Serializable

@Serializable
data class DiscoDataPage<T>(
    val items: List<T>,
    val continuation: String? = null,
)
