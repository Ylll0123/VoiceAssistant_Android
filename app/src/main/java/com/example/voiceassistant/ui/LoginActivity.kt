package com.example.voiceassistant.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.voiceassistant.R
import com.example.voiceassistant.data.AccountStore
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.data.LocaleHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * 登录 / 注册界面。
 *
 * 没有服务器：账号只存在这台手机上（见 [AccountStore]），
 * 作用是把「谁的对话和记忆」区分开，顺便满足课程里对登录页的要求。
 *
 * **必须登录才能用**（v4.0 起取消游客）：对话记录、提醒、会话标题都按账号隔离，
 * 没有身份就没有归属，所以这里不再留「游客进入」的后门。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var store: AccountStore
    private lateinit var toggleAuth: MaterialButtonToggleGroup
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirm: EditText
    private lateinit var btnSubmit: MaterialButton

    /** 当前是注册模式还是登录模式 */
    private var registerMode = false

    /** 界面语言：和主界面同一套规则，见 [LocaleHelper] */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, AppSettings.readLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContentView(R.layout.activity_login)
        applyInsets()

        store = AccountStore(this)

        toggleAuth = findViewById(R.id.toggleAuth)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etConfirm = findViewById(R.id.etConfirm)
        btnSubmit = findViewById(R.id.btnSubmit)

        bindMode()
        bindActions()
    }

    private fun applyInsets() {
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun bindMode() {
        // 先按当前模式对齐一次，再挂监听，避免初始化时把状态改掉
        toggleAuth.check(if (registerMode) R.id.btnTabRegister else R.id.btnTabLogin)
        toggleAuth.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val next = checkedId == R.id.btnTabRegister
            if (next != registerMode) {
                registerMode = next
                renderMode()
            }
        }
    }

    private fun renderMode() {
        etConfirm.visibility = if (registerMode) View.VISIBLE else View.GONE
        btnSubmit.setText(
            if (registerMode) R.string.login_submit_register else R.string.login_submit_login
        )
        // 切换模式时清掉密码：避免「注册失败后残留的密码」被拿去登录
        etPassword.setText("")
        etConfirm.setText("")
    }

    private fun bindActions() {
        btnSubmit.setOnClickListener { submit() }
        etConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        val username = etUsername.text.toString()
        val password = etPassword.text.toString()
        val error = if (registerMode) {
            store.register(username, password, etConfirm.text.toString())
        } else {
            store.login(username, password)
        }

        if (error != null) {
            toast(getString(errorText(error)))
            return
        }

        toast(getString(R.string.login_welcome, store.currentUser.orEmpty()))
        goMain()
    }

    /** 错误码 → 文案 */
    private fun errorText(error: AccountStore.Error): Int = when (error) {
        AccountStore.Error.EMPTY -> R.string.login_error_empty
        AccountStore.Error.SHORT_USERNAME -> R.string.login_error_empty
        AccountStore.Error.SHORT_PASSWORD -> R.string.login_error_short_password
        AccountStore.Error.MISMATCH -> R.string.login_error_mismatch
        AccountStore.Error.USER_EXISTS -> R.string.login_error_user_exists
        AccountStore.Error.NO_USER -> R.string.login_error_no_user
        AccountStore.Error.WRONG_PASSWORD -> R.string.login_error_wrong_password
    }

    /**
     * 进入主界面。
     *
     * 用 CLEAR_TASK：主界面是「发现没登录 → 拉起登录页 → 自己 finish」进来的，
     * 不清理任务栈的话，从主界面按返回会退回登录页。
     */
    private fun goMain() {
        startActivity(
            Intent(this, VoiceAssistantActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
