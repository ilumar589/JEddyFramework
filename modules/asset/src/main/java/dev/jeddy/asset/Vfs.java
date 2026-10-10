package dev.jeddy.asset;

import dev.jeddy.asset.pak.Pak;
import dev.jeddy.compress.ZstdDecompressor;
import dev.jeddy.core.collections.LongIntMap;
import dev.jeddy.core.id.StrId;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The virtual file system (TASKS M4-04, DESIGN §10.2): loose cooked directories (development, hot
 * reload) and {@code .jpak} archives, mounted in order. A later mount overrides earlier ones, so a
 * patch pak or the dev cooker's output directory shadows the shipped data.
 *
 * <p>Lookups go through an immutable index (a {@code long → int} hash of every visible asset id)
 * that is rebuilt and republished when mounts change. Reads from paks decompress straight from the
 * mapping into the caller's memory and allocate nothing; loose files are read with a {@link
 * FileChannel} (development only).
 *
 * <p>Thread ownership: lookups and reads are thread-safe and lock-free. {@code mount*}, {@link
 * #unmount}, {@link #rescan} and {@link #close} are serialized internally; {@link #unmount} waits
 * for reads in flight on that mount before unmapping it.
 */
public final class Vfs implements AutoCloseable {
    private static final int ENTRY_BITS = 24;
    private static final int ENTRY_MASK = (1 << ENTRY_BITS) - 1;
    private static final int MAX_VISIBLE_MOUNTS = 127;

    /** A mounted source. Readers count themselves in, so {@link #unmount} can wait for them. */
    private abstract static sealed class Mount permits PakMount, DirectoryMount {
        final int id;
        final AtomicInteger readers = new AtomicInteger();
        final AtomicInteger pins = new AtomicInteger();
        final AtomicBoolean unmapped = new AtomicBoolean();
        volatile boolean closed;

        Mount(int id) {
            this.id = id;
        }

        abstract int entryCount();

        abstract long entryId(int entry);
    }

    private static final class PakMount extends Mount {
        final Pak pak;

        PakMount(int id, Pak pak) {
            super(id);
            this.pak = pak;
        }

        @Override
        int entryCount() {
            return pak.size();
        }

        @Override
        long entryId(int entry) {
            return pak.id(entry);
        }
    }

    /** A loose directory; its listing is replaced by {@link #rescan}. */
    private static final class DirectoryMount extends Mount {
        final Path root;
        volatile Listing listing;

        DirectoryMount(int id, Path root, Listing listing) {
            super(id);
            this.root = root;
            this.listing = listing;
        }

        @Override
        int entryCount() {
            return listing.ids.length;
        }

        @Override
        long entryId(int entry) {
            return listing.ids[entry];
        }
    }

    /** Files of a loose directory, sorted by id. */
    private record Listing(long[] ids, String[] paths) {
        int find(long id) {
            int i = Arrays.binarySearch(ids, id);
            return i >= 0 ? i : -1;
        }
    }

    /** What lookups see: visible asset id → (index into {@code mounts} << 24 | entry). */
    private record Index(LongIntMap map, Mount[] mounts) {}

    private final List<Mount> mounts = new ArrayList<>();
    private volatile Index index = new Index(new LongIntMap(16, -1), new Mount[0]);
    private int nextMountId = 1;

    /** Creates an empty VFS. */
    public Vfs() {}

    /**
     * Mounts a {@code .jpak} on top of the current mounts.
     *
     * @return the mount id, for {@link #unmount}
     * @throws IOException if the pak cannot be opened or is invalid
     */
    public synchronized int mountPak(Path file) throws IOException {
        Pak pak = Pak.open(file);
        PakMount m = new PakMount(nextMountId++, pak);
        mounts.add(m);
        try {
            publish();
        } catch (RuntimeException e) {
            mounts.remove(m);
            pak.close();
            throw e;
        }
        return m.id;
    }

    /**
     * Where a game's cooked content is: {@code -Djeddy.assets.dir} if set, else {@code paks/} in
     * {@code -Djeddy.app.dir} when that exists (the packaged launchers point it at the image, and
     * the {@code dev.jeddy.game} Gradle plugin's {@code run} task at {@code build/}), else {@code
     * fallback}.
     *
     * <p>Thread ownership: any thread (reads system properties).
     */
    public static Path contentDirectory(Path fallback) {
        String explicit = System.getProperty("jeddy.assets.dir");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String app = System.getProperty("jeddy.app.dir");
        if (app != null && !app.isBlank() && Files.isDirectory(Path.of(app, "paks"))) {
            return Path.of(app, "paks");
        }
        return fallback;
    }

    /**
     * Mounts a content directory: every {@code .jpak} in it in name order (a later pak overrides
     * earlier ones), or, if it holds none, the directory itself as a loose cooked tree.
     *
     * @return the mount ids, in mount order
     * @throws IOException if the directory cannot be listed or a pak cannot be opened
     */
    public synchronized int[] mountContent(Path dir) throws IOException {
        List<Path> paks;
        try (Stream<Path> files = Files.list(dir)) {
            paks =
                    files.filter(p -> p.getFileName().toString().endsWith(".jpak"))
                            .sorted()
                            .toList();
        }
        if (paks.isEmpty()) {
            return new int[] {mountDirectory(dir)};
        }
        int[] ids = new int[paks.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = mountPak(paks.get(i));
        }
        return ids;
    }

    /**
     * Mounts a loose cooked directory on top of the current mounts. Files whose name starts with
     * {@code .} or ends with {@code .tmp} (writes in progress) are ignored.
     *
     * @return the mount id, for {@link #unmount} and {@link #rescan}
     * @throws IOException if the directory cannot be listed, or two files share an id
     */
    public synchronized int mountDirectory(Path root) throws IOException {
        Path r = root.toAbsolutePath().normalize();
        DirectoryMount m = new DirectoryMount(nextMountId++, r, scan(r));
        mounts.add(m);
        try {
            publish();
        } catch (RuntimeException e) {
            mounts.remove(m);
            throw e;
        }
        return m.id;
    }

    /**
     * Lists a directory mount again (files added, removed or resized by the cooker). The asset
     * reload watcher calls it before reloading changed assets.
     */
    public synchronized void rescan(int mountId) throws IOException {
        if (!(find(mountId) instanceof DirectoryMount d)) {
            throw new IllegalArgumentException("mount " + mountId + " is not a directory mount");
        }
        d.listing = scan(d.root);
        publish();
    }

    /** Returns the root of a directory mount. */
    public synchronized Path directory(int mountId) {
        if (!(find(mountId) instanceof DirectoryMount d)) {
            throw new IllegalArgumentException("mount " + mountId + " is not a directory mount");
        }
        return d.root;
    }

    /**
     * Removes a mount and, for paks, unmaps the file once reads in flight on it have finished and
     * every {@link Pin} on it is closed (the last pin to close unmaps it).
     */
    public synchronized void unmount(int mountId) {
        Mount m = find(mountId);
        if (m == null) {
            throw new IllegalArgumentException("no mount " + mountId);
        }
        m.closed = true; // readers that count themselves in from now on back off
        mounts.remove(m);
        publish();
        awaitReaders(m);
        if (m.pins.get() == 0) {
            unmap(m);
        }
    }

    private static void unmap(Mount m) {
        if (m instanceof PakMount p && m.unmapped.compareAndSet(false, true)) {
            p.pak.close();
        }
    }

    /**
     * Stored bytes of a pak entry kept mapped for as long as the pin is open: zero-copy access for
     * consumers that keep reading the data, such as streamed audio (DESIGN §12.3). Unmounting the
     * pak removes it from lookups at once, but the file stays mapped until the last pin closes.
     *
     * <p>Thread ownership: any thread; close once.
     */
    public static final class Pin implements AutoCloseable {
        private final Mount mount;
        private final MemorySegment data;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Pin(Mount mount, MemorySegment data) {
            this.mount = mount;
            this.data = data;
        }

        /** Returns the entry's bytes (valid until {@link #close}). */
        public MemorySegment data() {
            return data;
        }

        /** Releases the pin. */
        @Override
        public void close() {
            if (open.compareAndSet(true, false)
                    && mount.pins.decrementAndGet() == 0
                    && mount.closed) {
                unmap(mount);
            }
        }
    }

    /**
     * Pins the visible copy of {@code id} if it is stored uncompressed in a pak (so its bytes can
     * be used in place); returns {@code null} for loose files and compressed entries, which must be
     * read with {@link #read}.
     */
    public Pin pin(long id) {
        while (true) {
            Index ix = index;
            int loc = ix.map.get(id);
            if (loc < 0) {
                return null;
            }
            Mount m = ix.mounts[loc >>> ENTRY_BITS];
            m.readers.incrementAndGet();
            try {
                if (m.closed) {
                    continue;
                }
                if (m instanceof PakMount p) {
                    int entry = loc & ENTRY_MASK;
                    if (p.pak.codec(entry) == dev.jeddy.asset.pak.PakFormat.CODEC_STORED) {
                        m.pins.incrementAndGet();
                        return new Pin(m, p.pak.stored(entry));
                    }
                }
                return null;
            } finally {
                m.readers.decrementAndGet();
            }
        }
    }

    /** Returns the number of mounts. */
    public synchronized int mountCount() {
        return mounts.size();
    }

    /** Returns the number of distinct visible assets. */
    public int size() {
        return index.map.size();
    }

    /** Returns {@code true} if an asset with this id is visible. Allocates nothing. */
    public boolean exists(long id) {
        return index.map.get(id) >= 0;
    }

    /** Returns {@code true} if the asset at {@code path} is visible. */
    public boolean exists(String path) {
        return exists(AssetPaths.id(path));
    }

    /**
     * Returns the decompressed size of an asset, or -1 if it is not visible. Loose files are
     * checked on disk each time (this allocates; development only).
     */
    public long size(long id) {
        Index ix = index;
        int loc = ix.map.get(id);
        if (loc < 0) {
            return -1;
        }
        Mount m = ix.mounts[loc >>> ENTRY_BITS];
        if (m instanceof PakMount p) {
            return p.pak.rawSize(loc & ENTRY_MASK);
        }
        DirectoryMount d = (DirectoryMount) m;
        Listing l = d.listing;
        int i = l.find(id);
        if (i < 0) {
            return -1;
        }
        try {
            return Files.size(d.root.resolve(l.paths[i])); // the cooker may have rewritten it
        } catch (IOException e) {
            return -1;
        }
    }

    /** Returns {@code true} if the visible copy of {@code id} comes from a loose directory. */
    public boolean isLoose(long id) {
        Index ix = index;
        int loc = ix.map.get(id);
        return loc >= 0 && ix.mounts[loc >>> ENTRY_BITS] instanceof DirectoryMount;
    }

    /**
     * Reads an asset's decompressed bytes into {@code dst[dstOffset, dstOffset + capacity)}. From a
     * pak this decompresses straight from the mapping and allocates nothing.
     *
     * @param decompressor the calling thread's context
     * @return the number of bytes written
     * @throws NoSuchFileException if the asset is not visible
     * @throws FileChangedException if a loose file no longer fits {@code capacity}
     * @throws IOException if a loose file cannot be read
     */
    public long read(
            long id,
            MemorySegment dst,
            long dstOffset,
            long capacity,
            ZstdDecompressor decompressor)
            throws IOException {
        while (true) {
            Index ix = index;
            int loc = ix.map.get(id);
            if (loc < 0) {
                throw new NoSuchFileException(hex(id) + " is not mounted");
            }
            Mount m = ix.mounts[loc >>> ENTRY_BITS];
            m.readers.incrementAndGet();
            try {
                if (m.closed) {
                    continue; // unmounted meanwhile: retry against the new index
                }
                if (m instanceof PakMount p) {
                    int entry = loc & ENTRY_MASK;
                    if (p.pak.rawSize(entry) > capacity) {
                        throw new IOException(
                                hex(id) + " needs " + p.pak.rawSize(entry) + " bytes");
                    }
                    return p.pak.read(entry, dst, dstOffset, decompressor);
                }
                return readLoose((DirectoryMount) m, id, dst, dstOffset, capacity);
            } finally {
                m.readers.decrementAndGet();
            }
        }
    }

    /**
     * Touches the stored bytes of a pak entry so the page faults happen on the calling (IO) thread
     * rather than on a job worker. Does nothing for loose files. Allocates a slice.
     */
    public void prefetch(long id) {
        while (true) {
            Index ix = index;
            int loc = ix.map.get(id);
            if (loc < 0) {
                return;
            }
            Mount m = ix.mounts[loc >>> ENTRY_BITS];
            m.readers.incrementAndGet();
            try {
                if (m.closed) {
                    continue;
                }
                if (m instanceof PakMount p) {
                    p.pak.stored(loc & ENTRY_MASK).load();
                }
                return;
            } finally {
                m.readers.decrementAndGet();
            }
        }
    }

    /** Returns a readable description of where {@code id} comes from (diagnostics; allocates). */
    public String describe(long id) {
        Index ix = index;
        int loc = ix.map.get(id);
        if (loc < 0) {
            return hex(id) + " (missing)";
        }
        Mount m = ix.mounts[loc >>> ENTRY_BITS];
        if (m instanceof PakMount p) {
            String name = p.pak.name(loc & ENTRY_MASK);
            return (name != null ? name : hex(id)) + " in " + p.pak.path();
        }
        DirectoryMount d = (DirectoryMount) m;
        Listing l = d.listing;
        int i = l.find(id);
        return i < 0 ? hex(id) + " in " + d.root : d.root.resolve(l.paths[i]).toString();
    }

    /** Unmounts everything. */
    @Override
    public synchronized void close() {
        while (!mounts.isEmpty()) {
            unmount(mounts.get(mounts.size() - 1).id);
        }
    }

    // ---------------------------------------------------------------------------------------

    private long readLoose(
            DirectoryMount d, long id, MemorySegment dst, long dstOffset, long capacity)
            throws IOException {
        Listing l = d.listing;
        int i = l.find(id);
        if (i < 0) {
            throw new NoSuchFileException(hex(id) + " in " + d.root);
        }
        try (FileChannel ch =
                FileChannel.open(d.root.resolve(l.paths[i]), StandardOpenOption.READ)) {
            long size = ch.size();
            if (size > capacity) {
                throw new FileChangedException(
                        d.root.resolve(l.paths[i])
                                + " grew to "
                                + size
                                + " bytes (have "
                                + capacity
                                + ")");
            }
            long done = 0;
            long chunk = 1 << 30;
            while (done < size) {
                long n = Math.min(chunk, size - done);
                ByteBuffer b = dst.asSlice(dstOffset + done, n).asByteBuffer();
                while (b.hasRemaining()) {
                    if (ch.read(b, done + b.position()) < 0) {
                        throw new IOException(d.root.resolve(l.paths[i]) + " shrank while reading");
                    }
                }
                done += n;
            }
            return size;
        }
    }

    private Mount find(int mountId) {
        for (Mount m : mounts) {
            if (m.id == mountId) {
                return m;
            }
        }
        return null;
    }

    private static void awaitReaders(Mount m) {
        while (m.readers.get() != 0) {
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /** Rebuilds the index from the mounts, later mounts winning, and publishes it. */
    private void publish() {
        if (mounts.size() > MAX_VISIBLE_MOUNTS) {
            throw new IllegalStateException("more than " + MAX_VISIBLE_MOUNTS + " mounts");
        }
        int total = 0;
        for (Mount m : mounts) {
            int n = m.entryCount();
            if (n > ENTRY_MASK + 1) {
                throw new IllegalStateException("a mount has more than 2^24 entries");
            }
            total += n;
        }
        LongIntMap map = new LongIntMap(total, -1);
        Mount[] order = mounts.toArray(new Mount[0]);
        for (int mi = 0; mi < order.length; mi++) {
            Mount m = order[mi];
            int n = m.entryCount();
            for (int e = 0; e < n; e++) {
                map.put(m.entryId(e), (mi << ENTRY_BITS) | e);
            }
        }
        index = new Index(map, order);
    }

    private static Listing scan(Path root) throws IOException {
        List<String> paths = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .forEach(
                            f -> {
                                String name = f.getFileName().toString();
                                if (!name.startsWith(".") && !name.endsWith(".tmp")) {
                                    paths.add(root.relativize(f).toString().replace('\\', '/'));
                                }
                            });
        }
        int n = paths.size();
        long[] ids = new long[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            ids[i] = StrId.of(AssetPaths.normalize(paths.get(i)));
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(ids[a], ids[b]));
        long[] sortedIds = new long[n];
        String[] sortedPaths = new String[n];
        for (int i = 0; i < n; i++) {
            int k = order[i];
            sortedIds[i] = ids[k];
            sortedPaths[i] = paths.get(k);
            if (i > 0 && sortedIds[i] == sortedIds[i - 1]) {
                throw new IOException(
                        "two files in "
                                + root
                                + " map to the same asset path: "
                                + sortedPaths[i - 1]
                                + " and "
                                + sortedPaths[i]);
            }
        }
        return new Listing(sortedIds, sortedPaths);
    }

    private static String hex(long id) {
        return String.format("0x%016x", id);
    }
}
