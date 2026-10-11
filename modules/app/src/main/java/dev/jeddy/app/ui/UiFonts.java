package dev.jeddy.app.ui;

import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.format.FontData;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.render.material.GpuTexture;
import dev.jeddy.ui.FontLoader;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.UiDrawList;

import java.util.Arrays;

/**
 * Loads cooked fonts into a {@link Ui} (TASKS M13-05): a font is a {@code .jfont} (metrics, through
 * {@link FontLoader}) and a {@code .ktx2} atlas (a texture asset that becomes a layer of the
 * renderer's texture arrays). {@link #load} returns a font index at once, backed by the context's
 * metrics-only font until both halves are ready; {@link #update} swaps the real font in. The
 * default font every cook writes is {@code engine/fonts/inter}.
 *
 * <p>Thread ownership: the game thread (the asset manager's owner).
 */
public final class UiFonts {
    private static final Logger LOG = Log.logger("ui");

    private final Ui ui;
    private final AssetType<FontData> fonts;
    private final AssetType<GpuTexture> textures;
    private int[] fontHandle = new int[4];
    private int[] atlasHandle = new int[4];
    private int[] fontIndex = new int[4];
    private String[] names = new String[4];
    private int pending;

    /** Creates the loader; registers the {@code "font"} asset type with {@code assets}. */
    public UiFonts(Ui ui, AssetManager assets, AssetType<GpuTexture> textures) {
        this.ui = ui;
        this.fonts = assets.register("font", new FontLoader());
        this.textures = textures;
    }

    /** Returns the {@code .jfont} asset type. */
    public AssetType<FontData> fonts() {
        return fonts;
    }

    /**
     * Starts loading {@code base + ".jfont"} and {@code base + ".ktx2"} and returns the font's
     * index in the context (usable at once; text draws once the atlas is uploaded).
     */
    public int load(String base) {
        int index = ui.addFont(ui.font(0), UiDrawList.NO_TEXTURE);
        if (pending == fontHandle.length) {
            fontHandle = Arrays.copyOf(fontHandle, pending * 2);
            atlasHandle = Arrays.copyOf(atlasHandle, pending * 2);
            fontIndex = Arrays.copyOf(fontIndex, pending * 2);
            names = Arrays.copyOf(names, pending * 2);
        }
        fontHandle[pending] = fonts.load(base + ".jfont", -10);
        atlasHandle[pending] = textures.load(base + ".ktx2", -10);
        fontIndex[pending] = index;
        names[pending] = base;
        pending++;
        return index;
    }

    /** Installs the fonts whose files finished loading. Call once per frame; allocates nothing. */
    public void update() {
        for (int i = 0; i < pending; i++) {
            int f = fontHandle[i];
            int a = atlasHandle[i];
            boolean failed = fonts.error(f) != null || textures.error(a) != null;
            if (failed) {
                Throwable e = fonts.error(f) != null ? fonts.error(f) : textures.error(a);
                LOG.warn("font " + names[i] + " failed to load: " + e);
            } else if (!fonts.isReady(f) || !textures.isReady(a)) {
                continue;
            } else {
                ui.setFont(fontIndex[i], fonts.get(f), textures.get(a).arrayRef);
            }
            pending--;
            fontHandle[i] = fontHandle[pending];
            atlasHandle[i] = atlasHandle[pending];
            fontIndex[i] = fontIndex[pending];
            names[i] = names[pending];
            i--;
        }
    }

    /** Returns the number of fonts still loading. */
    public int pending() {
        return pending;
    }
}
