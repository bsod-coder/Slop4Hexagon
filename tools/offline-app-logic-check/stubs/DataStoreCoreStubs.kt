@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package androidx.datastore.core

import kotlinx.coroutines.flow.Flow

interface DataStore<T> {
    val data: Flow<T>
    suspend fun updateData(mutation: suspend (curr: T) -> T): T
    suspend fun setData(value: T)
}
