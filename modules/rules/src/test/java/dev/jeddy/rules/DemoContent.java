package dev.jeddy.rules;

import dev.jeddy.asset.format.RulesData;
import dev.jeddy.asset.format.RulesSchema;
import dev.jeddy.cooker.rules.RulesImporter;
import dev.jeddy.core.json.Json;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The demo's rules content ({@code samples/iso-demo/assets/rules}) built through the cooker's
 * importer, so the tests assert the numbers the shipped files give. Built once per JVM.
 */
final class DemoContent {
    private static Content content;

    private DemoContent() {}

    static synchronized Content get() {
        if (content == null) {
            Path demo = Path.of("../../samples/iso-demo/assets/rules");
            Assumptions.assumeTrue(
                    Files.isDirectory(demo), "the demo's rules are not next to this build");
            content = new Content(build(demo));
        }
        return content;
    }

    /** Builds a {@link RulesData} from the JSON files of one content directory. */
    static RulesData build(Path dir) {
        try {
            Map<String, List<Map<String, Object>>> sources = new HashMap<>();
            for (RulesSchema.Kind k : RulesSchema.KINDS) {
                Path f = dir.resolve(k.name() + ".json");
                if (k.hasId() && Files.isRegularFile(f)) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Object o : Json.asArray(Json.parse(Files.readAllBytes(f)), k.name())) {
                        rows.add(Json.asObject(o, k.name()));
                    }
                    sources.put(k.name(), rows);
                }
            }
            return RulesImporter.build(sources);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
