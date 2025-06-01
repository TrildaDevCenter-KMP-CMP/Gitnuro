package com.jetpackduba.gitnuro.images

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.useResource
import com.jetpackduba.gitnuro.extensions.acquireAndUse
import com.jetpackduba.gitnuro.extensions.toByteArray
import com.jetpackduba.gitnuro.logging.printLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_LOADING_IMAGES = 10

private const val TAG = "NetworkImageLoader"

object NetworkImageLoader {
    private val loadingImagesSemaphore = Semaphore(MAX_LOADING_IMAGES)
    private val cache: ImagesCache = InMemoryImagesCache
    private val requests = mutableMapOf<String, Mutex>()

    suspend fun loadImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        requestImageLoad(url) {
            val cachedImage = loadCachedImage(url)

            cachedImage ?: loadImageNetwork(url)
        }
    }

    private suspend fun loadImageNetwork(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        printLog(TAG, "Loading avatar from URL $url")

        try {
            loadingImagesSemaphore.acquireAndUse {
                val imageByteArray = loadImageFromNetwork(url)
                cache.cacheImage(url, imageByteArray)
                return@withContext imageByteArray.toComposeImage()
            }

        } catch (ex: Exception) {
            if (ex !is FileNotFoundException) {
                // Commented as it fills the logs without useless info when there is no internet connection
                //ex.printStackTrace()
            }
        }

        // If a previous return hasn't been called, something has gone wrong, return null
        return@withContext null
    }

    fun loadCachedImage(url: String): ImageBitmap? {
        val cachedImage = cache.getCachedImage(url)

        return cachedImage?.toComposeImage()
    }

    private suspend fun loadImageFromNetwork(link: String): ByteArray = withContext(Dispatchers.IO) {
        val url = URL(link)
        val connection = url.openConnection() as HttpURLConnection
        connection.connect()

        connection.inputStream.toByteArray()
    }

    private suspend fun <T> requestImageLoad(
        url: String,
        block: suspend () -> T,
    ): T {
        val requestMutex = synchronized(requests) {
            val existingMutex = requests[url]

            if (existingMutex != null) {
                existingMutex
            } else {
                val newMutex = Mutex()
                requests[url] = newMutex

                newMutex
            }
        }

        return requestMutex.withLock {
            block()
        }
    }
}

@Composable
fun rememberNetworkImageOrNull(url: String, placeHolderImageRes: String? = null): ImageBitmap? {
    val networkImageLoader = NetworkImageLoader
    val cacheImageUsed = remember { ValueHolder(false) }

    var image by remember(url) {
        val cachedImage = networkImageLoader.loadCachedImage(url)

        val image: ImageBitmap? = when {
            cachedImage != null -> {
                cacheImageUsed.value = true
                cachedImage
            }

            placeHolderImageRes != null -> useResource(placeHolderImageRes) {
                Image.makeFromEncoded(it.toByteArray()).toComposeImageBitmap()
            }

            else -> null
        }

        return@remember mutableStateOf(image)
    }

    LaunchedEffect(url) {
        if (!cacheImageUsed.value) {
            val networkImage = NetworkImageLoader.loadImage(url)

            if (networkImage != null && !cacheImageUsed.value) {
                image = networkImage
            }
        }

    }

    return image
}

fun ByteArray.toComposeImage() = Image.makeFromEncoded(this).toComposeImageBitmap()


internal class ValueHolder<T>(var value: T)