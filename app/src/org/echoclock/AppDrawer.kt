package org.echoclock

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * In-activity app-drawer overlay (DESIGN §2.5): scrollable grid of launchable apps.
 * Tapping launches; the "Clock" button closes and returns to the clock.
 */
class AppDrawer(private val activity: Activity, parent: FrameLayout) {

    private data class AppItem(val label: String, val icon: Drawable, val intent: Intent)

    private val container = FrameLayout(activity).apply {
        setBackgroundColor(0xF00A0D12.toInt())
        visibility = View.GONE
    }

    private val grid = GridView(activity)
    private var items: List<AppItem> = emptyList()

    init {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(12), activity.dp(8))
        }
        val title = TextView(activity).apply {
            text = activity.getString(R.string.drawer_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = Button(activity).apply {
            text = activity.getString(R.string.drawer_close)
            setOnClickListener { hide() }
        }
        header.addView(close)

        grid.numColumns = 5
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = activity.dp(4)
        grid.verticalSpacing = activity.dp(4)
        grid.setPadding(activity.dp(12), activity.dp(4), activity.dp(12), activity.dp(12))
        grid.adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(activity.dp(4), activity.dp(10), activity.dp(4), activity.dp(10))
                }
                val item = items[position]
                val icon = ImageView(activity).apply { setImageDrawable(item.icon) }
                cell.addView(icon, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
                val label = TextView(activity).apply {
                    text = item.label
                    setTextColor(0xFFDDE7F0.toInt())
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                cell.addView(
                    label,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = activity.dp(6) }
                )
                return cell
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemClickListener
            try {
                activity.startActivity(item.intent)
            } catch (t: Throwable) {
                Util.warn("cannot launch ${item.label}", t)
            }
            hide()
        }

        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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

    fun show() {
        reload()
        container.visibility = View.VISIBLE
        container.bringToFront()
    }

    fun hide() {
        container.visibility = View.GONE
    }

    private fun reload() {
        val pm = activity.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = try {
            pm.queryIntentActivities(query, 0)
        } catch (t: Throwable) {
            Util.warn("app query failed", t)
            emptyList()
        }
        val out = LinkedHashMap<String, AppItem>()
        for (ri in resolved) {
            val info = ri.activityInfo ?: continue
            val pkg = info.packageName ?: continue
            if (pkg == activity.packageName) continue
            val name = info.name ?: continue
            val key = "$pkg/$name"
            if (out.containsKey(key)) continue
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (t: Throwable) {
                name
            }
            val icon = try {
                ri.loadIcon(pm)
            } catch (t: Throwable) {
                null
            }
            if (icon == null) continue
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(pkg, name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            out[key] = AppItem(label, icon, intent)
        }
        items = out.values.sortedBy { it.label.lowercase() }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }
}
