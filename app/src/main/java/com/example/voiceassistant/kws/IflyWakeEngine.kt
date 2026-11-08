package com.example.voiceassistant.kws

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.iflytek.aiui.AIUIAgent
import com.iflytek.aiui.AIUIConstant
import com.iflytek.aiui.AIUIEvent
import com.iflytek.aiui.AIUIListener
import com.iflytek.aiui.AIUIMessage
import com.iflytek.aiui.AIUISetting
import java.io.File

/**
 * 讯飞 AIUI 的**离线唤醒（IVW）**封装。
 *
 * ## 为什么是 AIUI 而不是 `IVWRecognizer`
 * 网上那套「`IVWRecognizer.createRecognizer` + `ivw_threshold` + 手动喂 PCM」
 * 属于 **MSC 语音 SDK**（`Msc.jar` 里的 `com.iflytek.cloud.ivw`）。
 * 用户给的 `Android_aiui_soft_6.7` 是 **AIUI SDK**：`AIUI.jar` 里只有
 * `AIUIAgent`，没有 `IVWRecognizer`，`libaiui.so` 内部自己做了
 * 「录音 → VAD → 唤醒 → 识别」的整条链路，只对外抛事件。
 *
 * 所以这里是**按 AIUI 的方式**接的：唤醒在 cfg 里打开（`wakeup_mode=on`、
 * `ivw.res_path` 指向唤醒资源），命中后抛 `EVENT_WAKEUP`。
 * 好处是不用自己喂 PCM，坏处是拿不到逐帧打分——能调的只有 [threshold]。
 *
 * ## 它比系统识别强在哪
 * - 真·常驻监听：不用每轮「说一句 → 等结果」地空转；
 * - 离线：不依赖系统中文包，也不走网络识别；
 * - 有分数：`ivw_threshold` 是模型打分阈值，能按环境调灵敏。
 *
 * ## 用不了的时候
 * - 手机是 x86（模拟器）：SDK 只带 `armeabi-v7a` / `arm64-v8a`，直接不支持；
 * - 没填三件套：不启动，继续用系统识别；
 * - 鉴权失败（11200 / 11209 / 11210）：说明开放平台那边没开通离线唤醒，
 *   或者唤醒资源和 appid 不是一套——这两件事只能在控制台解决，代码无能为力，
 *   所以**自动退回系统识别**，不让用户对着一个死掉的开关发呆。
 */
class IflyWakeEngine(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        /** 真被叫醒了。[score] 是引擎给的分数（没有就是 0） */
        fun onWake(score: Int)

        /** 引擎出错。上层据此决定降级还是停 */
        fun onError(code: Int, message: String)

        /** 登录/鉴权过了，可以开始听 */
        fun onReady()
    }

    private var agent: AIUIAgent? = null

    fun start(): Boolean {
        if (agent != null) return true
        if (!supported()) {
            listener.onError(ERR_UNSUPPORTED, "这台设备的 CPU 跑不了讯飞唤醒（只支持 arm 手机）")
            return false
        }

        return runCatching {
            val res = prepareResources()
            AIUISetting.setShowLog(false)
            AIUISetting.setLogLevel(AIUISetting.LogLevel.info)
            AIUISetting.setSystemInfo(AIUIConstant.KEY_SERIAL_NUM, deviceId())

            val params = buildParams(
                appId = appId,
                apiKey = apiKey,
                apiSecret = apiSecret,
                resPath = res.absolutePath,
                threshold = threshold
            )
            Log.i(TAG, "createAgent: appid=${appId.take(4)}*** res=${res.absolutePath}")

            val created = AIUIAgent.createAgent(context, params, events)
            if (created == null) {
                listener.onError(ERR_CREATE, "讯飞引擎没起来（createAgent 返回空）")
                return@runCatching false
            }
            agent = created
            startRecord()
            true
        }.getOrElse { error ->
            // 最常见的两种：x86 上 UnsatisfiedLinkError、资源缺失
            Log.w(TAG, "启动失败：${error.javaClass.simpleName} ${error.message}")
            listener.onError(ERR_CREATE, "讯飞引擎加载失败：${error.javaClass.simpleName}")
            false
        }
    }

    /** 让出麦克风（用户开始说话时调） */
    fun pause() {
        val live = agent ?: return
        runCatching {
            live.sendMessage(
                AIUIMessage(AIUIConstant.CMD_STOP_RECORD, 0, 0, "data_type=audio", null)
            )
        }
    }

    /** 把麦克风还回来 */
    fun resume() {
        if (agent == null) return
        startRecord()
    }

    fun release() {
        val live = agent ?: return
        agent = null
        runCatching { live.destroy() }
    }

    // ------------------------------------------------------------------ 内部

    private fun startRecord() {
        val live = agent ?: return
        runCatching {
            // 唤醒本身就是「一边录一边打分」，所以要先开录音
            live.sendMessage(
                AIUIMessage(
                    AIUIConstant.CMD_START_RECORD,
                    0,
                    0,
                    "data_type=audio,sample_rate=16000",
                    null
                )
            )
        }
    }

    private val events = AIUIListener { event: AIUIEvent ->
        when (event.eventType) {
            AIUIConstant.EVENT_WAKEUP -> {
                val score = event.arg1
                Log.i(TAG, "EVENT_WAKEUP score=$score info=${event.info}")
                listener.onWake(score)
            }

            AIUIConstant.EVENT_CONNECTED_TO_SERVER -> listener.onReady()

            AIUIConstant.EVENT_ERROR -> {
                val code = event.arg1
                Log.w(TAG, "EVENT_ERROR code=$code info=${event.info}")
                listener.onError(code, describeError(code))
            }

            AIUIConstant.EVENT_STATE -> {
                Log.d(TAG, "EVENT_STATE arg1=${event.arg1}")
            }
        }
    }

    /**
     * 把唤醒资源从 assets 摊到私有目录，并重写 `vtn.ini`。
     *
     * 两个坑：
     * 1. 唤醒资源**不能直接从 assets 读**，原生层要的是文件路径；
     * 2. `vtn.ini` 里写着 `appid=` 和 `res_path=`，必须改成用户自己的 appid
     *    和本机的绝对路径——否则鉴权必挂（11210 appid 不匹配）。
     *
     * 用 `filesDir` 而不是 `/sdcard/AIUI`：Android 10 起没有存储权限写不了根目录，
     * 而私有目录永远可读可写。
     */
    private fun prepareResources(): File {
        val dir = File(context.filesDir, "ifly/ivw/vtn")
        val res = File(dir, "res.bin")
        if (!res.exists() || res.length() == 0L) {
            dir.mkdirs()
            copyAsset("ifly/ivw/vtn/res.bin", res)
        }

        val vad = File(context.filesDir, "ifly/vad/evad_16k.jet")
        if (!vad.exists() || vad.length() == 0L) {
            vad.parentFile?.mkdirs()
            copyAsset("ifly/vad/evad_16k.jet", vad)
        }

        // 每次都重写：appid 和路径都可能被用户改过
        File(dir, "vtn.ini").writeText(
            """
            [auth]
            appid=$appId

            [cae]
            cae_enable = 1
            input_audio_unit=2

            [ivw]
            ivw_enable = 1
            res_path=${res.absolutePath}
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )

        return res
    }

    private fun copyAsset(path: String, dest: File) {
        context.assets.open(path).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun deviceId(): String = runCatching {
        // 只用「装一次变一次」的安装 ID，不碰硬件标识
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ).orEmpty().ifEmpty { "hardlyseek" }
    }.getOrDefault("hardlyseek")

    private val appId: String get() = settings.iflyAppId
    private val apiKey: String get() = settings.iflyApiKey
    private val apiSecret: String get() = settings.iflyApiSecret
    private val threshold: Int get() = settings.iflyThreshold

    private val settings: com.example.voiceassistant.data.AppSettings by lazy {
        com.example.voiceassistant.data.AppSettings(context)
    }

    companion object {
        private const val TAG = "IflyWake"

        /** 自家错误：设备跑不了 */
        const val ERR_UNSUPPORTED = -1001

        /** 自家错误：引擎没起来 */
        const val ERR_CREATE = -1002

        /**
         * 这台机器能不能加载讯飞的原生库。
         *
         * SDK 只带 arm 的 `.so`，模拟器（x86_64）一定会 `UnsatisfiedLinkError`。
         * 与其让它崩一次，不如提前说不支持，安静地退回系统识别。
         */
        fun supported(): Boolean {
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            return abi == "arm64-v8a" || abi == "armeabi-v7a" || abi == "armeabi"
        }

        /**
         * AIUI 的启动参数（纯函数，可单测）。
         *
         * 只留下唤醒需要的部分：VAD、录音、唤醒、日志。
         * 场景与云端交互全部关掉——我们要的只是「听见唤醒词」，
         * 后面的识别走应用自己的语音识别，不必让 AIUI 再连一次云端。
         */
        fun buildParams(
            appId: String,
            apiKey: String,
            apiSecret: String,
            resPath: String,
            threshold: Int
        ): String {
            fun esc(value: String) = CloudLlmHandler.escapeJson(value)
            return """
            {
              "login": {
                "appid": "${esc(appId)}",
                "api_secret": "${esc(apiSecret)}",
                "key": "${esc(apiKey)}"
              },
              "global": { "scene": "sos_app", "aiui_ver": "3" },
              "vad": {
                "vad_enable": "1",
                "engine_type": "evad",
                "res_type": "assets",
                "res_path": "ifly/vad/evad_16k.jet",
                "vad_bos": "5000",
                "vad_eos": "1000",
                "threshold": "0.6"
              },
              "iat": { "sample_rate": "16000" },
              "recorder": { "channel_count": 1, "channel_filter": "0,-1" },
              "ivw": {
                "mic_type": "mic1",
                "res_type": "path",
                "res_path": "${esc(resPath)}",
                "ivw_threshold": "${threshold.coerceIn(0, 3000)}"
              },
              "speech": {
                "data_source": "sdk",
                "wakeup_mode": "on",
                "interact_mode": "continuous"
              },
              "tts": { "play_mode": "sdk", "buffer_time": 0, "data_encoding": "raw" },
              "log": { "debug_log": "1", "save_datalog": "0" }
            }
            """.trimIndent()
        }

        /**
         * 这些错误说明「这条路走不通」，应该退回系统识别，而不是原地重试。
         *
         * 授权、appid 与资源不匹配、额度用完，都是控制台侧的事——
         * 在手机上重试一百次也是同一个结果，只会让用户对着死开关发呆。
         */
        fun isFatal(code: Int): Boolean = code in FATAL

        private val FATAL = setOf(
            11200, 11201, 11202, 11203, 11205, 11209, 11210, 11211, 11212, 11216,
            11300, 11301, 11302, 11303, 11306,
            ERR_UNSUPPORTED, ERR_CREATE
        )

        /**
         * 错误码 → 人话（纯函数，可单测）。
         *
         * 这几种是**配置问题**，代码改不了，必须让用户知道去控制台做什么：
         * 11209 = 没开通离线唤醒；11210 = 唤醒资源和 appid 不是一套。
         */
        fun describeError(code: Int): String = when (code) {
            11200, 11201, 11216 ->
                "讯飞账号没有离线唤醒的授权（$code）：去开放平台「离线唤醒」里开通服务"
            11209 ->
                "这个 appid 没开通「离线唤醒」（11209）：到控制台给应用加上这个服务"
            11210 ->
                "唤醒资源和 appid 对不上（11210）：jet/res 是从别的应用导出的，重新下一份"
            11202, 11203 ->
                "讯飞授权无效或过期（$code）：检查 appid / apiKey / apiSecret"
            11300, 11301, 11306 ->
                "离线唤醒的装机量/并发额度用完了（$code）"
            20001, 20002, 20003 ->
                "连不上讯飞服务器（$code）：首次启动要联网做一次鉴权"
            20006 -> "麦克风打不开（20006）：检查录音权限，或者别的应用正占着麦克风"
            ERR_UNSUPPORTED -> "这台设备的 CPU 跑不了讯飞唤醒（只支持 arm 手机）"
            ERR_CREATE -> "讯飞引擎没起来，先退回系统识别"
            else -> "讯飞唤醒报错（$code）"
        }
    }
}
