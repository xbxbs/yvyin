package com.example.xuebimc

/** MediaStore DATE_ADDED is seconds, not milliseconds or a file-modification time. */
internal fun mediaAddedAtMs(seconds: Long): Long =
    if (seconds in 1..(Long.MAX_VALUE / 1_000L)) seconds * 1_000L else 0L

/** An actual absolute DATA/file path is evidence; an opaque provider ID is not. */
internal fun localFileParent(path: String?): String? {
    if (path.isNullOrBlank() || !path.startsWith('/') || path.endsWith('/') || '\u0000' in path) return null
    return path.substringBeforeLast('/').ifEmpty { "/" }
}

/**
 * Only ExternalStorageProvider defines path-shaped document IDs. Keep volume identity and
 * expose a logical parent; never guess an SD-card mount point or turn this into a read path.
 */
internal fun externalStorageDocumentParent(authority: String?, documentId: String?): String? {
    if (authority != "com.android.externalstorage.documents" || documentId.isNullOrBlank()) return null
    val boundary = documentId.indexOf(':')
    if (boundary <= 0) return null
    val volume = documentId.substring(0, boundary)
    if (!volume.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
    val path = documentId.substring(boundary + 1)
    if (path.isEmpty() || path.startsWith('/') || path.endsWith('/') || '\\' in path || '\u0000' in path) return null
    val segments = path.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    val parent = segments.dropLast(1).joinToString("/")
    val root = if (volume == "primary") "内部存储" else "$volume:"
    return if (parent.isEmpty()) root else "$root/$parent"
}

/** Newest known time first; callers use title/key order for both equal and unknown times. */
internal fun compareLibraryAddedAt(leftMs: Long, rightMs: Long): Int =
    rightMs.coerceAtLeast(0L).compareTo(leftMs.coerceAtLeast(0L))
