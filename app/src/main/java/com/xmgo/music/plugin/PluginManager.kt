package com.xmgo.music.plugin

import android.content.Context
import android.util.Log
import com.xmgo.music.plugin.bridge.CredentialsBridge
import com.xmgo.music.plugin.bridge.CryptoBridge
import com.xmgo.music.plugin.bridge.HttpBridge
import com.xmgo.music.plugin.bridge.StorageBridge
import com.xmgo.music.plugin.bridge.WebAuthBridge
import com.xmgo.music.plugin.install.PluginInstaller
import com.xmgo.music.plugin.model.AccountInfo
import com.xmgo.music.plugin.model.ArtistRef
import com.xmgo.music.plugin.model.HomeContent
import com.xmgo.music.plugin.model.HomeSection
import com.xmgo.music.plugin.model.InstalledPlugin
import com.xmgo.music.plugin.model.LoginChallenge
import com.xmgo.music.plugin.model.LoginMethod
import com.xmgo.music.plugin.model.Lyrics
import com.xmgo.music.plugin.model.OnlineAlbum
import com.xmgo.music.plugin.model.OnlineArtist
import com.xmgo.music.plugin.model.OnlineCollection
import com.xmgo.music.plugin.model.OnlinePlaylist
import com.xmgo.music.plugin.model.PagedItems
import com.xmgo.music.plugin.model.SearchResult
import com.xmgo.music.plugin.model.Song
import com.xmgo.music.plugin.model.StreamInfo
import com.xmgo.music.plugin.runtime.PluginRuntime
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 插件管理器：安装 / 加载 / 能力分发。
 * 暴露 MusicSourceProvider 门面给 UI 层。
 */
class PluginManager(private val context: Context) {
    companion object {
        private const val TAG = "PluginManager"
        private const val STREAM_TTL_MS = 15 * 60 * 1000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val pluginsRoot = File(context.filesDir, "plugins")
    private val installer = PluginInstaller(pluginsRoot)
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 插件请求几乎都打同一 API host：默认 maxRequestsPerHost=5 会把
        // 批量 resolveStream/封面下载串死，提高同 host 并发（对齐 qplayer）
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .build()
    private val httpBridge = HttpBridge(httpClient)
    private val cryptoBridge = CryptoBridge()
    private val credentialsBridge = CredentialsBridge(context)
    private val webAuthBridge = WebAuthBridge()
    private val storageBridge = StorageBridge(context)

    private val runtimes = ConcurrentHashMap<String, PluginRuntime>()
    private val _plugins = mutableListOf<InstalledPlugin>()

    /** 已安装插件响应式快照：安装/卸载后自动更新，首页等 UI collectAsState 实时响应。 */
    private val _pluginsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val pluginsFlow: kotlinx.coroutines.flow.StateFlow<List<InstalledPlugin>> = _pluginsFlow

    /** resolveStream 结果短时缓存（避免重复走 JS/网络）。 */
    private data class CachedStream(val info: StreamInfo, val at: Long)

    private val streamCache = object : android.util.LruCache<String, CachedStream>(64) {}


    /** 已安装插件快照（可变，读取时复制）。 */
    val plugins: List<InstalledPlugin> get() = synchronized(_plugins) { _plugins.toList() }

    /** 应用启动时恢复已安装插件。 */
    fun init() {
        val installed = installer.scanInstalled()
        synchronized(_plugins) {
            _plugins.clear()
            _plugins.addAll(installed)
        }
        _pluginsFlow.value = plugins
        Log.i(TAG, "restored ${installed.size} plugins: ${installed.map { it.id }}")
    }

    /** 安装 .qplug 文件并加载运行时（阻塞 IO，调用方自行切协程）。 */
    fun install(zipFile: File): InstalledPlugin {
        val plugin = installer.install(zipFile)
        // 覆盖安装：释放旧运行时（actor 线程 + 协程作用域），防止重复加载泄漏
        runtimes.remove(plugin.id)?.dispose()
        synchronized(_plugins) {
            _plugins.removeAll { it.id == plugin.id }
            _plugins.add(plugin)
        }
        _pluginsFlow.value = plugins
        runtimeFor(plugin)
        return plugin
    }

    fun uninstall(id: String) {
        runtimes.remove(id)?.dispose()
        installer.uninstall(id)
        synchronized(_plugins) { _plugins.removeAll { it.id == id } }
        _pluginsFlow.value = plugins
    }

    // 应用级 scope：安装/卸载是落盘操作，UI 离开（协程取消）也不能中断
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /** 从 SAF Uri 安装（应用级 scope），结果回主线程。 */
    fun installFromUri(uri: android.net.Uri, onResult: (Result<InstalledPlugin>) -> Unit) {
        appScope.launch {
            val r = runCatching {
                val tmp = File.createTempFile("install", ".qplug", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件")
                val plugin = install(tmp)
                tmp.delete()
                plugin
            }
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    /** 卸载（应用级 scope），结果回主线程。 */
    fun uninstallAsync(id: String, onResult: (Result<Unit>) -> Unit) {
        appScope.launch {
            val r = runCatching { uninstall(id) }
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    // ---------- 在线插件目录 ----------

    private val pluginCatalog = com.xmgo.music.plugin.catalog.PluginCatalog(httpClient)

    /** 查询内置目录的最新 release（应用级 scope，结果回主线程）。 */
    fun loadCatalog(onDone: (List<com.xmgo.music.plugin.catalog.CatalogRelease>, String?) -> Unit) {
        appScope.launch {
            val r = runCatching { pluginCatalog.loadLatest() }
            withContext(Dispatchers.Main) {
                onDone(r.getOrElse { emptyList() }, r.exceptionOrNull()?.message)
            }
        }
    }

    /** 从目录下载并安装（应用级 scope），结果回主线程。 */
    fun installFromRelease(
        release: com.xmgo.music.plugin.catalog.CatalogRelease,
        onResult: (Result<InstalledPlugin>) -> Unit
    ) {
        appScope.launch {
            val r = runCatching {
                val tmp = File.createTempFile("catalog", ".qplug", context.cacheDir)
                pluginCatalog.download(release, tmp)
                val plugin = install(tmp)
                tmp.delete()
                plugin
            }
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    fun hasCapability(id: String, capability: String): Boolean =
        plugins.firstOrNull { it.id == id }?.manifest?.capabilities?.contains(capability) == true

    /** 未声明的能力抛 UnsupportedOperationException（MusicSourceProvider 契约）。 */
    private fun requireCapability(id: String, capability: String) {
        if (!hasCapability(id, capability)) {
            throw UnsupportedOperationException("plugin $id lacks capability: $capability")
        }
    }

    /** 取（或懒加载）插件运行时。 */
    private fun runtimeFor(plugin: InstalledPlugin): PluginRuntime {
        return runtimes.getOrPut(plugin.id) {
            PluginRuntime(
                appContext = context.applicationContext,
                plugin = plugin,
                httpBridge = httpBridge,
                cryptoBridge = cryptoBridge,
                credentialsBridge = credentialsBridge,
                webAuthBridge = webAuthBridge,
                storageBridge = storageBridge
            )
        }
    }

    /** 调用插件 handler，超时 60s。 */
    private suspend fun callHandler(id: String, handler: String, args: String): String =
        withTimeout(60_000) {
            withContext(Dispatchers.Default) {
                val plugin = plugins.firstOrNull { it.id == id }
                    ?: throw IllegalStateException("plugin not installed: $id")
                val future: CompletableFuture<String> = runtimeFor(plugin).invokeHandler(handler, args)
                awaitFuture(future)
            }
        }

    private suspend fun <T> awaitFuture(future: CompletableFuture<T>): T =
        kotlinx.coroutines.suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
            future.whenComplete { value, error ->
                if (error != null) cont.resumeWithException(error) else cont.resume(value)
            }
            // 协程取消（点新歌取消 staged 补队列 / 超时）时同步取消底层 future：
            // PluginRuntime 借此跳过已入队的 JS 任务与在途回调，尽快释放单线程 actor
            cont.invokeOnCancellation { future.cancel(true) }
        }

    // ---------- MusicSourceProvider 门面 ----------

    fun providerFor(pluginId: String): MusicSourceProvider = object : MusicSourceProvider {
        override val sourceId: String = pluginId
        override val sourceName: String =
            plugins.firstOrNull { it.id == pluginId }?.name ?: pluginId

        override suspend fun searchSongs(keyword: String, cursor: String?): SearchResult {
            requireCapability(pluginId, "searchSongs")
            val raw = callHandler(pluginId, "searchSongs", searchArgs(keyword, cursor))
            return parseSearchResult(raw, pluginId)
        }

        override suspend fun searchAlbums(keyword: String, cursor: String?): PagedItems<OnlineAlbum> {
            requireCapability(pluginId, "searchAlbums")
            val raw = callHandler(pluginId, "searchAlbums", searchArgs(keyword, cursor))
            val root = json.parseToJsonElement(raw).jsonObject
            return PagedItems(
                items = (root["items"] ?: JsonArray(emptyList())).jsonArray.mapNotNull { parseAlbum(it) },
                nextCursor = root["nextCursor"]?.jsonPrimitive?.contentOrNullSafe()
                    ?.takeIf { it.isNotEmpty() },
                hasMore = !(root["nextCursor"]?.jsonPrimitive?.contentOrNullSafe()).isNullOrEmpty()
            )
        }

        override suspend fun searchArtists(keyword: String, cursor: String?): PagedItems<OnlineArtist> {
            requireCapability(pluginId, "searchArtists")
            val raw = callHandler(pluginId, "searchArtists", searchArgs(keyword, cursor))
            val root = json.parseToJsonElement(raw).jsonObject
            return PagedItems(
                items = (root["items"] ?: JsonArray(emptyList())).jsonArray.mapNotNull { parseArtist(it) },
                nextCursor = root["nextCursor"]?.jsonPrimitive?.contentOrNullSafe()
                    ?.takeIf { it.isNotEmpty() },
                hasMore = !(root["nextCursor"]?.jsonPrimitive?.contentOrNullSafe()).isNullOrEmpty()
            )
        }

        override suspend fun hotSearch(): List<String> {
            requireCapability(pluginId, "hotSearch")
            val raw = callHandler(pluginId, "hotSearch", "{}")
            return runCatching {
                json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                    (el as? JsonPrimitive)?.contentOrNullSafe()
                }
            }.getOrDefault(emptyList())
        }

        override suspend fun home(limit: Int): HomeContent {
            requireCapability(pluginId, "home")
            val args = JsonObject(mapOf("limit" to JsonPrimitive(limit))).toString()
            val raw = callHandler(pluginId, "home", args)
            val root = json.parseToJsonElement(raw).jsonObject
            val songs = (root["songs"] ?: JsonArray(emptyList())).jsonArray
                .mapNotNull { parseSong(it, pluginId) }
            val playlists = (root["playlists"] ?: JsonArray(emptyList())).jsonArray
                .mapNotNull { parsePlaylist(it) }
            val sections = (root["sections"] ?: JsonArray(emptyList())).jsonArray.mapNotNull { secEl ->
                val sec = secEl as? JsonObject ?: return@mapNotNull null
                val title = sec["title"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                val items = (sec["playlists"] ?: JsonArray(emptyList())).jsonArray
                    .mapNotNull { parsePlaylist(it) }
                if (items.isEmpty()) null else HomeSection(title, items)
            }
            return HomeContent(songs = songs, playlists = playlists, sections = sections)
        }

        override suspend fun playlistDetails(id: String): OnlineCollection {
            requireCapability(pluginId, "playlistDetails")
            return parseCollection("playlist", callHandler(pluginId, "playlistDetails", idArgs(id)), pluginId)
        }

        override suspend fun albumDetails(id: String): OnlineCollection {
            requireCapability(pluginId, "albumDetails")
            return parseCollection("album", callHandler(pluginId, "albumDetails", idArgs(id)), pluginId)
        }

        override suspend fun artistDetails(id: String): OnlineCollection {
            requireCapability(pluginId, "artistDetails")
            return parseCollection("artist", callHandler(pluginId, "artistDetails", idArgs(id)), pluginId)
        }

        override suspend fun resolveStream(song: Song): StreamInfo {
            requireCapability(pluginId, "resolveStream")
            // 流地址进程内缓存 15 分钟：同一首歌重复点/重解析直接命中，
            // 免再走 Rhino JS + HTTP（流地址通常 20 分钟左右过期）
            val cacheKey = "$pluginId:${song.rawId}"
            streamCache.get(cacheKey)?.let { hit ->
                if (System.currentTimeMillis() - hit.at < STREAM_TTL_MS) return hit.info
                streamCache.remove(cacheKey)
            }
            val args = JsonObject(
                mapOf("id" to JsonPrimitive(song.rawId))
            ).toString()
            // 弱网偶发失败自动重试一次（退避 400ms）；协程取消/超时不重试
            val raw = try {
                callHandler(pluginId, "resolveStream", args)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resolveStream retry $pluginId:${song.rawId}: ${e.message}")
                kotlinx.coroutines.delay(400)
                callHandler(pluginId, "resolveStream", args)
            }
            val info = parseStream(raw)
            streamCache.put(cacheKey, CachedStream(info, System.currentTimeMillis()))
            return info
        }

        override suspend fun lyrics(song: Song): Lyrics? {
            requireCapability(pluginId, "lyrics")
            val args = JsonObject(mapOf("id" to JsonPrimitive(song.rawId))).toString()
            val raw = callHandler(pluginId, "lyrics", args)
            return parseLyrics(raw)
        }
    }

    // ---------- 登录 ABI（login / account handler） ----------

    /** 可用登录方式列表（operation=methods）。 */
    suspend fun loginMethods(pluginId: String): List<LoginMethod> {
        requireCapability(pluginId, "login")
        val raw = callHandler(pluginId, "login", loginArgs("methods"))
        val root = json.parseToJsonElement(raw)
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> root["methods"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            val type = o["type"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            LoginMethod(
                id = id,
                type = type.lowercase(),
                label = o["label"]?.jsonPrimitive?.contentOrNullSafe() ?: id,
                instructions = o["instructions"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
                webUrl = o["webUrl"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
                cookieUrl = o["cookieUrl"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
                credentialLabel = o["credentialLabel"]?.jsonPrimitive?.contentOrNullSafe() ?: "Cookie"
            )
        }
    }

    /** 发起登录挑战（扫码：operation=begin）。 */
    suspend fun beginLogin(pluginId: String, methodId: String): LoginChallenge {
        requireCapability(pluginId, "login")
        val raw = callHandler(
            pluginId, "login",
            loginArgs("begin", mapOf("methodId" to methodId))
        )
        return parseChallenge(raw)
    }

    /** 轮询扫码状态（operation=poll）。 */
    suspend fun pollLogin(pluginId: String, challengeId: String): LoginChallenge {
        requireCapability(pluginId, "login")
        val raw = callHandler(
            pluginId, "login",
            loginArgs("poll", mapOf("challengeId" to challengeId))
        )
        return parseChallenge(raw)
    }

    /** 提交凭据登录（operation=submit，Cookie 粘贴）。 */
    suspend fun submitLogin(pluginId: String, methodId: String, credential: String): LoginChallenge {
        requireCapability(pluginId, "login")
        val raw = callHandler(
            pluginId, "login",
            loginArgs("submit", mapOf("methodId" to methodId, "credential" to credential))
        )
        return parseChallenge(raw)
    }

    /** 注销（operation=logout）。 */
    suspend fun logout(pluginId: String): Boolean {
        requireCapability(pluginId, "login")
        val raw = callHandler(pluginId, "login", loginArgs("logout"))
        return raw != "false"
    }

    /** 当前账号（account handler）。 */
    suspend fun accountInfo(pluginId: String): AccountInfo {
        requireCapability(pluginId, "account")
        val raw = callHandler(pluginId, "account", "{}")
        return parseAccount(raw)
    }

    private fun loginArgs(operation: String, extra: Map<String, String> = emptyMap()): String =
        JsonObject(
            extra.mapValues { JsonPrimitive(it.value) } + ("operation" to JsonPrimitive(operation))
        ).toString()

    private fun parseChallenge(raw: String): LoginChallenge {
        val o = json.parseToJsonElement(raw).jsonObject
        return LoginChallenge(
            id = o["id"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            methodId = o["methodId"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            status = (o["status"]?.jsonPrimitive?.contentOrNullSafe() ?: "failed").lowercase(),
            qrContent = o["qrContent"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            message = o["message"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
        )
    }

    private fun parseAccount(raw: String): AccountInfo {
        val o = json.parseToJsonElement(raw).jsonObject
        return AccountInfo(
            loggedIn = o["loggedIn"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            id = o["id"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            displayName = o["displayName"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            avatarUrl = o["avatarUrl"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            level = o["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        )
    }

    // ---------- 结果解析（宽容字段名） ----------

    /** ABI 搜索参数：{query, cursor, limit}。 */
    private fun searchArgs(query: String, cursor: String?): String = JsonObject(
        mapOf(
            "query" to JsonPrimitive(query),
            "cursor" to JsonPrimitive(cursor ?: ""),
            "limit" to JsonPrimitive(50)
        )
    ).toString()

    private fun idArgs(id: String): String =
        JsonObject(mapOf("id" to JsonPrimitive(id))).toString()

    private fun parsePlaylist(el: kotlinx.serialization.json.JsonElement): OnlinePlaylist? {
        val o = el as? JsonObject ?: return null
        val id = o["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        return OnlinePlaylist(
            id = id,
            name = name,
            description = o["description"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            artworkUrl = o["artworkUrl"]?.jsonPrimitive?.contentOrNullSafe()
                ?: o["artworkThumbUrl"]?.jsonPrimitive?.contentOrNullSafe(),
            ownerName = (o["owner"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNullSafe(),
            trackCount = o["trackCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            playCount = o["playCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        )
    }

    private fun parseAlbum(el: kotlinx.serialization.json.JsonElement): OnlineAlbum? {
        val o = el as? JsonObject ?: return null
        val id = o["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val artists = (o["artists"] as? JsonArray)?.mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            val aid = ao["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            ArtistRef(aid, ao["name"]?.jsonPrimitive?.contentOrNullSafe() ?: "")
        } ?: emptyList()
        return OnlineAlbum(
            id = id,
            name = name,
            artworkUrl = o["artworkUrl"]?.jsonPrimitive?.contentOrNullSafe()
                ?: o["artworkThumbUrl"]?.jsonPrimitive?.contentOrNullSafe(),
            artists = artists,
            trackCount = o["trackCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            publishTimeMs = o["publishTimeMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        )
    }

    private fun parseArtist(el: kotlinx.serialization.json.JsonElement): OnlineArtist? {
        val o = el as? JsonObject ?: return null
        val id = o["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        return OnlineArtist(
            id = id,
            name = name,
            artworkUrl = o["artworkUrl"]?.jsonPrimitive?.contentOrNullSafe()
                ?: o["artworkThumbUrl"]?.jsonPrimitive?.contentOrNullSafe(),
            description = o["description"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            albumCount = o["albumCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            songCount = o["songCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        )
    }

    /** 歌单/专辑/歌手详情 → OnlineCollection。 */
    private fun parseCollection(kind: String, raw: String, sourceId: String): OnlineCollection {
        val root = json.parseToJsonElement(raw).jsonObject
        val name = root["name"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
        val songs = (root["songs"] ?: JsonArray(emptyList())).jsonArray
            .mapNotNull { parseSong(it, sourceId) }
        return OnlineCollection(
            kind = kind,
            id = root["id"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            name = name,
            description = root["description"]?.jsonPrimitive?.contentOrNullSafe() ?: "",
            artworkUrl = root["artworkUrl"]?.jsonPrimitive?.contentOrNullSafe()
                ?: root["artworkThumbUrl"]?.jsonPrimitive?.contentOrNullSafe(),
            ownerName = (root["owner"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNullSafe(),
            songs = songs,
            albums = (root["albums"] ?: JsonArray(emptyList())).jsonArray
                .mapNotNull { parseAlbum(it) }
        )
    }

    private fun parseSearchResult(raw: String, sourceId: String): SearchResult {
        val root = json.parseToJsonElement(raw).jsonObject
        val items = (root["items"] ?: root["songs"] ?: root["list"] ?: JsonArray(emptyList()))
            .jsonArray
            .mapNotNull { parseSong(it, sourceId) }
        return SearchResult(
            items = items,
            nextCursor = root["nextCursor"]?.jsonPrimitive?.contentOrNullSafe(),
            hasMore = root["hasMore"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        )
    }

    private fun parseSong(el: kotlinx.serialization.json.JsonElement, sourceId: String): Song? {
        val o = el as? JsonObject ?: return null
        val id = (o["id"] ?: o["songId"])?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNullSafe()
            ?: o["title"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val artists = (o["artists"] ?: o["artist"])?.let { a ->
            when (a) {
                is JsonArray -> a.mapNotNull { el ->
                    (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNullSafe()
                        ?: (el as? JsonPrimitive)?.contentOrNullSafe()
                }
                is JsonObject -> listOfNotNull(a["name"]?.jsonPrimitive?.contentOrNullSafe())
                is JsonPrimitive -> listOfNotNull(a.contentOrNullSafe())
                else -> emptyList()
            }
        } ?: emptyList()
        val album = (o["album"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNullSafe()
            ?: o["album"]?.jsonPrimitive?.contentOrNullSafe()
        return Song(
            id = "$sourceId:$id",
            name = name,
            artists = artists,
            album = album,
            durationMs = o["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: o["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            artworkUrl = o["artworkThumbUrl"]?.jsonPrimitive?.contentOrNullSafe()
                ?: o["artworkUrl"]?.jsonPrimitive?.contentOrNullSafe()
                ?: o["artwork"]?.jsonPrimitive?.contentOrNullSafe()
                ?: (o["album"] as? JsonObject)?.get("picUrl")?.jsonPrimitive?.contentOrNullSafe(),
            sourceId = sourceId,
            rawId = id
        )
    }

    private fun parseStream(raw: String): StreamInfo {
        val root = json.parseToJsonElement(raw).jsonObject
        val url = root["url"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw IllegalStateException("resolveStream: no url in $raw")
        val headers = (root["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }
            ?: emptyMap()
        return StreamInfo(
            url = url,
            mimeType = root["mimeType"]?.jsonPrimitive?.contentOrNullSafe(),
            bitrateKbps = root["bitrate"]?.jsonPrimitive?.content?.toIntOrNull(),
            headers = headers
        )
    }

    /** QPlayer ABI：{assets:[{format,role,text}]}；兼容旧 {original,translation}。 */
    private fun parseLyrics(raw: String): Lyrics? {
        val root = json.parseToJsonElement(raw).jsonObject
        val assets = root["assets"] as? kotlinx.serialization.json.JsonArray
        if (assets == null) {
            val original = root["original"]?.jsonPrimitive?.contentOrNullSafe()
                ?: root["lyric"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
            return Lyrics(
                original = original,
                translation = root["translation"]?.jsonPrimitive?.contentOrNullSafe()
            )
        }
        var original: String? = null
        var format: String? = null
        var translation: String? = null
        for (el in assets) {
            val o = el as? kotlinx.serialization.json.JsonObject ?: continue
            val role = o["role"]?.jsonPrimitive?.contentOrNullSafe()?.lowercase() ?: "original"
            val text = o["text"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() } ?: continue
            when (role) {
                "original" -> if (original == null) {
                    original = text
                    format = o["format"]?.jsonPrimitive?.contentOrNullSafe()?.lowercase()
                }
                "translation" -> if (translation == null) translation = text
            }
        }
        val result = original ?: return null
        return Lyrics(original = result, translation = translation, originalFormat = format)
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (content == "null") null else contentOrNull
}
