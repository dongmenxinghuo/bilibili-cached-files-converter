package com.example.bilicache

data class BiliCacheItem(
    val dir: String,
    val videoPath: String,
    val audioPath: String,
    val title: String,
    val sizeBytes: Long,
    var selected: Boolean = false,
    var codec: String = "",       // "hevc" / "avc" / "" 未知
    var status: String = ""       // 空 / "合并中" / "转码中" / "完成" / "失败"
)
