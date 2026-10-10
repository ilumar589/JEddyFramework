package dev.jeddy.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Generates the OpenGL bindings (DESIGN §9.1, TASKS M3-01) from the Khronos registry {@code
 * gl.xml} and {@code gl.properties}.
 *
 * <p>Output: one class (for example {@code dev.jeddy.gl.ffi.GL46}) with {@code static final}
 * constants and one static method per command. Each method invokes a {@code static final
 * MethodHandle} held in a per-command nested class, linked on first use through {@code
 * GLLinker.link}, so the JIT treats every handle as a constant. Allow-listed commands are linked
 * with {@code Linker.Option.critical(true)}. Commands whose untyped pointer parameters are usually
 * buffer offsets (for example {@code indirect} in {@code glMultiDrawElementsIndirect}) get a second
 * overload taking a {@code long}, so per-frame draws need no {@code MemorySegment}.
 *
 * <p>The Gradle task {@code GenerateGlBindings} calls {@link #generate}; {@link #main} runs the
 * same code by hand: {@code java GlGenerator.java <gl.xml> <gl.properties> <output dir>}.
 *
 * <p>Pure function of its inputs (deterministic output), so the task is cacheable.
 */
public final class GlGenerator {
    private static final Map<String, String> SCALARS = new LinkedHashMap<>();

    static {
        for (String t : new String[] {"GLenum", "GLbitfield", "GLuint", "GLint", "GLsizei"}) {
            SCALARS.put(t, "int");
        }
        SCALARS.put("GLboolean", "boolean");
        for (String t : new String[] {"GLbyte", "GLubyte", "GLchar"}) {
            SCALARS.put(t, "byte");
        }
        SCALARS.put("GLshort", "short");
        SCALARS.put("GLushort", "short");
        SCALARS.put("GLfloat", "float");
        SCALARS.put("GLclampf", "float");
        SCALARS.put("GLdouble", "double");
        SCALARS.put("GLclampd", "double");
        for (String t :
                new String[] {
                    "GLint64", "GLuint64", "GLint64EXT", "GLuint64EXT", "GLintptr", "GLsizeiptr", "GLsync"
                }) {
            SCALARS.put(t, "long");
        }
        SCALARS.put("GLDEBUGPROC", "MemorySegment");
    }

    private GlGenerator() {}

    /** One parsed command. */
    static final class Command {
        final String name;
        final String returnType;
        final String returnC;
        final List<String> paramNames = new ArrayList<>();
        final List<String> paramTypes = new ArrayList<>();
        final List<String> paramC = new ArrayList<>();

        Command(String name, String returnType, String returnC) {
            this.name = name;
            this.returnType = returnType;
            this.returnC = returnC;
        }
    }

    /**
     * Generates the bindings.
     *
     * @param glXml the registry file
     * @param properties generator settings (see {@code bindings/gl/gl.properties})
     * @param outputDir source root to write into (the package directories are created)
     */
    public static void generate(Path glXml, Path properties, Path outputDir) {
        try {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(properties)) {
                p.load(in);
            }
            Document doc = parse(glXml);
            String api = p.getProperty("api", "gl");
            String profile = p.getProperty("profile", "core");
            String version = p.getProperty("version", "4.6");
            String pkg = p.getProperty("package");
            String className = p.getProperty("class");
            Set<String> extensions = list(p.getProperty("extensions", ""));
            Set<String> critical = list(p.getProperty("critical", ""));
            Set<String> offsetParams = list(p.getProperty("offset.params", ""));

            Set<String> enums = new LinkedHashSet<>();
            Set<String> commands = new LinkedHashSet<>();
            selectFeatures(doc, api, profile, version, enums, commands);
            Set<String> coreCommands = new LinkedHashSet<>(commands);
            Map<String, String> commandExtension = new LinkedHashMap<>();
            selectExtensions(doc, api, profile, extensions, enums, commands, commandExtension);
            commandExtension.keySet().removeAll(coreCommands);

            Map<String, String> enumValues = enumValues(doc);
            Map<String, Command> parsed = commands(doc);
            for (String c : critical) {
                if (!commands.contains(c)) {
                    throw new IllegalArgumentException("critical command not selected: " + c);
                }
            }
            String source =
                    emit(
                            pkg,
                            className,
                            version,
                            extensions,
                            enums,
                            enumValues,
                            commands,
                            parsed,
                            critical,
                            offsetParams,
                            commandExtension,
                            p.getProperty("registry.commit", "?"));
            Path dir = outputDir.resolve(pkg.replace('.', '/'));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(className + ".java"), source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("GL binding generation failed: " + e.getMessage(), e);
        }
    }

    private static Set<String> list(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split("[,\\s]+")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static Document parse(Path xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        try (InputStream in = Files.newInputStream(xml)) {
            return b.parse(in);
        }
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static int[] versionOf(String v) {
        String[] parts = v.split("\\.");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }

    private static boolean applies(Element block, String api, String profile) {
        String a = block.getAttribute("api");
        String p = block.getAttribute("profile");
        return (a.isEmpty() || a.equals(api)) && (p.isEmpty() || p.equals(profile));
    }

    private static void selectFeatures(
            Document doc,
            String api,
            String profile,
            String version,
            Set<String> enums,
            Set<String> commands) {
        int[] max = versionOf(version);
        List<Element> features = new ArrayList<>();
        NodeList all = doc.getDocumentElement().getElementsByTagName("feature");
        for (int i = 0; i < all.getLength(); i++) {
            Element f = (Element) all.item(i);
            if (f.getAttribute("api").equals(api)
                    && compare(versionOf(f.getAttribute("number")), max) <= 0) {
                features.add(f);
            }
        }
        features.sort((x, y) -> compare(versionOf(x.getAttribute("number")), versionOf(y.getAttribute("number"))));
        for (Element f : features) {
            for (Element req : children(f, "require")) {
                if (applies(req, api, profile)) {
                    add(req, enums, commands);
                }
            }
            for (Element rem : children(f, "remove")) {
                if (applies(rem, api, profile)) {
                    for (Element e : children(rem, "enum")) {
                        enums.remove(e.getAttribute("name"));
                    }
                    for (Element c : children(rem, "command")) {
                        commands.remove(c.getAttribute("name"));
                    }
                }
            }
        }
    }

    private static void selectExtensions(
            Document doc,
            String api,
            String profile,
            Set<String> wanted,
            Set<String> enums,
            Set<String> commands,
            Map<String, String> commandExtension) {
        Set<String> found = new LinkedHashSet<>();
        NodeList all = doc.getDocumentElement().getElementsByTagName("extension");
        for (int i = 0; i < all.getLength(); i++) {
            Element ext = (Element) all.item(i);
            String name = ext.getAttribute("name");
            if (!wanted.contains(name)) {
                continue;
            }
            found.add(name);
            for (Element req : children(ext, "require")) {
                if (applies(req, api, profile)) {
                    add(req, enums, commands);
                    for (Element c : children(req, "command")) {
                        commandExtension.putIfAbsent(c.getAttribute("name"), name);
                    }
                }
            }
        }
        Set<String> missing = new LinkedHashSet<>(wanted);
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("unknown extensions: " + missing);
        }
    }

    private static void add(Element req, Set<String> enums, Set<String> commands) {
        for (Element e : children(req, "enum")) {
            enums.add(e.getAttribute("name"));
        }
        for (Element c : children(req, "command")) {
            commands.add(c.getAttribute("name"));
        }
    }

    private static Map<String, String> enumValues(Document doc) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList all = doc.getDocumentElement().getElementsByTagName("enum");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (!e.hasAttribute("value")) {
                continue; // <enum name="..."/> references inside <require>
            }
            String api = e.getAttribute("api");
            if (!api.isEmpty() && !api.equals("gl")) {
                continue;
            }
            String type = e.getAttribute("type");
            out.put(e.getAttribute("name"), (type.equals("ull") ? "L:" : "I:") + e.getAttribute("value"));
        }
        return out;
    }

    private static Map<String, Command> commands(Document doc) {
        Map<String, Command> out = new LinkedHashMap<>();
        Element commands = (Element) doc.getDocumentElement().getElementsByTagName("commands").item(0);
        for (Element c : children(commands, "command")) {
            Element proto = children(c, "proto").get(0);
            String name = text(children(proto, "name").get(0));
            String ret = javaType(proto, name, true);
            Command cmd = new Command(name, ret, cText(proto));
            for (Element p : children(c, "param")) {
                String pname = text(children(p, "name").get(0));
                cmd.paramNames.add(safeName(pname));
                cmd.paramTypes.add(javaType(p, name, false));
                cmd.paramC.add(cText(p));
            }
            out.put(name, cmd);
        }
        return out;
    }

    private static String text(Element e) {
        return e.getTextContent().trim();
    }

    private static String cText(Element e) {
        return e.getTextContent().replaceAll("\\s+", " ").trim();
    }

    private static String javaType(Element decl, String command, boolean isReturn) {
        String full = decl.getTextContent();
        String name = text(children(decl, "name").get(0));
        String withoutName = full.substring(0, full.lastIndexOf(name));
        int stars = withoutName.length() - withoutName.replace("*", "").length();
        List<Element> ptypes = children(decl, "ptype");
        String ptype = ptypes.isEmpty() ? "void" : text(ptypes.get(0));
        if (stars > 0) {
            return "MemorySegment";
        }
        if (ptype.equals("void")) {
            if (!isReturn) {
                throw new IllegalArgumentException(command + ": void parameter");
            }
            return "void";
        }
        String t = SCALARS.get(ptype);
        if (t == null) {
            return "?" + ptype; // reported if the command is selected
        }
        return t;
    }

    private static final Set<String> KEYWORDS =
            Set.of(
                    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
                    "class", "const", "continue", "default", "do", "double", "else", "enum",
                    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
                    "import", "instanceof", "int", "interface", "long", "native", "new", "package",
                    "private", "protected", "public", "return", "short", "static", "strictfp",
                    "super", "switch", "synchronized", "this", "throw", "throws", "transient",
                    "try", "void", "volatile", "while", "string");

    private static String safeName(String name) {
        return KEYWORDS.contains(name) ? name + "_" : name;
    }

    private static String layout(String javaType) {
        switch (javaType) {
            case "int":
                return "JAVA_INT";
            case "boolean":
                return "JAVA_BOOLEAN";
            case "byte":
                return "JAVA_BYTE";
            case "short":
                return "JAVA_SHORT";
            case "float":
                return "JAVA_FLOAT";
            case "double":
                return "JAVA_DOUBLE";
            case "long":
                return "JAVA_LONG";
            case "MemorySegment":
                return "ADDRESS";
            default:
                throw new IllegalArgumentException("no layout for " + javaType);
        }
    }

    private static String emit(
            String pkg,
            String className,
            String version,
            Set<String> extensions,
            Set<String> enums,
            Map<String, String> enumValues,
            Set<String> commands,
            Map<String, Command> parsed,
            Set<String> critical,
            Set<String> offsetParams,
            Map<String, String> commandExtension,
            String commit) {
        StringBuilder sb = new StringBuilder(4 << 20);
        sb.append("// Generated by build-logic GlGenerator from Khronos gl.xml (OpenGL-Registry ")
                .append(commit)
                .append("). Do not edit.\n\n");
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import static java.lang.foreign.ValueLayout.*;\n\n");
        sb.append("import java.lang.foreign.FunctionDescriptor;\n");
        sb.append("import java.lang.foreign.MemorySegment;\n");
        sb.append("import java.lang.invoke.MethodHandle;\n\n");
        sb.append("/**\n * OpenGL ")
                .append(version)
                .append(" core profile");
        if (!extensions.isEmpty()) {
            sb.append(" plus ").append(String.join(", ", extensions));
        }
        sb.append(" (DESIGN §9.1).\n *\n")
                .append(" * <p>Pointers are {@link MemorySegment}s; {@code GLsync} is a {@code long} address.\n")
                .append(" * Commands whose untyped pointer is usually a buffer offset also take a {@code long}.\n")
                .append(" * Each command links on first use through {@link GLLinker}; call {@code GLLoader.init}\n")
                .append(" * with a current context first.\n *\n")
                .append(" * <p>Thread ownership: the thread whose GL context is current (the main thread).\n")
                .append(" */\n");
        sb.append("public final class ").append(className).append(" {\n");
        sb.append("    private ").append(className).append("() {}\n\n");

        // Constants, sorted by name for stable output.
        Map<String, String> sortedEnums = new TreeMap<>();
        for (String e : enums) {
            String v = enumValues.get(e);
            if (v == null) {
                throw new IllegalArgumentException("enum without value: " + e);
            }
            sortedEnums.put(e, v);
        }
        for (Map.Entry<String, String> e : sortedEnums.entrySet()) {
            String v = e.getValue();
            boolean isLong = v.startsWith("L:");
            String literal = v.substring(2);
            sb.append("    public static final ")
                    .append(isLong ? "long " : "int ")
                    .append(e.getKey())
                    .append(" = ")
                    .append(constant(literal, isLong))
                    .append(";\n");
        }
        sb.append('\n');

        List<String> errors = new ArrayList<>();
        List<String> sortedCommands = new ArrayList<>(commands);
        sortedCommands.sort(String::compareTo);
        for (String name : sortedCommands) {
            Command c = parsed.get(name);
            if (c == null) {
                errors.add("unknown command " + name);
                continue;
            }
            List<String> types = new ArrayList<>(c.paramTypes);
            types.add(c.returnType);
            boolean bad = false;
            for (String t : types) {
                if (t.startsWith("?")) {
                    errors.add(name + ": unmapped type " + t.substring(1));
                    bad = true;
                }
            }
            if (bad) {
                continue;
            }
            boolean isCritical = critical.contains(name);
            String extension = commandExtension.get(name);
            emitCommand(sb, c, c.paramTypes, name, "", isCritical, extension);
            // Offset overload: untyped const void* parameters with conventional names.
            List<String> offsetTypes = new ArrayList<>(c.paramTypes);
            boolean anyOffset = false;
            for (int i = 0; i < c.paramNames.size(); i++) {
                String cDecl = c.paramC.get(i);
                if (offsetParams.contains(c.paramNames.get(i))
                        && cDecl.startsWith("const void *")
                        && cDecl.indexOf('*') == cDecl.lastIndexOf('*')) {
                    offsetTypes.set(i, "long");
                    anyOffset = true;
                }
            }
            if (anyOffset) {
                emitCommand(sb, c, offsetTypes, name, "$offset", isCritical, extension);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String constant(String literal, boolean isLong) {
        String v = literal.trim();
        if (v.startsWith("0x") || v.startsWith("0X")) {
            String hex = v.substring(2);
            if (isLong) {
                return "0x" + hex.toUpperCase(Locale.ROOT) + "L";
            }
            long value = Long.parseLong(hex, 16);
            if (value > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("int enum out of range: " + literal);
            }
            return "0x" + hex.toUpperCase(Locale.ROOT);
        }
        if (v.matches("-?\\d+")) {
            return isLong ? v + "L" : v;
        }
        // Rare symbolic values such as "~0" or "((GLuint)-1)".
        if (v.equals("~0") || v.replace(" ", "").equals("((GLuint)-1)")) {
            return isLong ? "-1L" : "-1";
        }
        throw new IllegalArgumentException("unsupported enum value " + literal);
    }

    private static void emitCommand(
            StringBuilder sb,
            Command c,
            List<String> types,
            String name,
            String holderSuffix,
            boolean critical,
            String extension) {
        String holder = name + holderSuffix;
        String ret = c.returnType;
        // Javadoc with the C declaration.
        sb.append("    /**\n     * {@code ").append(escape(c.returnC)).append('(');
        for (int i = 0; i < c.paramC.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(escape(c.paramC.get(i)));
        }
        sb.append(")}");
        if (!holderSuffix.isEmpty()) {
            sb.append(" with buffer offsets passed as {@code long}");
        }
        if (critical) {
            sb.append(" (critical)");
        }
        if (extension != null) {
            sb.append(" from {@code ").append(extension).append('}');
        }
        sb.append("\n     */\n");
        sb.append("    public static ").append(ret).append(' ').append(name).append('(');
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types.get(i)).append(' ').append(c.paramNames.get(i));
        }
        sb.append(") {\n        try {\n            ");
        if (!ret.equals("void")) {
            sb.append("return (").append(ret).append(") ");
        }
        sb.append(holder).append(".MH.invokeExact(");
        sb.append(String.join(", ", c.paramNames));
        sb.append(");\n        } catch (Throwable t) {\n");
        sb.append("            throw GLLinker.rethrow(t);\n        }\n    }\n\n");
        // Holder.
        sb.append("    private static final class ").append(holder).append(" {\n");
        sb.append("        static final MethodHandle MH =\n                GLLinker.link(\"")
                .append(name)
                .append("\", ");
        if (ret.equals("void")) {
            sb.append("FunctionDescriptor.ofVoid(");
        } else {
            sb.append("FunctionDescriptor.of(").append(layout(ret));
            if (!types.isEmpty()) {
                sb.append(", ");
            }
        }
        List<String> layouts = new ArrayList<>();
        for (String t : types) {
            layouts.add(layout(t));
        }
        sb.append(String.join(", ", layouts)).append("), ").append(critical);
        if (extension != null) {
            sb.append(", \"").append(extension).append('"');
        }
        sb.append(");\n");
        sb.append("    }\n\n");
    }

    private static String escape(String s) {
        return s.replace("*/", "* /");
    }

    /** Command-line entry point: {@code GlGenerator <gl.xml> <gl.properties> <output dir>}. */
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: GlGenerator <gl.xml> <gl.properties> <output dir>");
            System.exit(2);
        }
        generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println("generated into " + args[2] + " " + Arrays.toString(args));
    }
}
