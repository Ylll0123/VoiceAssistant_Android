package com.example.voiceassistant.provider

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.service.AndroidPhoneBook
import com.example.voiceassistant.service.PhoneBook
import com.example.voiceassistant.service.PhoneFormats

/**
 * 通讯与出行域：`phone/…`
 *
 * 这一域和 `device/…` 分开，是因为它关系到「人」：打给谁、去哪儿。
 * 两者的失败方式也完全不同——设备操作失败是「东西不听话」，这里失败是「找错人了」，
 * 所以宁可多问一句，也不替用户在几个同名的人里挑一个。
 *
 * ## 三个降级，一个都不许假装成功
 * | 情况 | 怎么处理 |
 * | --- | --- |
 * | 没有读通讯录的权限 | 不猜，直接说「给我权限，或者说号码」 |
 * | 有权限但没这个人 | 说没找到，**不模糊到别人头上** |
 * | 有 CALL_PHONE | `ACTION_CALL` 直接拨 |
 * | 没有 CALL_PHONE | `ACTION_DIAL` 把号码填进拨号盘，等用户按一下 |
 * | 没有地图应用 | 退回高德网页地图；再不行就如实说 |
 *
 * `ACTION_DIAL` 不需要任何权限，所以「打不开通讯录」不等于「这个功能废了」——
 * 用户说号码照样能打。
 */
class PhoneSupply(
    context: Context,
    private val book: PhoneBook = AndroidPhoneBook(context)
) {

    private val app: Context = context.applicationContext

    val all: List<Supply> = listOf(call(), navigate())

    // ------------------------------------------------------------------ 打电话

    private fun call(): Supply = object : Supply {
        override val name = "phone/call"
        override val summary =
            "打电话。参数 to 是联系人姓名或号码。姓名会去通讯录里查，" +
                "重名时会失败并给出候选，这时要问用户是哪一位，不要自己挑。"

        override fun invoke(args: BusArgs): BusReply {
            val to = args.str("to") ?: return BusReply.fail("要打给谁？")

            // 直接给号码：不用通讯录权限
            if (PhoneFormats.looksLikeNumber(to)) {
                val number = PhoneFormats.normalizeNumber(to)
                if (number.isEmpty()) return BusReply.fail("这个号码看着不对。")
                return dial(number, to)
            }

            return when (val found = book.find(to)) {
                is PhoneBook.Lookup.One -> dial(found.number, found.name)
                is PhoneBook.Lookup.Many -> BusReply.fail(
                    "通讯录里有 ${found.names.size} 个可能的人：${found.names.joinToString("、")}。" +
                        "要打给哪一个？说全名，或者直接给我号码。"
                )
                PhoneBook.Lookup.None -> BusReply.fail(
                    "通讯录里没找到「$to」。说全名试试，或者把号码告诉我。"
                )
                PhoneBook.Lookup.Denied -> BusReply.fail(
                    "我还读不到通讯录，所以不知道「$to」的号码。" +
                        "给我通讯录权限我就能自己查；或者直接把号码说给我。",
                    "need_permission" to "phone"
                )
            }
        }
    }

    /**
     * 真的拨出去。
     *
     * 有 `CALL_PHONE` 就直接拨；没有就只把号码送进拨号盘——那一步不需要权限，
     * 用户按一下绿色键即可。两条路都会先确认系统里有应用能接这个 intent，
     * 否则平板上会出现「说了没反应」。
     */
    private fun dial(number: String, who: String): BusReply {
        val canCall = ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(app.packageManager) == null) {
            // 少数设备没拨号应用（平板/车机）；TEL scheme 也接不了就只能说实话
            return BusReply.fail("这台设备没有能拨号的电话应用。")
        }

        val started = runCatching { app.startActivity(intent) }.isSuccess
        if (!started) return BusReply.fail("拨号没起来，系统可能拦了后台跳转。")

        return if (canCall) {
            BusReply.ok("正在给${who}打电话。")
        } else {
            BusReply.ok(
                "号码 $number 已经填进拨号盘了，按一下拨号键就行。" +
                    "（给我打电话权限，我就能直接拨出去。）",
                "need_permission" to "phone_call"
            )
        }
    }

    // ------------------------------------------------------------------ 导航

    private fun navigate(): Supply = object : Supply {
        override val name = "phone/navigate"
        override val summary =
            "打开地图导航到某个地方。参数 to 是目的地（地名或地址），" +
                "mode 可选 drive（驾车，默认）/ walk（步行）/ bike（骑行）/ transit（公交）。"

        override fun invoke(args: BusArgs): BusReply {
            val to = args.str("to") ?: return BusReply.fail("要去哪儿？")
            val uri = PhoneFormats.geoUri(to, args.str("mode"))
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (intent.resolveActivity(app.packageManager) != null &&
                runCatching { app.startActivity(intent) }.isSuccess
            ) {
                return BusReply.ok("导航到「$to」，已经打开地图了。")
            }

            /*
             * 没有地图应用接 geo:（或者后台跳转被拦）。
             *
             * 退回网页地图：高德的搜索页不需要 key、国内直连，
             * 比「打不开就算了」有用得多。
             */
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(PhoneFormats.mapWebFallback(to))
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (web.resolveActivity(app.packageManager) == null) {
                return BusReply.fail("这台设备没有地图应用，也没有浏览器，导航用不了。")
            }
            return if (runCatching { app.startActivity(web) }.isSuccess) {
                BusReply.ok("没找到地图应用，已经用网页地图搜「$to」了。")
            } else {
                BusReply.fail("地图打不开，系统可能拦了后台跳转。")
            }
        }
    }
}
