package dev.jeddy.cooker;

import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The result of a cook (DESIGN §10.3): one item per source with its status, time, outputs, warnings
 * and importer statistics (texture PSNR/SSIM, mesh LODs and cache efficiency, ...), written as
 * {@code report.json} and a self-contained {@code report.html}.
 *
 * <p>Thread ownership: built by the cooker's driving thread; immutable afterwards.
 */
public final class CookReport {
    /** What happened to a source. */
    public enum Status {
        /** Cooked by its importer. */
        COOKED,
        /** Outputs restored from the cache. */
        CACHED,
        /** The importer failed; the previous outputs were left in place. */
        FAILED,
        /** The source disappeared and its outputs were deleted. */
        REMOVED
    }

    /** One source. */
    public record Item(
            String source,
            String importer,
            Status status,
            long nanos,
            List<String> outputs,
            long outputBytes,
            List<String> warnings,
            Map<String, Object> stats,
            String error) {}

    private final List<Item> items;
    private final long nanos;

    CookReport(List<Item> items, long nanos) {
        this.items = List.copyOf(items);
        this.nanos = nanos;
    }

    /** Returns the items, sorted by source path. */
    public List<Item> items() {
        return items;
    }

    /** Returns the item for {@code source}, or {@code null}. */
    public Item item(String source) {
        for (Item i : items) {
            if (i.source().equals(source)) {
                return i;
            }
        }
        return null;
    }

    /** Returns the wall time of the cook. */
    public long nanos() {
        return nanos;
    }

    /** Returns how many items have {@code status}. */
    public int count(Status status) {
        int n = 0;
        for (Item i : items) {
            if (i.status() == status) {
                n++;
            }
        }
        return n;
    }

    /** Returns {@code true} if any source failed. */
    public boolean failed() {
        return count(Status.FAILED) > 0;
    }

    /** Returns a one-line summary. */
    public String summary() {
        return String.format(
                Locale.ROOT,
                "%d cooked, %d cached, %d failed, %d removed in %.2f s",
                count(Status.COOKED),
                count(Status.CACHED),
                count(Status.FAILED),
                count(Status.REMOVED),
                nanos / 1e9);
    }

    /** Returns the report as JSON. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seconds", nanos / 1e9);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            counts.put(s.name().toLowerCase(Locale.ROOT), count(s));
        }
        root.put("counts", counts);
        List<Object> list = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", i.source());
            m.put("importer", i.importer());
            m.put("status", i.status().name().toLowerCase(Locale.ROOT));
            m.put("milliseconds", i.nanos() / 1e6);
            m.put("outputs", i.outputs());
            m.put("outputBytes", i.outputBytes());
            m.put("warnings", i.warnings());
            m.put("stats", i.stats());
            if (i.error() != null) {
                m.put("error", i.error());
            }
            list.add(m);
        }
        root.put("items", list);
        return Json.write(root);
    }

    /** Returns the report as a standalone HTML page. */
    public String toHtml() {
        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Cook report</title><style>")
                .append(
                        ":root{--bg:#fff;--fg:#1d2330;--muted:#5d6678;--line:#e3e6ec;--ok:#1b7f4b;--warn:#a15c00;--err:#b42318}")
                .append(
                        "@media (prefers-color-scheme:"
                            + " dark){:root{--bg:#14171d;--fg:#e6e9ef;--muted:#9aa3b5;--line:#2a2f3a;--ok:#4cc38a;--warn:#f0a542;--err:#ff6b5e}}")
                .append(
                        "body{background:var(--bg);color:var(--fg);font:14px/1.45"
                                + " system-ui,sans-serif;margin:24px}")
                .append(
                        "h1{font-size:20px;margin:0 0 4px}p.sum{color:var(--muted);margin:0 0"
                                + " 16px}")
                .append(
                        "table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid"
                            + " var(--line);padding:6px 8px;text-align:left;vertical-align:top}")
                .append(
                        "th{color:var(--muted);font-weight:600}td.num{text-align:right;font-variant-numeric:tabular-nums}")
                .append(
                        ".cooked{color:var(--ok)}.cached{color:var(--muted)}.failed{color:var(--err);font-weight:600}.removed{color:var(--warn)}")
                .append(
                        ".warn{color:var(--warn)}.err{color:var(--err)}code{font-size:12px}details"
                                + " summary{cursor:pointer;color:var(--muted)}")
                .append("</style></head><body><h1>Cook report</h1><p class=\"sum\">")
                .append(escape(summary()))
                .append(
                        "</p><table><thead><tr><th>Source</th><th>Importer</th><th>Status</th><th>ms</th>")
                .append("<th>Output bytes</th><th>Details</th></tr></thead><tbody>");
        for (Item i : items) {
            String status = i.status().name().toLowerCase(Locale.ROOT);
            sb.append("<tr><td><code>")
                    .append(escape(i.source()))
                    .append("</code></td><td>")
                    .append(escape(i.importer()))
                    .append("</td><td class=\"")
                    .append(status)
                    .append("\">")
                    .append(status)
                    .append("</td><td class=\"num\">")
                    .append(String.format(Locale.ROOT, "%.1f", i.nanos() / 1e6))
                    .append("</td><td class=\"num\">")
                    .append(i.outputBytes())
                    .append("</td><td>");
            if (i.error() != null) {
                sb.append("<div class=\"err\">").append(escape(i.error())).append("</div>");
            }
            for (String w : i.warnings()) {
                sb.append("<div class=\"warn\">").append(escape(w)).append("</div>");
            }
            if (!i.stats().isEmpty() || !i.outputs().isEmpty()) {
                sb.append("<details><summary>")
                        .append(i.outputs().size())
                        .append(" outputs, statistics</summary><pre><code>")
                        .append(
                                escape(
                                        Json.write(
                                                Map.of(
                                                        "outputs",
                                                        i.outputs(),
                                                        "stats",
                                                        i.stats()))))
                        .append("</code></pre></details>");
            }
            sb.append("</td></tr>");
        }
        sb.append("</tbody></table></body></html>\n");
        return sb.toString();
    }

    /** Writes {@code report.json} and {@code report.html} into {@code dir}. */
    public void write(Path dir) throws IOException {
        CookCache.writeAtomically(
                dir.resolve("report.json"), toJson().getBytes(StandardCharsets.UTF_8));
        CookCache.writeAtomically(
                dir.resolve("report.html"), toHtml().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
