package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.U32;
import static dev.jeddy.asset.format.Bytes.U64;
import static dev.jeddy.asset.format.Bytes.align;
import static dev.jeddy.asset.format.Bytes.checkRange;

import dev.jeddy.asset.format.RulesSchema.Field;
import dev.jeddy.asset.format.RulesSchema.Kind;
import dev.jeddy.asset.format.RulesSchema.Type;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cooked rules content (DESIGN §20, D97; {@code docs/formats/jrules.md}): one table per {@link
 * RulesSchema} kind, each a flat {@code int[]} of rows of the kind's stride, plus the pool of list
 * ints, the array of effect operations ({@link RulesSchema#OP_INTS} ints each) and the string
 * table. Every field is read by {@code (kind, row, field)} through the schema's offsets, so the
 * engine resolves the field indices it needs by name once and then reads ints.
 *
 * <p>Built by the cooker through {@link Builder}, written with {@link #toBytes} and read with
 * {@link #read}; immutable afterwards (the editor edits the JSON source, not this).
 *
 * <p>Thread ownership: immutable after construction; any thread.
 */
public final class RulesData {
    /** {@code "JRUL"} in file order. */
    public static final int MAGIC = 0x4C55524A;

    public static final int VERSION = 1;
    private static final int HEADER_SIZE = 64;

    private final int[][] cells; // per kind: rows × stride
    private final int[] rows; // per kind
    private final int[] pool;
    private final int[] ops;
    private final String[] strings;
    private final List<Map<String, Integer>> ids = new ArrayList<>(); // per kind: id → row

    private RulesData(int[][] cells, int[] rows, int[] pool, int[] ops, String[] strings) {
        this.cells = cells;
        this.rows = rows;
        this.pool = pool;
        this.ops = ops;
        this.strings = strings;
        for (Kind k : RulesSchema.KINDS) {
            Map<String, Integer> m = new HashMap<>();
            if (k.hasId()) {
                int idField = k.field("id");
                for (int r = 0; r < rows[k.index()]; r++) {
                    m.put(getString(k, r, idField), r);
                }
            }
            ids.add(m);
        }
    }

    /** Returns the number of rows of {@code kind}. */
    public int rows(Kind kind) {
        return rows[kind.index()];
    }

    /** Returns the row of the definition with {@code id} in {@code kind}, or -1. */
    public int find(Kind kind, String id) {
        Integer r = ids.get(kind.index()).get(id);
        return r == null ? -1 : r;
    }

    /** Returns int field {@code field} of row {@code row} (INT, BOOL, ENUM, FLAGS, DICE, REF). */
    public int getInt(Kind kind, int row, int field) {
        return cells[kind.index()][row * kind.stride() + kind.offset(field)];
    }

    /** Returns a BOOL field. */
    public boolean getBool(Kind kind, int row, int field) {
        return getInt(kind, row, field) != 0;
    }

    /** Returns a STRING field. */
    public String getString(Kind kind, int row, int field) {
        return strings[cells[kind.index()][row * kind.stride() + kind.offset(field)]];
    }

    /** Returns the start of a range field (REF_LIST, INT_LIST, OPS, NESTED). */
    public int start(Kind kind, int row, int field) {
        return cells[kind.index()][row * kind.stride() + kind.offset(field)];
    }

    /** Returns the count of a range field. */
    public int count(Kind kind, int row, int field) {
        return cells[kind.index()][row * kind.stride() + kind.offset(field) + 1];
    }

    /** Returns int {@code i} of the list pool (REF_LIST and INT_LIST ranges index it). */
    public int pool(int i) {
        return pool[i];
    }

    /** Returns the number of effect operations. */
    public int opCount() {
        return ops.length / RulesSchema.OP_INTS;
    }

    /** Returns int {@code k} ({@code RulesSchema.OP_*}) of operation {@code i}. */
    public int op(int i, int k) {
        return ops[i * RulesSchema.OP_INTS + k];
    }

    /** Returns the raw operation array (read-only; {@link RulesSchema#OP_INTS} per op). */
    public int[] ops() {
        return ops;
    }

    /** Returns the number of strings. */
    public int stringCount() {
        return strings.length;
    }

    /** Returns string {@code i}. */
    public String string(int i) {
        return strings[i];
    }

    // ---- Building -----------------------------------------------------------------------------

    /** Assembles a {@link RulesData} table by table (the cooker; tests). */
    public static final class Builder {
        private final int[][] cells = new int[RulesSchema.KINDS.size()][];
        private final int[] rows = new int[RulesSchema.KINDS.size()];
        private int[] pool = new int[64];
        private int poolCount;
        private int[] ops = new int[64 * RulesSchema.OP_INTS];
        private int opCount;
        private final Map<String, Integer> stringIndex = new HashMap<>();
        private String[] strings = new String[64];
        private int stringCount;

        /** Creates an empty builder (every kind has zero rows; the empty string is string 0). */
        public Builder() {
            for (Kind k : RulesSchema.KINDS) {
                cells[k.index()] = new int[k.stride() * 8];
            }
            string("");
        }

        /** Adds a row of {@code kind} with every field at its default; returns its index. */
        public int addRow(Kind kind) {
            int r = rows[kind.index()]++;
            int[] c = cells[kind.index()];
            if ((r + 1) * kind.stride() > c.length) {
                c = Arrays.copyOf(c, Math.max((r + 1) * kind.stride(), c.length * 2));
                cells[kind.index()] = c;
            }
            int base = r * kind.stride();
            for (int f = 0; f < kind.fields().size(); f++) {
                Field field = kind.fields().get(f);
                int o = base + kind.offset(f);
                switch (field.type()) {
                    case STRING -> {
                        c[o] = string(field.defaultString());
                        c[o + 1] = 0;
                    }
                    case REF_LIST, INT_LIST, OPS, NESTED -> {
                        c[o] = 0;
                        c[o + 1] = 0;
                    }
                    default -> c[o] = field.defaultInt();
                }
            }
            return r;
        }

        /** Sets an int-like field. */
        public Builder setInt(Kind kind, int row, int field, int value) {
            cells[kind.index()][row * kind.stride() + kind.offset(field)] = value;
            return this;
        }

        /** Sets a STRING field. */
        public Builder setString(Kind kind, int row, int field, String value) {
            cells[kind.index()][row * kind.stride() + kind.offset(field)] = string(value);
            return this;
        }

        /** Sets a list field to {@code values[0, n)} (appended to the pool). */
        public Builder setList(Kind kind, int row, int field, int[] values, int n) {
            if (poolCount + n > pool.length) {
                pool = Arrays.copyOf(pool, Math.max(poolCount + n, pool.length * 2));
            }
            System.arraycopy(values, 0, pool, poolCount, n);
            int o = row * kind.stride() + kind.offset(field);
            cells[kind.index()][o] = poolCount;
            cells[kind.index()][o + 1] = n;
            poolCount += n;
            return this;
        }

        /**
         * Sets a range field (NESTED) to rows {@code [start, start + count)} of the nested kind.
         */
        public Builder setRange(Kind kind, int row, int field, int start, int count) {
            int o = row * kind.stride() + kind.offset(field);
            cells[kind.index()][o] = start;
            cells[kind.index()][o + 1] = count;
            return this;
        }

        /** Appends an operation and returns its index. */
        public int addOp(
                int op,
                int stat,
                int type,
                int value,
                int dice,
                int rounds,
                int ref,
                int damageType) {
            int i = opCount++;
            if ((i + 1) * RulesSchema.OP_INTS > ops.length) {
                ops = Arrays.copyOf(ops, ops.length * 2);
            }
            int o = i * RulesSchema.OP_INTS;
            ops[o] = op;
            ops[o + 1] = stat;
            ops[o + 2] = type;
            ops[o + 3] = value;
            ops[o + 4] = dice;
            ops[o + 5] = rounds;
            ops[o + 6] = ref;
            ops[o + 7] = damageType;
            return i;
        }

        /**
         * Sets an OPS field to operations {@code [first, first + count)} (added with {@link
         * #addOp}).
         */
        public Builder setOps(Kind kind, int row, int field, int first, int count) {
            return setRange(kind, row, field, first, count);
        }

        /** Returns the number of operations added so far (the next op's index). */
        public int opCount() {
            return opCount;
        }

        /** Returns the number of rows of {@code kind} so far. */
        public int rows(Kind kind) {
            return rows[kind.index()];
        }

        private int string(String s) {
            Integer i = stringIndex.get(s);
            if (i != null) {
                return i;
            }
            if (stringCount == strings.length) {
                strings = Arrays.copyOf(strings, stringCount * 2);
            }
            strings[stringCount] = s;
            stringIndex.put(s, stringCount);
            return stringCount++;
        }

        /** Finishes the table. */
        public RulesData build() {
            int[][] c = new int[cells.length][];
            for (Kind k : RulesSchema.KINDS) {
                c[k.index()] = Arrays.copyOf(cells[k.index()], rows[k.index()] * k.stride());
            }
            return new RulesData(
                    c,
                    rows.clone(),
                    Arrays.copyOf(pool, poolCount),
                    Arrays.copyOf(ops, opCount * RulesSchema.OP_INTS),
                    Arrays.copyOf(strings, stringCount));
        }
    }

    // ---- File ---------------------------------------------------------------------------------

    /**
     * Reads a {@code .jrules}.
     *
     * @throws AssetFormatException on a bad magic, version, count or range
     */
    public static RulesData read(MemorySegment file) throws AssetFormatException {
        checkRange(file, 0, HEADER_SIZE, ".jrules header");
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jrules file");
        }
        int version = file.get(U32, 4);
        if (version != VERSION) {
            throw new AssetFormatException(".jrules version " + version + ", expected " + VERSION);
        }
        int kindCount = file.get(U32, 8);
        if (kindCount != RulesSchema.KINDS.size()) {
            throw new AssetFormatException(
                    ".jrules has " + kindCount + " kinds, the schema " + RulesSchema.KINDS.size());
        }
        int opInts = file.get(U32, 12);
        int poolInts = file.get(U32, 16);
        int stringCount = file.get(U32, 20);
        long tablesAt = file.get(U64, 24);
        long opsAt = file.get(U64, 32);
        long poolAt = file.get(U64, 40);
        long stringsAt = file.get(U64, 48);
        long fileSize = file.get(U64, 56);
        if (fileSize != file.byteSize()) {
            throw new AssetFormatException(".jrules size mismatch");
        }
        int[][] cells = new int[kindCount][];
        int[] rows = new int[kindCount];
        long at = tablesAt;
        for (Kind k : RulesSchema.KINDS) {
            checkRange(file, at, 4, ".jrules table header");
            int n = file.get(U32, at);
            if (n < 0 || n > 1 << 20) {
                throw new AssetFormatException(".jrules " + k.name() + " has " + n + " rows");
            }
            long bytes = 4L * n * k.stride();
            checkRange(file, at + 4, bytes, ".jrules " + k.name());
            int[] c = new int[n * k.stride()];
            MemorySegment.copy(file, ValueLayout.JAVA_INT_UNALIGNED, at + 4, c, 0, c.length);
            cells[k.index()] = c;
            rows[k.index()] = n;
            at = align(at + 4 + bytes, 16);
        }
        checkRange(file, opsAt, 4L * opInts, ".jrules ops");
        int[] ops = new int[opInts];
        MemorySegment.copy(file, ValueLayout.JAVA_INT_UNALIGNED, opsAt, ops, 0, opInts);
        checkRange(file, poolAt, 4L * poolInts, ".jrules pool");
        int[] pool = new int[poolInts];
        MemorySegment.copy(file, ValueLayout.JAVA_INT_UNALIGNED, poolAt, pool, 0, poolInts);
        // Strings: a (offset, length) table then the bytes.
        checkRange(file, stringsAt, 8L * stringCount, ".jrules string table");
        String[] strings = new String[stringCount];
        long bytesAt = stringsAt + 8L * stringCount;
        for (int i = 0; i < stringCount; i++) {
            int off = file.get(U32, stringsAt + 8L * i);
            int len = file.get(U32, stringsAt + 8L * i + 4);
            checkRange(file, bytesAt + off, len, ".jrules string " + i);
            byte[] b = new byte[len];
            MemorySegment.copy(file, ValueLayout.JAVA_BYTE, bytesAt + off, b, 0, len);
            strings[i] = new String(b, StandardCharsets.UTF_8);
        }
        RulesData data = new RulesData(cells, rows, pool, ops, strings);
        data.validate();
        return data;
    }

    /** Checks every string, range and reference against the table sizes. */
    private void validate() throws AssetFormatException {
        for (Kind k : RulesSchema.KINDS) {
            int n = rows[k.index()];
            for (int r = 0; r < n; r++) {
                for (int f = 0; f < k.fields().size(); f++) {
                    Field field = k.fields().get(f);
                    int v = cells[k.index()][r * k.stride() + k.offset(f)];
                    switch (field.type()) {
                        case STRING -> check(v >= 0 && v < strings.length, k, r, field);
                        case REF -> {
                            Kind target = RulesSchema.kind(field.refKind());
                            check(v >= -1 && v < rows[target.index()], k, r, field);
                        }
                        case REF_LIST, INT_LIST -> {
                            int c = count(k, r, f);
                            check(v >= 0 && c >= 0 && v + c <= pool.length, k, r, field);
                            if (field.type() == Type.REF_LIST) {
                                Kind target = RulesSchema.kind(field.refKind());
                                for (int i = 0; i < c; i++) {
                                    check(
                                            pool[v + i] >= 0 && pool[v + i] < rows[target.index()],
                                            k,
                                            r,
                                            field);
                                }
                            }
                        }
                        case OPS -> {
                            int c = count(k, r, f);
                            check(v >= 0 && c >= 0 && v + c <= opCount(), k, r, field);
                        }
                        case NESTED -> {
                            Kind target = RulesSchema.kind(field.refKind());
                            int c = count(k, r, f);
                            check(v >= 0 && c >= 0 && v + c <= rows[target.index()], k, r, field);
                        }
                        case ENUM -> check(v >= 0 && v < field.names().length, k, r, field);
                        default -> {}
                    }
                }
            }
        }
        for (int i = 0; i < opCount(); i++) {
            int o = op(i, RulesSchema.OP_OP);
            if (o < 0 || o >= RulesSchema.OPS.length) {
                throw new AssetFormatException(".jrules op " + i + " has op " + o);
            }
        }
    }

    private static void check(boolean ok, Kind k, int r, Field f) throws AssetFormatException {
        if (!ok) {
            throw new AssetFormatException(
                    ".jrules " + k.name() + "[" + r + "]." + f.name() + " out of range");
        }
    }

    /** Serializes to the file format. */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        // Tables.
        ByteArrayOutputStream tables = new ByteArrayOutputStream();
        for (Kind k : RulesSchema.KINDS) {
            int n = rows[k.index()];
            ByteBuffer b =
                    ByteBuffer.allocate(4 + 4 * n * k.stride()).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(n);
            for (int v : cells[k.index()]) {
                b.putInt(v);
            }
            tables.writeBytes(b.array());
            pad(tables, 16);
        }
        byte[] tableBytes = tables.toByteArray();
        ByteBuffer opBuf = ByteBuffer.allocate(4 * ops.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : ops) {
            opBuf.putInt(v);
        }
        ByteBuffer poolBuf = ByteBuffer.allocate(4 * pool.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : pool) {
            poolBuf.putInt(v);
        }
        ByteArrayOutputStream stringBytes = new ByteArrayOutputStream();
        ByteBuffer stringTable =
                ByteBuffer.allocate(8 * strings.length).order(ByteOrder.LITTLE_ENDIAN);
        for (String s : strings) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            stringTable.putInt(stringBytes.size());
            stringTable.putInt(b.length);
            stringBytes.writeBytes(b);
        }
        long tablesAt = HEADER_SIZE;
        long opsAt = align(tablesAt + tableBytes.length, 16);
        long poolAt = align(opsAt + opBuf.capacity(), 16);
        long stringsAt = align(poolAt + poolBuf.capacity(), 16);
        long fileSize = stringsAt + stringTable.capacity() + stringBytes.size();
        header.putInt(MAGIC)
                .putInt(VERSION)
                .putInt(RulesSchema.KINDS.size())
                .putInt(ops.length)
                .putInt(pool.length)
                .putInt(strings.length)
                .putLong(tablesAt)
                .putLong(opsAt)
                .putLong(poolAt)
                .putLong(stringsAt)
                .putLong(fileSize);
        out.writeBytes(header.array());
        out.writeBytes(tableBytes);
        padTo(out, opsAt);
        out.writeBytes(opBuf.array());
        padTo(out, poolAt);
        out.writeBytes(poolBuf.array());
        padTo(out, stringsAt);
        out.writeBytes(stringTable.array());
        out.writeBytes(stringBytes.toByteArray());
        return out.toByteArray();
    }

    private static void pad(ByteArrayOutputStream out, int alignment) {
        while (out.size() % alignment != 0) {
            out.write(0);
        }
    }

    private static void padTo(ByteArrayOutputStream out, long offset) {
        while (out.size() < offset) {
            out.write(0);
        }
    }
}
