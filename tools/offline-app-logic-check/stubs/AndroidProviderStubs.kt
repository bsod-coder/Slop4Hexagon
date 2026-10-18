@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package android.provider

import android.content.ContentValues
import android.net.Uri

object MediaStore {
    object Images {
        object Media {
            val EXTERNAL_CONTENT_URI: Uri = Uri()
            val INTERNAL_CONTENT_URI: Uri = Uri()
            const val DISPLAY_NAME = "_display_name"
            const val MIME_TYPE = "mime_type"
            const val RELATIVE_PATH = "relative_path"
            const val IS_PENDING = "is_pending"
        }
    }

    object Files {
        val EXTERNAL_CONTENT_URI: Uri = Uri()
        object Media {
            const val DISPLAY_NAME = "_display_name"
            const val MIME_TYPE = "mime_type"
        }
    }
}

object OpenableColumns {
    object Display {
        const val _ID = "_id"
        const val DISPLAY_NAME = "display_name"
        const val SIZE = "_size"
    }
}
