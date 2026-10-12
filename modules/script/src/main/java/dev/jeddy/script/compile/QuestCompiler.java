package dev.jeddy.script.compile;

import dev.jeddy.asset.format.QuestData;
import dev.jeddy.script.compile.Tokens.Kind;
import dev.jeddy.script.compile.Tokens.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a {@code .quest} file to a {@link QuestData} (DESIGN §24, D120): any number of quests,
 * each with a title and numbered stages whose text is the journal entry:
 *
 * <pre>
 * quest ID
 *   title: TEXT
 *   stage N: TEXT                 -- an open stage
 *   stage N done: TEXT            -- reaching it completes the quest
 *   stage N failed: TEXT
 * </pre>
 *
 * Stage numbers rise within a quest; stage 0 is "not started" and cannot be declared. Errors are
 * collected with line and column like the dialogue compiler's.
 *
 * <p>Thread ownership: any single thread per call.
 */
public final class QuestCompiler {
    private static final Pattern TITLE = Pattern.compile("^\\s*title\\s*:(.*)$");
    private static final Pattern STAGE =
            Pattern.compile("^\\s*stage\\s+(\\d+)(?:\\s+(done|failed))?\\s*:(.*)$");

    /** The outcome: the data and every error found. */
    public record Result(QuestData data, List<ScriptError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private QuestCompiler() {}

    public static QuestData compile(String source, String file) throws ScriptException {
        Result r = check(source);
        if (!r.ok()) {
            throw new ScriptException(file, r.errors());
        }
        return r.data();
    }

    public static Result check(String source) {
        QuestData out = new QuestData();
        List<ScriptError> errors = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int quest = -1;
        int lastStage = 0;
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String raw = lines[i];
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) {
                continue;
            }
            try {
                Matcher title = TITLE.matcher(raw);
                Matcher stage = STAGE.matcher(raw);
                if (title.matches()) {
                    if (quest < 0) {
                        throw new Tokens.Bad(lineNumber, 1, "'title:' belongs after 'quest ID'");
                    }
                    out.setQuestTitle(quest, title.group(1).strip());
                } else if (stage.matches()) {
                    if (quest < 0) {
                        throw new Tokens.Bad(lineNumber, 1, "a stage belongs after 'quest ID'");
                    }
                    int number;
                    try {
                        number = Integer.parseInt(stage.group(1));
                    } catch (NumberFormatException e) {
                        throw new Tokens.Bad(lineNumber, 1, "stage number too large");
                    }
                    if (number <= 0) {
                        throw new Tokens.Bad(
                                lineNumber, 1, "stage numbers start at 1 (0 is 'not started')");
                    }
                    if (number <= lastStage) {
                        throw new Tokens.Bad(
                                lineNumber,
                                1,
                                "stage "
                                        + number
                                        + " comes after stage "
                                        + lastStage
                                        + "; stages rise");
                    }
                    String text = stage.group(3).strip();
                    if (text.isEmpty()) {
                        throw new Tokens.Bad(lineNumber, 1, "the stage has no journal text");
                    }
                    int kind =
                            stage.group(2) == null
                                    ? QuestData.STAGE_OPEN
                                    : stage.group(2).equals("done")
                                            ? QuestData.STAGE_DONE
                                            : QuestData.STAGE_FAILED;
                    out.addStage(quest, number, kind, text, lineNumber);
                    lastStage = number;
                } else {
                    Tokens t = new Tokens(raw, lineNumber);
                    Token first = t.peek();
                    if (first.kind() != Kind.WORD || !first.text().equals("quest")) {
                        throw t.error(
                                "expected 'quest ID', 'title:' or 'stage N:', found " + first);
                    }
                    t.next();
                    Token id = t.expect(Kind.WORD, "the quest's id");
                    t.expectEnd();
                    if (ids.contains(id.text())) {
                        throw new Tokens.Bad(
                                lineNumber,
                                id.column(),
                                "quest '" + id.text() + "' is declared twice");
                    }
                    if (quest >= 0 && out.questStageCount(quest) == 0) {
                        errors.add(
                                new ScriptError(
                                        out.questSourceLine(quest),
                                        1,
                                        "quest '" + ids.get(quest) + "' has no stages"));
                    }
                    quest = out.addQuest(id.text(), "", lineNumber);
                    ids.add(id.text());
                    lastStage = 0;
                }
            } catch (Tokens.Bad bad) {
                errors.add(bad.toError());
            }
        }
        if (quest < 0) {
            errors.add(new ScriptError(1, 1, "the file has no quests"));
        } else if (out.questStageCount(quest) == 0) {
            errors.add(
                    new ScriptError(
                            out.questSourceLine(quest),
                            1,
                            "quest '" + ids.get(quest) + "' has no stages"));
        }
        errors.sort(
                (a, b) ->
                        a.line() != b.line()
                                ? Integer.compare(a.line(), b.line())
                                : Integer.compare(a.column(), b.column()));
        return new Result(out, errors);
    }
}
