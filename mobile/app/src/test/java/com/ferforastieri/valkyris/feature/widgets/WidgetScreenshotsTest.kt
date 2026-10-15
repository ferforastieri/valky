package com.ferforastieri.valkyris.feature.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.ferforastieri.valkyris.R
import com.ferforastieri.valkyris.ScreenshotTest
import java.io.File
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the home screen widgets to PNGs (no emulator) so the rows can be
 * compared with the app's own list. Files land in app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Category(ScreenshotTest::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class WidgetScreenshotsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun inflate(id: Int): View = LayoutInflater.from(context).inflate(id, null)

    /** Tamanho em dp (a captura usa pixels, então converte pela densidade). */
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun View.sized(width: Int, height: Int): View {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        layout(0, 0, measuredWidth, measuredHeight)
        return this
    }

    private fun View.save(name: String) {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        val file = File("build/screenshots/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lightRow(name: String, state: String, on: Boolean, offline: Boolean, color: Int): View {
        val row = inflate(R.layout.widget_light_row)
        row.findViewById<TextView>(R.id.widget_light_name).text = name
        row.findViewById<TextView>(R.id.widget_light_state).apply {
            text = state
            setTextColor(if (offline) context.getColor(R.color.widget_alarm) else context.getColor(R.color.widget_muted))
        }
        row.findViewById<ImageView>(R.id.widget_light_bulb).apply {
            setImageResource(if (on) R.drawable.widget_bulb_on else R.drawable.widget_bulb_off)
            setColorFilter(if (on) color else context.getColor(R.color.widget_on_secondary_container))
        }
        row.findViewById<ImageView>(R.id.widget_light_glow).apply {
            visibility = if (on) View.VISIBLE else View.GONE
            if (on) setColorFilter(color)
        }
        return row
    }

    @Test
    fun lightRows() {
        val widget = inflate(R.layout.widget_lights)
        val list = widget.findViewById<ViewGroup>(R.id.widget_lights_list)
        list.addView(lightRow("Luz Suíte", "Ligada · 99%", on = true, offline = false, color = 0xFFFFC98A.toInt()))
        list.addView(lightRow("Sala de TV", "Ligada · 40%", on = true, offline = false, color = 0xFF7EC8FF.toInt()))
        list.addView(lightRow("Sala de Jantar", "Desligada", on = false, offline = false, color = 0xFFFFFFFF.toInt()))
        list.addView(lightRow("Quarto bebê", "Indisponível", on = false, offline = true, color = 0xFFFFFFFF.toInt()))
        widget.sized(dp(360), dp(430)).save("widget-light-rows.png")
    }

    private fun remote(letters: Boolean): View {
        val widget = inflate(R.layout.widget_remote)
        widget.findViewById<TextView>(R.id.widget_remote_name).text = "TV da sala"
        widget.findViewById<TextView>(R.id.widget_remote_abc)
            .text = context.getString(if (letters) R.string.widget_remote_keypad_letters else R.string.widget_remote_keypad_toggle)
        widget.findViewById<View>(R.id.widget_remote_digits).visibility = if (letters) View.GONE else View.VISIBLE
        widget.findViewById<View>(R.id.widget_remote_letters).visibility = if (letters) View.VISIBLE else View.GONE
        return widget
    }

    @Test
    fun remoteDigits() {
        remote(letters = false).sized(dp(340), dp(560)).save("widget-remote-numeros.png")
    }

    @Test
    fun remoteLetters() {
        remote(letters = true).sized(dp(340), dp(560)).save("widget-remote-letras.png")
    }
}
