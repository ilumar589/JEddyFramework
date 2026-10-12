/**
 * The component processor: generates stores, views, loops, the registry and the schema from
 * {@code @Component} records and {@code @Behaviour} classes at compile time. See §22 of
 * docs/DESIGN.md.
 */
module dev.jeddy.components.processor {
    requires java.compiler;

    provides javax.annotation.processing.Processor with
            dev.jeddy.components.processor.ComponentProcessor;
}
