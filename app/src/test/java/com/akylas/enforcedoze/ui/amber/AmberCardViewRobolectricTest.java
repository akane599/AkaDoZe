package com.akylas.enforcedoze.ui.amber;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import com.akylas.enforcedoze.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.shape.CornerTreatment;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.shape.ShapeAppearancePathProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The Amber kit views get squircle corners at their themed sizes, and cards carry the glass overlay. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AmberCardViewRobolectricTest {
    private static final RectF LARGE = new RectF(0f, 0f, 1000f, 1000f);

    private static Context appTheme() {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
    }

    /** Inflates a fully-qualified tag the way a layout does: the inflater's reflective (Context, AttributeSet) path. */
    private static <T extends View> T inflate(Class<T> type, AttributeSet attrs) throws Exception {
        Context context = appTheme();
        return type.cast(LayoutInflater.from(context).createView(type.getName(), null, attrs));
    }

    private static float dimen(int id) {
        return appTheme().getResources().getDimension(id);
    }

    private static void assertSquircle(ShapeAppearanceModel model, float sizePx) {
        CornerTreatment[] corners = {model.getTopLeftCorner(), model.getTopRightCorner(),
                model.getBottomRightCorner(), model.getBottomLeftCorner()};
        for (CornerTreatment corner : corners) {
            assertTrue(corner.getClass().getName(), corner instanceof SquircleCornerTreatment);
        }
        assertEquals(sizePx, model.getTopLeftCornerSize().getCornerSize(LARGE), 0.01f);
        assertEquals(sizePx, model.getBottomRightCornerSize().getCornerSize(LARGE), 0.01f);
    }

    private static GlassOverlay glassOf(View view) {
        Object group = ReflectionHelpers.getField(view.getOverlay(), "mOverlayViewGroup");
        List<Drawable> drawables = ReflectionHelpers.getField(group, "mDrawables");
        List<GlassOverlay> glass = new ArrayList<>();
        // The overlay creates its drawable list on the first add.
        for (Drawable drawable : drawables == null ? new ArrayList<Drawable>() : drawables) {
            if (drawable instanceof GlassOverlay) {
                glass.add((GlassOverlay) drawable);
            }
        }
        assertEquals(1, glass.size());
        return glass.get(0);
    }

    @Test
    public void inflatedCardHasSquircleCornersAtCornerCardAndGlassOverlay() throws Exception {
        AmberCardView card = inflate(AmberCardView.class, Robolectric.buildAttributeSet().build());
        assertSquircle(card.getShapeAppearanceModel(), dimen(R.dimen.corner_card));
        assertNotNull(glassOf(card));
    }

    @Test
    public void perLayoutCornerRadiusStillApplies() throws Exception {
        AttributeSet attrs = Robolectric.buildAttributeSet()
                .addAttribute(com.google.android.material.R.attr.cardCornerRadius, "12dp").build();
        AmberCardView card = inflate(AmberCardView.class, attrs);
        assertSquircle(card.getShapeAppearanceModel(), dimen(R.dimen.corner_card) / 2f);
    }

    @Test
    public void glassFollowsLayoutBoundsAndDrawsInsideTheShape() {
        AmberCardView card = new AmberCardView(appTheme());
        card.layout(10, 20, 310, 220);
        GlassOverlay glass = glassOf(card);
        assertEquals(new Rect(0, 0, 300, 200), glass.getBounds());

        glass.setAlpha(128);
        glass.setColorFilter(null);
        glass.draw(new Canvas(Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)));
        assertEquals(PixelFormat.TRANSLUCENT, glass.getOpacity());
    }

    @Test
    public void buttonGetsSquircleCornersAtCornerButton() throws Exception {
        AmberButton button = inflate(AmberButton.class, Robolectric.buildAttributeSet().build());
        assertSquircle(button.getShapeAppearanceModel(), dimen(R.dimen.corner_button));
        assertSquircle(new AmberButton(appTheme()).getShapeAppearanceModel(), dimen(R.dimen.corner_button));
    }

    @Test
    public void treatChipKeepsItsThemedSizeWithSquircleCorners() {
        Chip chip = new Chip(appTheme());
        Amber.treat(chip);
        assertSquircle(chip.getShapeAppearanceModel(), dimen(R.dimen.corner_inner));
    }

    @Test
    public void treatCodeBuiltCardAddsSquircleAndGlass() {
        MaterialCardView card = new MaterialCardView(appTheme());
        Amber.treat(card);
        assertSquircle(card.getShapeAppearanceModel(), dimen(R.dimen.corner_card));
        assertNotNull(glassOf(card));
    }

    private static void assertPathSpans(float radiusPx, RectF bounds) {
        Path path = new Path();
        new ShapeAppearancePathProvider().calculatePath(SquircleShapes.model(radiusPx), 1f, bounds, path);
        RectF drawn = new RectF();
        path.computeBounds(drawn, true);
        assertEquals(bounds.left, drawn.left, 0.01f);
        assertEquals(bounds.top, drawn.top, 0.01f);
        assertEquals(bounds.right, drawn.right, 0.01f);
        assertEquals(bounds.bottom, drawn.bottom, 0.01f);
    }

    @Test
    public void squirclePathOfAButtonStaysWithinAndSpansItsBounds() {
        assertPathSpans(16f, new RectF(0f, 0f, 200f, 40f));
    }

    @Test
    public void zeroRadiusModelTracesTheFullRectangle() {
        assertPathSpans(0f, new RectF(0f, 0f, 100f, 50f));
    }
}
