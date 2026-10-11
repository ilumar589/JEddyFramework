package dev.jeddy.ui;

import dev.jeddy.asset.AssetLoader;
import dev.jeddy.asset.DecodeContext;
import dev.jeddy.asset.format.FontData;

import java.lang.foreign.MemorySegment;

/**
 * Loads {@code .jfont} files (TASKS M13-05): the metrics half of a cooked font; the atlas is a
 * texture asset loaded next to it. {@link FontData#read} copies everything it keeps.
 *
 * <p>Thread ownership: decoding runs on asset workers; the value is immutable.
 */
public final class FontLoader implements AssetLoader<FontData> {
    /** Creates the loader. */
    public FontLoader() {}

    @Override
    public FontData decode(long id, MemorySegment data, DecodeContext context) throws Exception {
        return FontData.read(data);
    }
}
