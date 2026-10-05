package org.echoclock

import android.app.Activity
import android.app.TimePickerDialog
import android.provider.AlarmClock
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.content.res.ColorStateList
import android.widget.CheckBox
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Native settings sheet (DESIGN §7.1): rows generated from the current face's `settings`
 * schema, plus the Alarm row. Values are written through the callbacks, which persist to
 * config.json and push `ec.config(cfg)`.
 */
class SettingsSheet(private val activity: Activity, parent: FrameLayout) {

    /** A rendered row; [value] returns the schema-typed value to persist. */
    private class Row(val key: String, val value: () -> Any?)

    private val container = FrameLayout(activity).apply {
        setBackgroundColor(0xF00A0D12.toInt())
        visibility = View.GONE
    }

    private val title = TextView(activity).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 20f
        gravity = Gravity.CENTER_VERTICAL
    }

    private val rows = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(24), activity.dp(4), activity.dp(24), activity.dp(4))
    }

    private var faceRows: List<Row> = emptyList()
    private var onSave: ((JSONObject) -> Unit)? = null
    private var onAlarm: ((Int, Int) -> Unit)? = null

    init {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(24), activity.dp(10), activity.dp(12), activity.dp(6))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(
                rows,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(10))
        }
        footer.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { hide() }
        })
        footer.addView(Button(activity).apply {
            text = "Save"
            setOnClickListener { save() }
        })

        column.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(
            footer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        container.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        parent.addView(
            container,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    fun isShown(): Boolean = container.visibility == View.VISIBLE

    fun hide() {
        container.visibility = View.GONE
    }

    fun show(
        faceName: String,
        schema: List<FaceSetting>,
        current: JSONObject,
        onSave: (JSONObject) -> Unit,
        onAlarm: (Int, Int) -> Unit
    ) {
        this.onSave = onSave
        this.onAlarm = onAlarm
        title.text = "Settings \u2014 $faceName"
        rows.removeAllViews()
        faceRows = schema.map { buildRow(it, current.opt(it.key) ?: it.default) }

        // v3 (DESIGN §8.4): the alarm row opens the system Clock's alarm UI.
        rows.addView(sectionHeader("Alarm"))
        rows.addView(labelRow("Set alarm", Button(activity).apply {
            text = "Open Clock\u2026"
            setOnClickListener { pickAlarm() }
        }))

        container.visibility = View.VISIBLE
        container.bringToFront()
    }

    /**
     * Opens the **system Clock app's** alarm UI rather than a local picker, so the alarm is
     * owned by DeskClock: it rings properly, survives reboot, and shows up as the next alarm
     * on the clock faces. Falls back to a local time picker only if no Clock app
     * answers the intent.
     */
    private fun pickAlarm() {
        val now = Calendar.getInstance()
        val tries = listOf(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, now.get(Calendar.HOUR_OF_DAY))
                .putExtra(AlarmClock.EXTRA_MINUTES, now.get(Calendar.MINUTE)),
            Intent(AlarmClock.ACTION_SHOW_ALARMS)
        )
        for (intent in tries) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activity.startActivity(intent)
                hide()
                return
            } catch (t: Throwable) {
                Util.warn("no Clock app for ${intent.action}", t)
            }
        }
        val callback = onAlarm
        if (callback == null) {
            hide()
            return
        }
        TimePickerDialog(
            activity,
            { _, hour, minute -> callback(hour, minute) },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(activity)
        ).show()
    }

    // ---- rows ----------------------------------------------------------------

    private fun save() {
        try {
            val values = JSONObject()
            for (row in faceRows) {
                val v = row.value() ?: continue
                values.put(row.key, v)
            }
            if (values.length() > 0) onSave?.invoke(values)
        } catch (t: Throwable) {
            Util.warn("settings save failed", t)
        }
        hide()
    }

    private fun buildRow(setting: FaceSetting, rawValue: Any?): Row {
        val value = if (rawValue == null || rawValue == JSONObject.NULL) setting.default else rawValue
        return when (setting.type) {
            "bool" -> {
                // A CheckBox, tinted so it is clearly visible on the dark sheet (the plain
                // framework Switch was nearly invisible here — no visible thumb/track).
                val cb = CheckBox(activity).apply {
                    isChecked = when (value) {
                        is Boolean -> value
                        is Number -> value.toInt() != 0
                        else -> value?.toString().equals("true", ignoreCase = true)
                    }
                    gravity = Gravity.END
                    buttonTintList = ColorStateList.valueOf(0xFF7FD8FF.toInt())
                    minimumWidth = activity.dp(48)
                    minimumHeight = activity.dp(44)
                    contentDescription = setting.label
                }
                rows.addView(labelRow(setting.label, cb))
                Row(setting.key) { cb.isChecked }
            }
            "int" -> {
                val lo = setting.min
                val hi = setting.max
                val start = ((value as? Number)?.toInt() ?: lo).coerceIn(lo, hi)
                val valueLabel = TextView(activity).apply {
                    text = start.toString()
                    setTextColor(0xFFDDE7F0.toInt())
                    textSize = 16f
                    gravity = Gravity.CENTER
                    minWidth = activity.dp(48)
                }
                val seek = SeekBar(activity).apply {
                    max = (hi - lo).coerceAtLeast(1)
                    progress = start - lo
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            valueLabel.text = (lo + progress).toString()
                        }

                        override fun onStartTrackingTouch(sb: SeekBar?) = Unit

                        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                    })
                }
                val control = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(seek, LinearLayout.LayoutParams(activity.dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(valueLabel)
                }
                rows.addView(labelRow(setting.label, control))
                Row(setting.key) { lo + seek.progress }
            }
            "enum" -> {
                val options = setting.options
                val spinner = Spinner(activity).apply {
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, options).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    val idx = options.indexOfFirst { it.equals(value?.toString().orEmpty(), ignoreCase = true) }
                    if (idx >= 0) setSelection(idx)
                }
                rows.addView(labelRow(setting.label, spinner))
                Row(setting.key) { options.getOrNull(spinner.selectedItemPosition) ?: "" }
            }
            "color" -> {
                val palette = LinkedHashSet<String>()
                palette.addAll(
                    listOf("#4fd6ff", "#3fd0ff", "#ff5a5a", "#ffcf3f", "#35d07f", "#c792ea", "#ffffff", "#8fb4c8")
                )
                val currentColor = value?.toString()?.takeIf { it.isNotEmpty() } ?: "#ffffff"
                palette.add(currentColor)
                var selected = currentColor
                val hex = TextView(activity).apply {
                    text = currentColor
                    setTextColor(0xFF8FB4C8.toInt())
                    textSize = 14f
                }
                val strip = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val swatches = LinkedHashMap<String, View>()
                for (c in palette) {
                    val dot = View(activity).apply {
                        setBackgroundColor(parseColor(c))
                        alpha = if (c == currentColor) 1f else 0.45f
                        setOnClickListener {
                            selected = c
                            hex.text = c
                            for ((key, view) in swatches) view.alpha = if (key == c) 1f else 0.45f
                        }
                    }
                    val lp = LinearLayout.LayoutParams(activity.dp(28), activity.dp(28))
                    lp.marginStart = activity.dp(6)
                    strip.addView(dot, lp)
                    swatches[c] = dot
                }
                val control = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(hex)
                    addView(strip)
                }
                rows.addView(labelRow(setting.label, control))
                Row(setting.key) { selected }
            }
            "list" -> {
                val field = EditText(activity).apply {
                    setText(listToText(value))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 2
                    maxLines = 4
                }
                rows.addView(fieldRow(setting.label, field))
                Row(setting.key) { FaceStore.splitList(field.text.toString()) }
            }
            else -> {
                val field = EditText(activity).apply {
                    setText(value?.toString().orEmpty())
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                rows.addView(fieldRow(setting.label, field))
                Row(setting.key) { field.text.toString() }
            }
        }
    }

    private fun sectionHeader(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(0xFF7FA6BB.toInt())
        textSize = 15f
        letterSpacing = 0.2f
        setPadding(0, activity.dp(16), 0, activity.dp(4))
    }

    private fun labelRow(label: String, control: View): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(7), 0, activity.dp(7))
        }
        row.addView(labelView(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(control)
        return row
    }

    private fun fieldRow(label: String, field: EditText): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(7), 0, activity.dp(7))
        }
        row.addView(labelView(label))
        row.addView(
            field,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return row
    }

    private fun labelView(label: String): TextView = TextView(activity).apply {
        text = label
        setTextColor(0xFFDDE7F0.toInt())
        textSize = 16f
    }

    private fun listToText(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length()).joinToString("\n") { value.optString(it, "") }
        is Collection<*> -> value.joinToString("\n") { it.toString() }
        else -> value?.toString().orEmpty()
    }

    private fun parseColor(s: String): Int = try {
        Color.parseColor(s)
    } catch (t: Throwable) {
        0xFF888888.toInt()
    }
}
