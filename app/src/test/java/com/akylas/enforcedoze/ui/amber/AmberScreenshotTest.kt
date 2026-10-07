package com.akylas.enforcedoze.ui.amber

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.akylas.enforcedoze.AboutAppActivity
import com.akylas.enforcedoze.R
import com.akylas.enforcedoze.SettingsActivity
import com.akylas.enforcedoze.TestAppState
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM goldens of the Amber Night look (Roborazzi on Robolectric native graphics).
 * `./gradlew :app:recordRoborazziDebug` writes them to app/src/test/snapshots and
 * `:app:verifyRoborazziDebug` fails when a rendering drifts from them.
 * Shadows, the launch glow and the glass hairline don't render reliably here; the emulator pass owns those.
 * Kotlin, not Java: javac must load every captureRoboImage overload, including the Compose ones this app lacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmberScreenshotTest {

    @After
    fun resetFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun themed(overlay: Int? = null): Context =
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme).apply {
            overlay?.let { theme.applyStyle(it, true) }
        }

    private fun Context.px(dimen: Int): Int = resources.getDimensionPixelSize(dimen)

    private fun text(context: Context, appearanceAttr: Int, value: String): TextView {
        val appearance = TypedValue()
        context.theme.resolveAttribute(appearanceAttr, appearance, true)
        return TextView(context).apply {
            TextViewCompat.setTextAppearance(this, appearance.resourceId)
            text = value
        }
    }

    /** Built through the (Context, AttributeSet) path a layout uses, so a style= attribute applies. */
    private fun button(context: Context, label: String, style: String? = null): AmberButton {
        val attrs = Robolectric.buildAttributeSet()
        style?.let { attrs.setStyleAttribute(it) }
        return AmberButton(context, attrs.build()).apply { text = label }
    }

    private fun card(context: Context): AmberCardView = AmberCardView(context).apply {
        val body = LinearLayout(context)
        body.orientation = LinearLayout.VERTICAL
        val pad = context.px(R.dimen.space_3)
        body.setPadding(pad, pad, pad, pad)
        body.addView(text(context, com.google.android.material.R.attr.textAppearanceHeadlineSmall, "Doze enforced"))
        body.addView(
            text(
                context,
                com.google.android.material.R.attr.textAppearanceBodyMedium,
                "Deep sleep starts right after the screen turns off.",
            ),
        )
        addView(body)
    }

    private fun toggle(context: Context, label: String, checked: Boolean): MaterialSwitch =
        MaterialSwitch(context).apply {
            text = label
            isChecked = checked
        }

    /** A screen-like column on the theme's ink background. */
    private fun column(context: Context, vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val background = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        setBackgroundColor(background.getColor(0, 0))
        background.recycle()
        val pad = context.px(R.dimen.space_3)
        setPadding(pad, pad, pad, pad)
        for (child in children) {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = context.px(R.dimen.space_2)
            addView(child, params)
        }
    }

    private fun components(context: Context): View {
        val chip = Chip(context)
        chip.text = "Motion sensing off"
        Amber.treat(chip)
        val fixture = column(
            context,
            card(context),
            button(context, "Enable Doze"),
            button(context, "Whitelist apps", "@style/Widget.Amber.Button.Outlined"),
            chip,
            toggle(context, "Disable motion sensing", true),
            toggle(context, "Turn off Wi-Fi in Doze", false),
        )
        chip.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        return fixture
    }

    private fun accentFixture(overlay: Int): View {
        val context = themed(overlay)
        return column(context, card(context), button(context, "Enable Doze"))
    }

    /** Hosts the fixture in a window so it is measured and drawn like a screen. */
    private fun capture(fixture: View) {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        host.setContentView(
            fixture,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        shadowOf(Looper.getMainLooper()).idle()
        fixture.captureRoboImage()
    }

    @Test
    fun components_fontScale1() = capture(components(themed()))

    @Test
    fun components_fontScale2() {
        RuntimeEnvironment.setFontScale(2f)
        capture(components(themed()))
    }

    @Test
    fun accent_morning() = capture(accentFixture(R.style.ThemeOverlay_Amber_Accent_Morning))

    @Test
    fun accent_day() = capture(accentFixture(R.style.ThemeOverlay_Amber_Accent_Day))

    @Test
    fun accent_evening() = capture(accentFixture(R.style.ThemeOverlay_Amber_Accent_Evening))

    @Test
    fun accent_night() = capture(accentFixture(R.style.ThemeOverlay_Amber_Accent_Night))

    /** Non-root mode and a seeded app context, as the Settings behaviour tests host the screen. */
    private fun captureActivity(type: Class<out Activity>, prepare: (Activity) -> Unit = {}) {
        val app = RuntimeEnvironment.getApplication()
        TestAppState.reset()
        TestAppState.selectNonRootMode(app)
        TestAppState.setAppContext(app)
        val controller = Robolectric.buildActivity(type).setup()
        prepare(controller.get())
        shadowOf(Looper.getMainLooper()).idle()
        controller.get().window.decorView.captureRoboImage()
        controller.pause().stop().destroy()
        TestAppState.reset()
    }

    @Test
    fun aboutAppActivity() = captureActivity(AboutAppActivity::class.java) { about ->
        // Pinned so a release version bump doesn't redraw the golden.
        about.findViewById<TextView>(R.id.textVersion).text = "1.0.0 (build 1)"
    }

    @Test
    fun settingsActivity() = captureActivity(SettingsActivity::class.java)
}
