package dev.jeddy.components.processor;

import dev.jeddy.components.processor.Model.Behaviour;
import dev.jeddy.components.processor.Model.Component;
import dev.jeddy.components.processor.Model.Field;
import dev.jeddy.components.processor.Model.Kind;
import dev.jeddy.components.processor.Model.Param;
import dev.jeddy.components.processor.Model.ParamKind;
import dev.jeddy.components.processor.Model.Phase;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/**
 * The component processor (DESIGN §22, D105–D109): from {@code @Component} records and
 * {@code @Behaviour} classes it generates, per component, a store, a view and a mut in the record's
 * package; one registry per {@code @Components} package; and the schema JSON. It matches the
 * annotations by name, so it depends on nothing but the JDK. Every rejected shape is reported at
 * the element with the reason.
 *
 * <p>Thread ownership: the compiler's processing thread.
 */
public final class ComponentProcessor extends AbstractProcessor {
    static final String PKG = "dev.jeddy.components.";

    private boolean done;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(PKG + "*");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        if (done || env.processingOver()) {
            return false;
        }
        Model model = new Model();
        boolean ok = collect(env, model);
        if (model.components.isEmpty()
                && model.behaviours.isEmpty()
                && model.registryName == null) {
            return false;
        }
        done = true;
        if (!ok) {
            return false; // errors were reported; javac stops after this round
        }
        generate(model);
        return false;
    }

    // ---- Collection -------------------------------------------------------------------------

    private boolean collect(RoundEnvironment env, Model model) {
        boolean ok = true;
        List<TypeElement> records = new ArrayList<>();
        for (Element e : annotated(env, "Component")) {
            if (e.getKind() != ElementKind.RECORD) {
                error(e, "@Component must be on a record");
                ok = false;
                continue;
            }
            records.add((TypeElement) e);
        }
        records.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        for (TypeElement t : records) {
            ok &= collectComponent(t, model);
        }
        List<Element> packages = new ArrayList<>(annotated(env, "Components"));
        if (packages.size() > 1) {
            for (Element p : packages) {
                error(p, "one @Components package per compilation; found " + packages.size());
            }
            ok = false;
        } else if (packages.size() == 1) {
            Element p = packages.get(0);
            if (!(p instanceof PackageElement pe)) {
                error(p, "@Components must be on a package (package-info.java)");
                ok = false;
            } else {
                AnnotationMirror a = annotation(p, "Components");
                model.registryPackage = pe.getQualifiedName().toString();
                model.registryName = string(a, "name", "");
                model.capacity = integer(a, "capacity", 256);
                if (!SourceVersion.isIdentifier(model.registryName)
                        || SourceVersion.isKeyword(model.registryName)) {
                    error(p, "@Components name must be a Java identifier: " + model.registryName);
                    ok = false;
                }
            }
        }
        List<TypeElement> classes = new ArrayList<>();
        for (Element e : annotated(env, "Behaviour")) {
            if (e.getKind() != ElementKind.CLASS) {
                error(e, "@Behaviour must be on a class");
                ok = false;
                continue;
            }
            classes.add((TypeElement) e);
        }
        classes.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        for (TypeElement t : classes) {
            ok &= collectBehaviours(t, model);
        }
        if (!model.behaviours.isEmpty() && model.registryName == null) {
            error(
                    classes.get(0),
                    "behaviours need a registry: put @Components(name = \"...\") on a"
                            + " package-info.java");
            ok = false;
        }
        model.behaviours.sort(
                Comparator.comparingInt((Behaviour b) -> b.order)
                        .thenComparing(b -> b.owner)
                        .thenComparingInt(b -> b.sourceIndex));
        return ok;
    }

    private boolean collectComponent(TypeElement t, Model model) {
        boolean ok = true;
        Component c = new Component();
        c.pkg = packageOf(t);
        c.name = t.getSimpleName().toString();
        c.doc = string(annotation(t, "Component"), "doc", "");
        if (!t.getModifiers().contains(Modifier.PUBLIC)) {
            error(t, "a @Component record must be public (the generated store is)");
            ok = false;
        }
        if (t.getNestingKind().isNested()) {
            error(t, "a @Component record must be top-level (its store is generated beside it)");
            ok = false;
        }
        Component clash = model.component(c.name);
        if (clash != null) {
            error(
                    t,
                    "component names must be unique in a game: "
                            + c.name
                            + " is also "
                            + clash.qualified());
            ok = false;
        }
        for (RecordComponentElement rc : t.getRecordComponents()) {
            Field f = new Field();
            f.name = rc.getSimpleName().toString();
            f.javaType = rc.asType().toString();
            TypeMirror type = rc.asType();
            boolean entityRef = annotation(rc, "EntityRef") != null;
            AnnotationMirror assetRef = annotation(rc, "AssetRef");
            switch (type.getKind()) {
                case INT -> {
                    f.kind = entityRef ? Kind.ENTITY : assetRef != null ? Kind.ASSET : Kind.INT;
                    if (entityRef && assetRef != null) {
                        error(rc, "a field is an @EntityRef or an @AssetRef, not both");
                        ok = false;
                    }
                    if (assetRef != null) {
                        f.assetKind = string(assetRef, "value", "");
                    }
                }
                case LONG -> f.kind = Kind.LONG;
                case FLOAT -> f.kind = Kind.FLOAT;
                case BOOLEAN -> f.kind = Kind.BOOL;
                case DECLARED -> {
                    TypeElement te = (TypeElement) ((DeclaredType) type).asElement();
                    String q = te.getQualifiedName().toString();
                    if (te.getKind() == ElementKind.ENUM) {
                        f.kind = Kind.ENUM;
                        f.javaType = q;
                        for (Element ec : te.getEnclosedElements()) {
                            if (ec.getKind() == ElementKind.ENUM_CONSTANT) {
                                f.enumValues.add(ec.getSimpleName().toString());
                            }
                        }
                        if (f.enumValues.isEmpty()) {
                            error(rc, "an enum field needs at least one constant");
                            ok = false;
                        }
                    } else if (q.equals("dev.jeddy.math.Vec2f")) {
                        f.kind = Kind.VEC2;
                    } else if (q.equals("dev.jeddy.math.Vec3f")) {
                        f.kind = Kind.VEC3;
                    } else {
                        error(
                                rc,
                                "unsupported component field type "
                                        + q
                                        + ": use int, long, float, boolean, an enum, Vec2f or"
                                        + " Vec3f");
                        ok = false;
                        continue;
                    }
                }
                default -> {
                    error(
                            rc,
                            "unsupported component field type "
                                    + type
                                    + ": use int, long, float, boolean, an enum, Vec2f or Vec3f");
                    ok = false;
                    continue;
                }
            }
            if ((entityRef || assetRef != null) && type.getKind() != TypeKind.INT) {
                error(rc, "@EntityRef and @AssetRef go on int fields (handles)");
                ok = false;
            }
            f.defaults = new double[f.kind.columns()];
            AnnotationMirror def = annotation(rc, "Default");
            if (def != null) {
                ok &= parseDefault(rc, f, string(def, "value", ""));
            }
            AnnotationMirror range = annotation(rc, "Range");
            if (range != null) {
                f.min = number(range, "min", Double.NaN);
                f.max = number(range, "max", Double.NaN);
                if (!(f.kind == Kind.INT || f.kind == Kind.LONG || f.kind == Kind.FLOAT)) {
                    error(rc, "@Range goes on a numeric field");
                    ok = false;
                }
            }
            f.angle = annotation(rc, "Angle") != null;
            if (f.angle && f.kind != Kind.FLOAT) {
                error(rc, "@Angle goes on a float field");
                ok = false;
            }
            f.color = annotation(rc, "Color") != null;
            if (f.color && f.kind != Kind.VEC3) {
                error(rc, "@Color goes on a Vec3f field");
                ok = false;
            }
            AnnotationMirror doc = annotation(rc, "Doc");
            if (doc != null) {
                f.doc = string(doc, "value", "");
            }
            c.fields.add(f);
        }
        model.components.add(c);
        return ok;
    }

    private boolean parseDefault(Element at, Field f, String text) {
        try {
            switch (f.kind) {
                case INT, ENTITY, ASSET -> f.defaults[0] = Integer.parseInt(text.trim());
                case LONG -> f.defaults[0] = Long.parseLong(text.trim());
                case FLOAT -> f.defaults[0] = Float.parseFloat(text.trim());
                case BOOL -> {
                    String t = text.trim();
                    if (!t.equals("true") && !t.equals("false")) {
                        throw new NumberFormatException(t);
                    }
                    f.defaults[0] = t.equals("true") ? 1 : 0;
                }
                case ENUM -> {
                    int i = f.enumValues.indexOf(text.trim());
                    if (i < 0) {
                        error(at, "@Default " + text + " is not a constant of " + f.javaType);
                        return false;
                    }
                    f.defaults[0] = i;
                }
                case VEC2, VEC3 -> {
                    String[] parts = text.split(",");
                    if (parts.length != f.defaults.length) {
                        error(
                                at,
                                "@Default of a vector needs "
                                        + f.defaults.length
                                        + " comma-separated numbers");
                        return false;
                    }
                    for (int i = 0; i < parts.length; i++) {
                        f.defaults[i] = Float.parseFloat(parts[i].trim());
                    }
                }
            }
            return true;
        } catch (NumberFormatException e) {
            error(at, "@Default \"" + text + "\" is not a " + f.kind.json);
            return false;
        }
    }

    private boolean collectBehaviours(TypeElement t, Model model) {
        boolean ok = true;
        int order = integer(annotation(t, "Behaviour"), "order", 0);
        int index = 0;
        int found = 0;
        for (Element e : t.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement m = (ExecutableElement) e;
            Phase phase = null;
            AnnotationMirror phaseAnnotation = null;
            int phases = 0;
            for (Phase p : Phase.values()) {
                AnnotationMirror a = annotation(m, phaseAnnotationName(p));
                if (a != null) {
                    phases++;
                    phase = p;
                    phaseAnnotation = a;
                }
            }
            if (phases == 0) {
                continue;
            }
            found++;
            if (phases > 1) {
                error(m, "a behaviour has one phase annotation");
                ok = false;
                continue;
            }
            Behaviour b = new Behaviour();
            b.owner = t.getQualifiedName().toString();
            b.ownerSimple = t.getSimpleName().toString();
            b.method = m.getSimpleName().toString();
            b.phase = phase;
            b.order = order;
            b.sourceIndex = index++;
            b.kindValue = integer(phaseAnnotation, "value", 0);
            if (!m.getModifiers().contains(Modifier.STATIC)
                    || m.getModifiers().contains(Modifier.PRIVATE)) {
                error(m, "a behaviour is a non-private static method");
                ok = false;
            }
            if (m.getReturnType().getKind() != TypeKind.VOID) {
                error(m, "a behaviour returns void");
                ok = false;
            }
            AnnotationMirror parallel = annotation(m, "Parallel");
            if (parallel != null) {
                b.parallel = true;
                b.grain = Math.max(1, integer(parallel, "grain", 64));
                if (phase != Phase.FIXED_UPDATE && phase != Phase.UPDATE) {
                    error(m, "@Parallel applies to @OnFixedUpdate and @OnUpdate behaviours");
                    ok = false;
                }
            }
            AnnotationMirror without = annotation(m, "Without");
            if (without != null) {
                for (TypeMirror tm : types(without, "value")) {
                    String simple = simpleName(tm.toString());
                    Component c = model.component(simple);
                    if (c == null) {
                        error(m, "@Without names a component; " + tm + " is not one");
                        ok = false;
                    } else {
                        b.without.add(c);
                    }
                }
            }
            for (VariableElement v : m.getParameters()) {
                Param p = new Param();
                p.name = v.getSimpleName().toString();
                p.optional = annotation(v, "Optional") != null;
                p.threadSafe = annotation(v, "ThreadSafe") != null;
                TypeMirror type = v.asType();
                String written = type.toString();
                String simple = simpleName(written);
                Component viewOf = null;
                boolean mut = false;
                if (simple.endsWith("View")
                        && model.component(simple.substring(0, simple.length() - 4)) != null) {
                    viewOf = model.component(simple.substring(0, simple.length() - 4));
                } else if (simple.endsWith("Mut")
                        && model.component(simple.substring(0, simple.length() - 3)) != null) {
                    viewOf = model.component(simple.substring(0, simple.length() - 3));
                    mut = true;
                }
                if (viewOf != null && type.getKind() == TypeKind.DECLARED) {
                    // The type exists (a later compilation): it must be the generated one.
                    String q =
                            ((TypeElement) ((DeclaredType) type).asElement())
                                    .getQualifiedName()
                                    .toString();
                    if (!q.equals(mut ? viewOf.qualifiedMut() : viewOf.qualifiedView())) {
                        viewOf = null;
                    }
                }
                if (viewOf != null) {
                    p.kind = mut ? ParamKind.MUT : ParamKind.VIEW;
                    p.component = viewOf;
                } else if (model.registryName != null
                        && simple.equals(model.registryName)
                        && (type.getKind() == TypeKind.ERROR
                                || written.equals(model.registryQualified()))) {
                    // The registry itself: behaviours despawn, spawn and reach stores through it.
                    p.kind = ParamKind.SINGLETON;
                    p.type = model.registryQualified();
                    p.field = "this";
                } else if (type.getKind() == TypeKind.FLOAT) {
                    switch (p.name) {
                        case "dt" -> p.kind = ParamKind.DT;
                        case "alpha" -> p.kind = ParamKind.ALPHA;
                        default -> {
                            error(v, "a float parameter of a behaviour is dt or alpha");
                            ok = false;
                            continue;
                        }
                    }
                } else if (type.getKind() == TypeKind.INT) {
                    switch (p.name) {
                        case "entity" -> p.kind = ParamKind.ENTITY;
                        case "trigger" -> p.kind = ParamKind.TRIGGER;
                        case "kind" -> p.kind = ParamKind.KIND;
                        case "a" -> p.kind = ParamKind.A;
                        case "b" -> p.kind = ParamKind.B;
                        default -> {
                            error(
                                    v,
                                    "an int parameter of a behaviour is entity, trigger, kind, a or"
                                            + " b");
                            ok = false;
                            continue;
                        }
                    }
                } else if (type.getKind() == TypeKind.DECLARED) {
                    p.kind = ParamKind.SINGLETON;
                    p.type =
                            ((TypeElement) ((DeclaredType) type).asElement())
                                    .getQualifiedName()
                                    .toString();
                    p.field = model.singleton(p.type).field;
                } else if (type.getKind() == TypeKind.ERROR) {
                    error(
                            v,
                            "unknown parameter type "
                                    + written
                                    + " (a component view is <Component>View or <Component>Mut of"
                                    + " a @Component record in this compilation)");
                    ok = false;
                    continue;
                } else {
                    error(v, "unsupported behaviour parameter type " + written);
                    ok = false;
                    continue;
                }
                if (p.optional && p.kind != ParamKind.VIEW && p.kind != ParamKind.MUT) {
                    error(v, "@Optional goes on a component view parameter");
                    ok = false;
                }
                ok &= checkPhaseParam(v, b, p);
                b.params.add(p);
            }
            if (b.required().isEmpty()) {
                error(m, "a behaviour needs at least one required (non-optional) component view");
                ok = false;
            }
            model.behaviours.add(b);
        }
        if (found == 0) {
            error(t, "a @Behaviour class needs at least one phase-annotated static method");
            ok = false;
        }
        return ok;
    }

    private boolean checkPhaseParam(VariableElement v, Behaviour b, Param p) {
        boolean allowed =
                switch (p.kind) {
                    case DT -> b.phase == Phase.FIXED_UPDATE || b.phase == Phase.UPDATE;
                    case ALPHA -> b.phase == Phase.UPDATE;
                    case TRIGGER, KIND -> b.phase == Phase.TRIGGER;
                    case A, B -> b.phase == Phase.EVENT;
                    default -> true;
                };
        if (!allowed) {
            error(
                    v,
                    p.name
                            + " is not a parameter of an "
                            + phaseAnnotationName(b.phase)
                            + " behaviour");
            return false;
        }
        if (b.parallel) {
            if (p.kind == ParamKind.SINGLETON && "this".equals(p.field)) {
                error(v, "a @Parallel behaviour may not take the registry (structural changes)");
                return false;
            }
            if (p.kind == ParamKind.SINGLETON && !p.threadSafe) {
                error(
                        v,
                        "a @Parallel behaviour may only take a singleton marked @ThreadSafe at the"
                                + " parameter");
                return false;
            }
        }
        return true;
    }

    private static String phaseAnnotationName(Phase p) {
        return switch (p) {
            case SPAWN -> "OnSpawn";
            case DESPAWN -> "OnDespawn";
            case FIXED_UPDATE -> "OnFixedUpdate";
            case UPDATE -> "OnUpdate";
            case TRIGGER -> "OnTrigger";
            case TURN -> "OnTurn";
            case EVENT -> "OnEvent";
        };
    }

    // ---- Generation -------------------------------------------------------------------------

    private void generate(Model model) {
        Filer filer = processingEnv.getFiler();
        try {
            for (Component c : model.components) {
                write(filer, c.qualifiedStore(), StoreGen.store(c));
                write(filer, c.qualifiedView(), StoreGen.view(c));
                write(filer, c.qualifiedMut(), StoreGen.mut(c));
            }
            if (model.registryName != null) {
                String q =
                        model.registryPackage.isEmpty()
                                ? model.registryName
                                : model.registryPackage + "." + model.registryName;
                write(filer, q, RegistryGen.registry(model));
                try (Writer w =
                        filer.createResource(
                                        StandardLocation.CLASS_OUTPUT,
                                        "",
                                        "META-INF/jeddy/components.json")
                                .openWriter()) {
                    w.write(SchemaGen.json(model));
                }
            }
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "component processor: " + e);
        }
    }

    private static void write(Filer filer, String qualifiedName, String source) throws IOException {
        try (Writer w = filer.createSourceFile(qualifiedName).openWriter()) {
            w.write(source);
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private Set<? extends Element> annotated(RoundEnvironment env, String simple) {
        TypeElement t = processingEnv.getElementUtils().getTypeElement(PKG + simple);
        return t == null ? Set.of() : env.getElementsAnnotatedWith(t);
    }

    private static String packageOf(TypeElement t) {
        Element e = t;
        while (e != null && e.getKind() != ElementKind.PACKAGE) {
            e = e.getEnclosingElement();
        }
        return e == null ? "" : ((PackageElement) e).getQualifiedName().toString();
    }

    private static String simpleName(String written) {
        int generic = written.indexOf('<');
        String s = generic >= 0 ? written.substring(0, generic) : written;
        return s.substring(s.lastIndexOf('.') + 1);
    }

    private static AnnotationMirror annotation(Element e, String simple) {
        for (AnnotationMirror a : e.getAnnotationMirrors()) {
            Element t = a.getAnnotationType().asElement();
            if (t instanceof TypeElement te
                    && te.getQualifiedName().toString().equals(PKG + simple)) {
                return a;
            }
        }
        return null;
    }

    private static Object value(AnnotationMirror a, String name) {
        if (a == null) {
            return null;
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
                a.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(name)) {
                return e.getValue().getValue();
            }
        }
        return null;
    }

    private static String string(AnnotationMirror a, String name, String fallback) {
        Object v = value(a, name);
        return v instanceof String s ? s : fallback;
    }

    private static int integer(AnnotationMirror a, String name, int fallback) {
        Object v = value(a, name);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static double number(AnnotationMirror a, String name, double fallback) {
        Object v = value(a, name);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static List<TypeMirror> types(AnnotationMirror a, String name) {
        List<TypeMirror> out = new ArrayList<>();
        Object v = value(a, name);
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof AnnotationValue av && av.getValue() instanceof TypeMirror tm) {
                    out.add(tm);
                }
            }
        } else if (v instanceof TypeMirror tm) {
            out.add(tm);
        }
        return out;
    }

    private void error(Element at, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, at);
    }
}
