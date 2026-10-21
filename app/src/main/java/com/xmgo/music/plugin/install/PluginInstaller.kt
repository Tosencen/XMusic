package com.xmgo.music.plugin.install

import android.util.Log
import com.xmgo.music.plugin.model.InstalledPlugin
import com.xmgo.music.plugin.model.PluginManifest
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * .qplug 安装器：zip 解包到私有目录，解析 plugin.json。
 * 自用阶段跳过 META-INF 签名校验（ECDSA P-256），仅记录警告。
 */
class PluginInstaller(private val pluginsRoot: File) {
    companion object {
        private const val TAG = "PluginInstaller"
        private const val MANIFEST = "plugin.json"
    }

    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (!pluginsRoot.exists()) pluginsRoot.mkdirs()
    }

    /** 安装 zip 文件；返回 InstalledPlugin。重复安装同一 id 会覆盖。 */
    fun install(zipFile: File): InstalledPlugin {
        require(zipFile.exists()) { "qplug not found: ${zipFile.path}" }
        var manifest: PluginManifest? = null
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(MANIFEST)
                ?: throw IllegalArgumentException("not a qplug: $MANIFEST missing")
            manifest = json.decodeFromString<PluginManifest>(
                zip.getInputStream(entry).bufferedReader().readText()
            )
        }
        val m = manifest!!
        validate(m)
        val targetDir = File(pluginsRoot, m.id)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            val rootPath = targetDir.canonicalFile.toPath()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val out = File(targetDir, e.name).canonicalFile
                if (!out.toPath().startsWith(rootPath)) {
                    throw IllegalArgumentException("zip entry escapes plugin dir: ${e.name}")
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(e).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        val hasSignature = ZipFile(zipFile).use { it.getEntry("META-INF/qplayer.sig") != null }
        if (!hasSignature) Log.w(TAG, "plugin ${m.id} installed without signature (self-use mode)")
        Log.i(TAG, "installed plugin ${m.id} v${m.version} (${m.capabilities.size} capabilities)")
        return InstalledPlugin(m, targetDir)
    }

    /** 扫描已安装目录，恢复 InstalledPlugin 列表。 */
    fun scanInstalled(): List<InstalledPlugin> {
        val dirs = pluginsRoot.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val mf = File(dir, MANIFEST)
            if (!mf.exists()) return@mapNotNull null
            runCatching {
                InstalledPlugin(json.decodeFromString<PluginManifest>(mf.readText()), dir)
            }.onFailure { e ->
                Log.w(TAG, "scan skip ${dir.name}: ${e.message}")
            }.getOrNull()
        }
    }

    fun uninstall(id: String) {
        File(pluginsRoot, id).deleteRecursively()
    }

    private fun validate(m: PluginManifest) {
        require(m.id.isNotBlank()) { "manifest id blank" }
        require(m.id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "invalid plugin id: ${m.id}" }
        require(File(m.entry).let { !it.isAbsolute && !it.normalize().startsWith("..") }) {
            "invalid entry path: ${m.entry}"
        }
    }
}
