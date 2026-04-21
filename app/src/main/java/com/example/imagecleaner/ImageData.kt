package com.example.imagecleaner

import android.net.Uri

data class ImageData(
    val uri: Uri,
    val dateAdded: Long,
    val displayName: String,
    var isSelected: Boolean = false
)
