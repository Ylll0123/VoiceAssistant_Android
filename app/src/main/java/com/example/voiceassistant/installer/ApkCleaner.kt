package com.example.voiceassistant.installer

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.voiceassistant.R
import java.io.File

/**
 * 装完之后把安装包清掉。
 *
 * ## 为什么不能「默默地删」
 * Android 10 起有了分区存储：一个 App **不能**随便删别的 App 写的文件。
 * 用户点浏览器下载的 APK 属于浏览器，本应用既没有权限、
 * 也不该有权限去悄悄删它——这条限制是安全模型的一部分，
 * 不是靠申请某个权限就能绕过的。
 *
 * 所以这里分两步走：
 * 1. **自己的文件直接删**（[deleteSilently]）。将来做应用内升级时，
 *    APK 是本应用自己下载的，可以做到真正的「装完即删」；
 * 2. **别人的文件走系统确认框**（[deleteRequest]）。系统会弹一句
 *    「允许 HardlySeek 删除此文件吗？」，用户点一下才删——
 *    这是官方给的唯一合规路径。
 *
 * ## 为什么只认自己的安装包
 * 只删文件名里带本应用名的 APK（[nameKeys]）。
 * 宁可漏删，也不能把用户下载的别的安装包删了。
 */
object ApkCleaner {

    /** 找到的一个安装包 */
    data class Installer(
        val uri: Uri,
        val name: String,
        val size: Long,
        /** 能直接按路径删的时候用它（API 28 / 自己的文件） */
        val file: File? = null
    ) {
        val sizeText: String
            get() = when {
                size <= 0 -> ""
                size > 1024 * 1024 -> String.format("%.1f MB", size / 1024.0 / 1024.0)
                else -> "${size / 1024} KB"
            }
    }

    /**
     * 找下载目录里属于本应用的安装包。
     *
     * 两条路都试：MediaStore（Android 10+，能拿到 content:// 才能走系统删除）
     * 和公共下载目录（老系统直接可读）。
     */
    fun findInstallers(context: Context): List<Installer> {
        val app = context.applicationContext
        val keys = nameKeys(context)
        val found = LinkedHashMap<String, Installer>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE
            )
            runCatching {
                app.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("%.apk"),
                    "${MediaStore.Downloads.DATE_ADDED} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        if (!looksLikeOurs(name, keys)) continue
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        )
                        found[uri.toString()] = Installer(uri, name, cursor.getLong(2))
                    }
                }
            }
        }

        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", true) &&
                    looksLikeOurs(file.name, keys)
                ) {
                    found["file:${file.absolutePath}"] = Installer(
                        Uri.fromFile(file), file.name, file.length(), file
                    )
                }
            }
        }
        return found.values.toList()
    }

    /**
     * 尝试直接删（不需要系统弹窗）。
     *
     * @return true 表示真的删掉了；false 表示没有权限，得走 [deleteRequest]
     */
    fun deleteSilently(context: Context, installer: Installer): Boolean {
        installer.file?.let { file ->
            return runCatching { file.delete() }.getOrDefault(false)
        }
        return runCatching {
            context.contentResolver.delete(installer.uri, null, null) > 0
        }.getOrDefault(false)
    }

    /**
     * 走系统删除确认：返回一个 IntentSender，交给界面 launcher 启动。
     *
     * 只有 Android 11+ 有这套 API。Android 10 及以下直接返回 null
     * （那个版本要拿 `RecoverableSecurityException` 里的授权 Intent，
     * 代码路径更多、收益极小——这版机型占比已经很低，不值得为它增加出错面）。
     *
     * @return null 表示这个系统版本无法发起删除请求
     */
    fun deleteRequest(context: Context, installers: List<Installer>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = installers.map { it.uri }
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }.getOrNull()
    }

    /** 文件名像不像本应用的安装包 */
    private fun looksLikeOurs(name: String, keys: List<String>): Boolean {
        val lower = name.lowercase()
        if (!lower.endsWith(".apk")) return false
        return keys.any { lower.contains(it) }
    }

    /** 认哪些名字：当前应用名 + 历史名字（改名前的包用户可能还没删） */
    private fun nameKeys(context: Context): List<String> {
        val current = context.getString(R.string.app_name)
            .lowercase()
            .replace(" ", "")
            .replace("-", "")
        return listOf(current, "hardlyseek", "deepsuck", "voiceassistant", "app-debug")
            .filter { it.isNotBlank() }
            .distinct()
    }

    // ------------------------------------------------------------------ 手动兜底

    /**
     * 这个包是不是「侧载」装进来的（自己传 APK / 文件管理器点开装的）。
     *
     * 用途：从应用商店装的包，下载目录里通常没有安装包，就别去问用户
     * 「要不要删安装包」了——那种打扰纯属多余。
     */
    fun wasSideLoaded(context: Context): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        return installer == null || !installer.startsWith("com.android.vending")
    }

    /**
     * 让用户自己选一个 APK 来删（SAF）。
     *
     * 为什么需要这条路：浏览器下载的文件属于浏览器，本应用连**看见**都做不到
     * （分区存储），更别说删。SAF 是系统给的授权通道——用户选中的那个文件，
     * 会临时把读写权限授给本应用，这时才删得掉。
     */
    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/vnd.android.package-archive"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("application/vnd.android.package-archive", "application/octet-stream")
        )
    }

    /** 删掉用户选中的那个文件（无法删除时返回 false，由调用方如实告知） */
    fun deletePicked(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val byDocument = runCatching {
            DocumentsContract.deleteDocument(resolver, uri)
        }.getOrDefault(false)
        if (byDocument) return true
        return runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }
}
