package com.akylas.enforcedoze.ui.amber;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.akylas.enforcedoze.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.shape.ShapeAppearancePathProvider;
import com.google.android.material.shape.Shapeable;

import java.util.Random;

/**
 * Smoked-glass finish drawn in a view's {@link android.view.ViewOverlay}: a fixed-seed noise tile at 3% white and a
 * 1px hairline fading from white 12% at the top-left to nothing at the bottom-right, both inside the view's current
 * shape. It sits above the view and leaves its background and foreground (ripple) alone.
 */
public final class GlassOverlay extends Drawable {
    private static final int NOISE_ALPHA = Math.round(0.03f * 255);
    private static final int HAIRLINE_START = Color.argb(Math.round(0.12f * 255), 255, 255, 255);
    private static final int HAIRLINE_END = Color.argb(0, 255, 255, 255);

    private final Shapeable shapeable;
    private final Paint noise = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ShapeAppearancePathProvider pathProvider = new ShapeAppearancePathProvider();
    private final Path path = new Path();
    private final RectF pathBounds = new RectF();
    private ShapeAppearanceModel pathModel;

    GlassOverlay(@NonNull Shapeable shapeable, float hairlinePx) {
        this.shapeable = shapeable;
        noise.setColor(Color.WHITE);
        noise.setAlpha(NOISE_ALPHA);
        noise.setShader(new BitmapShader(NoiseTile.BITMAP, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        hairline.setStyle(Paint.Style.STROKE);
        // Twice the hairline, clipped to the shape: only the inner half shows.
        hairline.setStrokeWidth(2f * hairlinePx);
    }

    /** Adds a glass overlay to {@code card} and keeps its bounds on the card's size through every layout. */
    @NonNull
    public static GlassOverlay attach(@NonNull MaterialCardView card) {
        GlassOverlay glass = new GlassOverlay(card, card.getResources().getDimension(R.dimen.hairline));
        glass.setBounds(0, 0, card.getWidth(), card.getHeight());
        card.getOverlay().add(glass);
        card.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                glass.setBounds(0, 0, right - left, bottom - top));
        return glass;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        hairline.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                HAIRLINE_START, HAIRLINE_END, Shader.TileMode.CLAMP));
        pathModel = null;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        ShapeAppearanceModel model = shapeable.getShapeAppearanceModel();
        if (model != pathModel) {
            pathBounds.set(getBounds());
            path.reset();
            pathProvider.calculatePath(model, 1f, pathBounds, path);
            pathModel = model;
        }
        canvas.drawPath(path, noise);
        int save = canvas.save();
        canvas.clipPath(path);
        canvas.drawPath(path, hairline);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        noise.setAlpha(NOISE_ALPHA * alpha / 255);
        hairline.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        noise.setColorFilter(colorFilter);
        hairline.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** One shared 128px ALPHA_8 tile of seeded uniform noise, built on first use. */
    private static final class NoiseTile {
        static final int SIZE = 128;
        static final long SEED = 0x416D626572L;
        static final Bitmap BITMAP = create();

        private static Bitmap create() {
            Random random = new Random(SEED);
            int[] colors = new int[SIZE * SIZE];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = random.nextInt(256) << 24 | 0xFFFFFF;
            }
            Bitmap tile = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8);
            tile.setPixels(colors, 0, SIZE, 0, 0, SIZE, SIZE);
            return tile;
        }
    }
}
