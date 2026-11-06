package com.example.voiceassistant.dialog

import com.example.voiceassistant.fuzzy.IntentKind
import com.example.voiceassistant.fuzzy.IntentResult
import com.example.voiceassistant.reminder.Reminder
import com.example.voiceassistant.reminder.ReminderPort
import com.example.voiceassistant.tool.ReminderTime
import com.example.voiceassistant.tool.ToolIntent
import java.time.ZoneId

/**
 * 多轮上下文对话管理器。
 *
 * 它只做**提醒域**的对话（新建 / 查看 / 改时间 / 取消 / 再提醒）：
 * 别的问题交给普通问答链路，这样才能做到「提醒类操作不调用大模型」——
 * 模板化回复零延迟、零 token，这也是框架里强调的那条原则。
 *
 * ## 三件真正难的活
 *
 * ### ① 槽位补全
 * 用户：「提醒我交作业」→ 意图 `set_remind`，但 `time` 是空的。
 * 直接建一条「没时间」的提醒等于骗人（永远不会响），所以反问一句
 * 「几点提醒你？」，把内容先挂在 [DialogueSession.partialContent] 上。
 * 下一轮「三点」进来时，不需要再出现「提醒」两个字也能接上。
 *
 * ### ② 指代消解
 * 用户：「把它改成四点」——「它」是谁？靠 [DialogueSession.refReminderId]。
 * 每次建 / 改 / 查都会记住最近操作的那一条，所以用户不用重复说内容。
 * 目标被删掉时会顺手把引用清掉，避免下一轮改到一条不存在的提醒。
 *
 * ### ③ 会话过期
 * 上下文只在内存活 [DialogueSession.DEFAULT_TIMEOUT_MS]（120 秒）。
 * 超时后「改成四点」不再被当成指代，而是老老实实反问——
 * 这比对着十分钟前的一句话瞎猜要好得多。
 */
class DialogueManager(
    private val reminders: ReminderPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    val session: DialogueSession = DialogueSession()
) {

    /** 一次处理的结果 */
    sealed interface Outcome {

        /** 处理完了，[text] 就是要说的话 */
        data class Handled(
            val text: String,
            val intentId: String = ToolIntent.REMINDER,
            val reminderId: Int = 0
        ) : Outcome

        /** 需要用户补一句（追问） */
        data class Ask(
            val text: String,
            val intentId: String = ToolIntent.REMINDER
        ) : Outcome

        /** 不属于提醒域，交给后面的问答链路 */
        object NotMine : Outcome
    }

    /** 这句话要不要由对话管理器接手（引擎没听懂，但正在等用户补时间时也要接） */
    fun claims(intent: IntentResult): Boolean = intent.actionable || session.waiting

    /**
     * 处理一句话。
     *
     * @param raw ASR 原文（补时间时要从里面再解析一次时间）
     * @param intent 模糊引擎给出的标准意图
     */
    fun handle(raw: String, intent: IntentResult): Outcome {
        val now = clock()
        session.expire(now)
        session.touch(now)

        // ① 上一轮在等时间：这句话先当「时间」试一次
        //    但如果用户干脆说了句完整的新指令（带内容），以新指令为准
        if (session.state == DialogueSession.State.EXPECT_TIME) {
            val newFullCommand = intent.kind == IntentKind.SET_REMIND &&
                intent.slots.content.isNotBlank()
            if (!newFullCommand) {
                answerTime(raw, now)?.let { time ->
                    return complete(now, raw, session.partialContent, time, session.partialRepeat)
                }
                session.countRetry()
                if (session.timeRetries < MAX_TIME_RETRIES) {
                    return finish(
                        now, raw,
                        Outcome.Ask("没听清是几点。可以说「明天下午三点」或者「十分钟后」。"),
                        keepPending = true
                    )
                }
                // 问两次还答不上来就放弃，别把用户困在追问里
                val content = session.partialContent
                session.resetPending()
                return finish(now, raw, Outcome.Handled("没听清时间，先不设了。想设的话说「明天下午三点提醒我$content」。"))
            }
            session.resetPending()
        }

        if (!intent.actionable) return Outcome.NotMine

        return when (intent.kind) {
            IntentKind.SET_REMIND -> set(now, raw, intent)
            IntentKind.LIST_REMIND -> list(now, raw)
            IntentKind.UPDATE_REMIND -> update(now, raw, intent)
            IntentKind.CANCEL_REMIND -> cancel(now, raw, intent)
            IntentKind.CLEAR_REMIND -> clear(now, raw)
            IntentKind.SNOOZE_REMIND -> snooze(now, raw)
            else -> Outcome.NotMine
        }
    }

    // ------------------------------------------------------------------ 各意图

    private fun set(now: Long, raw: String, intent: IntentResult): Outcome {
        val content = intent.slots.content.trim()
        if (content.isBlank()) {
            return finish(now, raw, Outcome.Ask("要提醒你什么？比如「提醒我明天八点交报告」。"))
        }

        val at = intent.slots.timeAt
        if (at <= 0L) {
            // 缺时间 → 挂起内容，追问一句
            session.expectTime(content, intent.slots.repeat, now)
            val repeatHint = if (intent.slots.repeat == Reminder.Repeat.NONE) "" else "（${intent.slots.repeat.label}）"
            return finish(
                now, raw,
                Outcome.Ask("好，$content$repeatHint。几点提醒你？"),
                keepPending = true
            )
        }
        return complete(now, raw, content, ReminderTime.Resolved(at, intent.slots.timeLabel), intent.slots.repeat)
    }

    private fun list(now: Long, raw: String): Outcome {
        val items = reminders.list()
        if (items.isEmpty()) {
            return finish(
                now, raw,
                Outcome.Handled("现在没有提醒。说「提醒我明天八点……」我给你记一条。")
            )
        }
        if (items.size == 1) session.rememberRef(items[0].id)
        val body = items.mapIndexed { i, item -> "${i + 1}. ${describe(item, now)}" }
            .joinToString("；")
        return finish(now, raw, Outcome.Handled("你有 ${items.size} 条提醒：$body。"))
    }

    private fun update(now: Long, raw: String, intent: IntentResult): Outcome {
        val items = reminders.list()
        if (items.isEmpty()) {
            return finish(now, raw, Outcome.Handled("现在没有提醒可以改。"))
        }

        // 目标：说了序号按序号，否则用上下文里那条，只有一个时就用它
        val target = when {
            intent.slots.index > 0 -> items.getOrNull(intent.slots.index - 1)
                ?: return finish(
                    now, raw,
                    Outcome.Handled("第 ${intent.slots.index} 条不存在，现在只有 ${items.size} 条。")
                )
            else -> items.firstOrNull { it.id == session.refReminderId } ?: items.singleOrNull()
        } ?: return finish(
            now, raw,
            Outcome.Ask("要改哪一条？现在有 ${items.size} 条，说「改第 2 条到四点」这样就行。")
        )

        val at = when {
            intent.slots.timeAt > 0L -> intent.slots.timeAt
            intent.slots.shiftMs != 0L -> target.triggerAt + intent.slots.shiftMs
            else -> return finish(now, raw, Outcome.Ask("要改到几点？比如「改到四点」。"))
        }

        val updated = reminders.updateTime(target.id, at)
            ?: return finish(now, raw, Outcome.Handled("那条提醒没找到，可能已经被删了。"))

        session.rememberRef(updated.id)
        return finish(
            now, raw,
            Outcome.Handled(
                "已将提醒改为 ${ReminderTime.describe(updated.triggerAt, now)}：${updated.content}。",
                reminderId = updated.id
            )
        )
    }

    private fun cancel(now: Long, raw: String, intent: IntentResult): Outcome {
        val items = reminders.list()
        if (items.isEmpty()) return finish(now, raw, Outcome.Handled("现在没有提醒。"))

        val target = if (intent.slots.index > 0) {
            items.getOrNull(intent.slots.index - 1) ?: return finish(
                now, raw,
                Outcome.Handled("第 ${intent.slots.index} 条不存在，现在只有 ${items.size} 条。")
            )
        } else {
            items.firstOrNull { it.id == session.refReminderId } ?: items.last()
        }

        reminders.cancel(target.id)
        session.clearRef(target.id)
        return finish(now, raw, Outcome.Handled("取消了：${target.content}。"))
    }

    private fun clear(now: Long, raw: String): Outcome {
        val count = reminders.cancelAll()
        if (count == 0) return finish(now, raw, Outcome.Handled("现在没有提醒。"))
        session.rememberRef(null)
        return finish(now, raw, Outcome.Handled("好，$count 条提醒全删了。"))
    }

    private fun snooze(now: Long, raw: String): Outcome {
        val target = reminders.list().firstOrNull { it.id == session.refReminderId }
            ?: reminders.list().lastOrNull()
            ?: return finish(now, raw, Outcome.Handled("现在没有提醒可以再提醒。"))

        val updated = reminders.snooze(target.id, SNOOZE_MINUTES)
            ?: return finish(now, raw, Outcome.Handled("那条提醒没找到，可能已经删了。"))
        session.rememberRef(updated.id)
        return finish(
            now, raw,
            Outcome.Handled(
                "好，${SNOOZE_MINUTES} 分钟后再提醒你：${updated.content}。",
                reminderId = updated.id
            )
        )
    }

    // ------------------------------------------------------------------ 内部

    /** 把这句话当「时间」解析（补时间那一轮用） */
    private fun answerTime(raw: String, now: Long): ReminderTime.Resolved? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 单独一句「三点」也能解析；带「就三点吧」这类口水话同样能吃下
        return ReminderTime.resolve(text, now, zone)
    }

    /** 建一条提醒并给出确认话术 */
    private fun complete(
        now: Long,
        raw: String,
        content: String,
        time: ReminderTime.Resolved,
        repeat: Reminder.Repeat
    ): Outcome {
        val created = reminders.create(content, time.at, repeat, session.id)
        session.rememberRef(created.id)
        val text = if (repeat == Reminder.Repeat.NONE) {
            "好，${time.label} 提醒你：$content。"
        } else {
            "好，从 ${time.label} 开始，${repeat.label}这个时间提醒你：$content。"
        }
        return finish(now, raw, Outcome.Handled(text, reminderId = created.id))
    }

    /** 收尾：记一轮历史 + 记活动时间 */
    private fun finish(
        now: Long,
        raw: String,
        outcome: Outcome,
        keepPending: Boolean = false
    ): Outcome {
        val reply = when (outcome) {
            is Outcome.Handled -> outcome.text
            is Outcome.Ask -> outcome.text
            Outcome.NotMine -> return outcome
        }
        session.recordRound(raw, reply, now)
        // 追问那一轮不能清「半截指令」，否则用户回「三点」时上下文已经没了
        if (!keepPending) session.resetPending()
        return outcome
    }

    /** 清单一行：有时间就带上时间，没时间的只念内容 */
    private fun describe(item: Reminder, now: Long): String =
        if (item.triggerAt > 0L) {
            "${ReminderTime.describe(item.triggerAt, now)} ${item.content}"
        } else {
            item.content
        }

    companion object {
        /** 追问时间的次数上限：问两次还答不上就放弃 */
        const val MAX_TIME_RETRIES = 2

        /** 「再提醒我」默认延后多久 */
        const val SNOOZE_MINUTES = 10L
    }
}
