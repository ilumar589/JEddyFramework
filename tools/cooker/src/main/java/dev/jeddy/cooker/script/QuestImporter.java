package dev.jeddy.cooker.script;

import dev.jeddy.asset.format.QuestData;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.script.compile.QuestCompiler;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Cooks a {@code .quest} file (DESIGN §24, D123) to {@code .jquest} through the quest compiler;
 * errors fail the source with their line and column.
 *
 * <p>Thread ownership: cooker workers.
 */
public final class QuestImporter implements Importer {
    @Override
    public String name() {
        return "quest";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return path.toLowerCase(Locale.ROOT).endsWith(".quest");
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        String source = new String(ctx.bytes(), StandardCharsets.UTF_8);
        QuestCompiler.Result r = QuestCompiler.check(source);
        if (!r.ok()) {
            throw new IllegalArgumentException(DialogueImporter.describe(ctx.path(), r.errors()));
        }
        QuestData q = r.data();
        ctx.output(ctx.outputBase() + ".jquest", q.toBytes());
        ctx.stat("quests", q.questCount());
        ctx.stat("stages", q.stageCount());
    }
}
