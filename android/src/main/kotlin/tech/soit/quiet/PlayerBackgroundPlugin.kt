package tech.soit.quiet

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.view.FlutterMain
import io.flutter.view.FlutterNativeView
import io.flutter.view.FlutterRunArguments
import kotlinx.coroutines.withTimeout
import tech.soit.quiet.utils.*


data class Config(
        val enableCache: Boolean = false,
        val userAgent: String?
) {

    companion object {
        val Default = Config(enableCache = false, userAgent = null)
    }

    constructor(map: Map<String, Any>) : this(
            enableCache = map["enableCache"] as? Boolean ?: false,
            userAgent = map["userAgent"] as? String
    )

}


interface BackgroundHandle {

    val config: Config get() = Config.Default

    suspend fun loadImage(description: MediaDescriptionCompat, uri: Uri): Artwork?

    suspend fun getPlayUrl(id: String, fallback: String?): Uri

}

typealias BackgroundRegistrarCallback = (registry: PluginRegistry) -> Unit


private object DefaultBackgroundHandle : BackgroundHandle {

    override suspend fun loadImage(description: MediaDescriptionCompat, uri: Uri): Artwork? {
        val bytes = loadArtworkFromUri(uri) ?: return null
        return createArtworkFromByteArray(bytes)
    }

    override suspend fun getPlayUrl(id: String, fallback: String?): Uri {
        return Uri.parse(fallback)
    }

}


class MusicPlayerBackgroundPlugin(
        private val methodChannel: MethodChannel,
        private val dartExecutor: DartExecutor
) : MethodChannel.MethodCallHandler, BackgroundHandle {


    companion object {


        private const val NAME = "tech.soit.quiet/background_callback"

        private var registrarCallback: BackgroundRegistrarCallback? = null

        fun setOnRegisterCallback(callback: BackgroundRegistrarCallback) {
            registrarCallback = callback
        }

        /**
         * start flutter background isolate
         *
         *
         * Note:
         * The flutter background entry point should be placed in lib/main.dart (the same as main() method)
         *
         */
        fun startBackgroundIsolate(context: Context): BackgroundHandle {
            try {
                FlutterMain.startInitialization(context)
            } catch (e: UnsatisfiedLinkError) {
                // in android test mode, we don't have libflutter.so in apk
                return DefaultBackgroundHandle
            }
            FlutterMain.ensureInitializationComplete(context, null)
            val appBundlePath = FlutterMain.findAppBundlePath()
            val nativeView = FlutterNativeView(context, true)

            val arguments = FlutterRunArguments().apply {
                bundlePath = appBundlePath
                entrypoint = "playerBackgroundService"
                libraryPath = ""
            }
            nativeView.runFromBundle(arguments)
            val channel = MethodChannel(
                    nativeView.pluginRegistry.registrarFor(MusicPlayerBackgroundPlugin::class.java.name).messenger(),
                    NAME
            )
            registrarCallback?.invoke(nativeView.pluginRegistry)
            val helper = MusicPlayerBackgroundPlugin(channel, nativeView.dartExecutor)
            channel.setMethodCallHandler(helper)
            return helper
        }
    }


    override var config = Config(false, null)


    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "updateConfig") {
            config = Config(call.arguments())
            result.success(null)
        } else {
            result.notImplemented()
        }
    }


    private suspend inline fun <reified T> MethodChannel.invokeAsyncCast(
            method: String,
            arguments: Any?,
            noinline onNotImplement: suspend () -> T
    ): T {
        if (dartExecutor.isolateServiceId == null) {
            // run background entry point failed
            log(LoggerLevel.ERROR) {
                """
                    We can not run background isolate, if you want custom player behavior, maybe you
                    should add a top function named with playerBackgroundService() at you main.dart
                """.trimIndent()
            }
        }
        return runCatching {
            withTimeout(10000) {
                invokeAsync(method, arguments, onNotImplement)
            }
        }.getOrElse { onNotImplement() }

    }


    override suspend fun loadImage(description: MediaDescriptionCompat, uri: Uri): Artwork? {
        val bytes = methodChannel.invokeAsyncCast("loadImage", description.toMap()) {
            loadArtworkFromUri(uri)
        } ?: return null
        return createArtworkFromByteArray(bytes)
    }


    override suspend fun getPlayUrl(id: String, fallback: String?): Uri {
        val url = methodChannel.invokeAsyncCast(
                "getPlayUrl", mapOf("id" to id, "url" to fallback)
        ) { fallback }
        return Uri.parse(url)
    }


}