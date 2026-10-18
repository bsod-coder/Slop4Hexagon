package com.slop4hexagon.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val socModel: String?,
    val hardware: String?,
    val supportedAbis: List<String>,
    val androidVersion: String,
    val apiLevel: Int,
    val totalMemMb: Long?,
    val availMemMb: Long?,
    val lowMemory: Boolean?,
    val thermalStatus: Int?,
    val appVersion: String,
    val backendStatuses: Map<String, String> = emptyMap()
)
