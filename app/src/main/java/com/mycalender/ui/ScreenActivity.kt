package com.mycalender.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mycalender.MyCalenderApp
import com.mycalender.R

abstract class ScreenActivity : Activity() {
    protected val app get() = application as MyCalenderApp

    protected fun page(): LinearLayout {
        val root = column().apply {
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(getColor(R.color.background))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        return root
    }

    protected fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    protected fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    protected fun text(copy: String, size: Float = 15f, bold: Boolean = false, muted: Boolean = false) =
        TextView(this).apply {
            text = copy
            textSize = size
            setTextColor(getColor(if (muted) R.color.muted else R.color.foreground))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    protected fun button(copy: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = copy
        isAllCaps = false
        setTextColor(getColor(if (primary) R.color.on_accent else R.color.foreground))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(getColor(if (primary) R.color.accent else R.color.card))
        }
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minimumHeight = dp(48)
        setOnClickListener { action() }
    }

    protected fun field(value: String, placeholder: String) = EditText(this).apply {
        setText(value)
        hint = placeholder
        textSize = 16f
        setTextColor(getColor(R.color.foreground))
        setHintTextColor(getColor(R.color.muted))
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
    }

    protected fun LinearLayout.add(view: View, gap: Int = 0) {
        addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(gap) })
    }

    protected fun LinearLayout.cell(view: View, weight: Float = 1f, gap: Int = 0) {
        addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, weight).apply { marginStart = dp(gap) })
    }

    protected fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
