package dev.jeddy.asset;

/**
 * The state byte of an asset slot (DESIGN §10.2). Transitions: {@code UNLOADED → QUEUED → LOADING →
 * (UPLOADING →) READY}, or {@code FAILED} from any step. Reloads keep an asset {@code READY} while
 * the new version loads.
 *
 * <p>Thread ownership: constants.
 */
public final class AssetState {
    private AssetState() {}

    /** Not requested, or released. */
    public static final int UNLOADED = 0;

    /** Requested and waiting for a load slot (staging memory and an IO thread). */
    public static final int QUEUED = 1;

    /** Being read and decoded. */
    public static final int LOADING = 2;

    /** Decoded; waiting for the main thread to upload it (GPU resources). */
    public static final int UPLOADING = 3;

    /** Loaded; {@code get} returns the value. */
    public static final int READY = 4;

    /** Loading failed; {@code error} says why. A reload tries again. */
    public static final int FAILED = 5;

    /** Returns the name of a state, for logs. */
    public static String name(int state) {
        return switch (state) {
            case UNLOADED -> "UNLOADED";
            case QUEUED -> "QUEUED";
            case LOADING -> "LOADING";
            case UPLOADING -> "UPLOADING";
            case READY -> "READY";
            case FAILED -> "FAILED";
            default -> "UNKNOWN(" + state + ")";
        };
    }
}
