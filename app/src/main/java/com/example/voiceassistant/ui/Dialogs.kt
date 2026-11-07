package com.example.voiceassistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.voiceassistant.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * 全应用统一的弹窗样式：**白底、黑字、圆角**。
 *
 * 所有确认框、列表选择都从这里出，不要再用 `MaterialAlertDialogBuilder(context)` 裸构造——
 * 那样会落到 Material3 的色调化底色上（实测是 #E7E7E6 的灰白），和浅色界面放在一起显得脏。
 *
 * 为什么要在代码里画底板：Material 1.10 没有暴露 `materialAlertDialogContainerStyle`，
 * 想换掉那层「按主题现算」的颜色，只能建完 dialog 之后自己换背景。
 */
fun Context.appDialog(): MaterialAlertDialogBuilder =
    MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_VoiceAssistant_Dialog)

/**
 * 显示成白纸弹窗。
 *
 * 用法和 `.show()` 一样，只是把底板换成纯白圆角：
 * ```
 * context.appDialog().setTitle(...).setMessage(...).showWhite()
 * ```
 */
fun MaterialAlertDialogBuilder.showWhite(): AlertDialog {
    val dialog = create()
    runCatching { dialog.window?.setBackgroundDrawable(whiteDialogBackground(dialog.context)) }
    dialog.show()
    return dialog
}

/** 纯白圆角底板；外面留 16dp，圆角才不会被窗口边缘切掉 */
private fun whiteDialogBackground(context: Context): Drawable {
    val radius = 20f * context.resources.displayMetrics.density
    val shape = MaterialShapeDrawable(
        ShapeAppearanceModel.Builder()
            .setAllCorners(CornerFamily.ROUNDED, radius)
            .build()
    ).apply {
        fillColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))
        elevation = 6f * context.resources.displayMetrics.density
    }
    val inset = (16 * context.resources.displayMetrics.density).toInt()
    return InsetDrawable(shape, inset)
}
