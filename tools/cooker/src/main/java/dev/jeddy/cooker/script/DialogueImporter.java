package dev.jeddy.cooker.script;

import dev.jeddy.asset.format.DialogueData;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.script.compile.DialogueCompiler;
import dev.jeddy.script.compile.ScriptError;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Cooks a {@code .dlg} conversation (DESIGN §24, D123) to {@code .jdlg} through the script
 * compiler; every error is reported as {@code file:line:column: message} and fails the source. The
 * dialogue's id must match the file's name, so that a {@code Talker} component naming {@code
 * dialogue/ghost} finds the dialogue {@code ghost}.
 *
 * <p>Thread ownership: cooker workers.
 */
public final class DialogueImporter implements Importer {
    @Override
    public String name() {
        return "dialogue";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        return path.toLowerCase(Locale.ROOT).endsWith(".dlg");
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        String source = new String(ctx.bytes(), StandardCharsets.UTF_8);
        DialogueCompiler.Result r = DialogueCompiler.check(source);
        if (!r.ok()) {
            throw new IllegalArgumentException(describe(ctx.path(), r.errors()));
        }
        DialogueData d = r.data();
        String base = ctx.outputBase();
        String fileName = base.substring(base.lastIndexOf('/') + 1);
        if (!fileName.equals(d.id().toLowerCase(Locale.ROOT))) {
            ctx.warn(
                    ctx.path()
                            + ": the dialogue is named '"
                            + d.id()
                            + "' but the file '"
                            + fileName
                            + "'; the game finds it by the file");
        }
        ctx.output(base + ".jdlg", d.toBytes());
        ctx.stat("nodes", d.nodeCount());
        ctx.stat("lines", d.lineCount());
        ctx.stat("choices", d.choiceCount());
        ctx.stat("code", d.codeLength());
    }

    /** Every error as {@code file:line:column: message}, one per line. */
    static String describe(String file, List<ScriptError> errors) {
        StringBuilder b = new StringBuilder();
        for (ScriptError e : errors) {
            if (!b.isEmpty()) {
                b.append('\n');
            }
            b.append(file).append(':').append(e);
        }
        return b.toString();
    }
}
