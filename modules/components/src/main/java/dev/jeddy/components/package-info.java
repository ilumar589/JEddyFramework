/**
 * Components and behaviours (DESIGN §22): the annotations a game puts on its {@code record}s and
 * behaviour classes, and the runtime the generated code is built on — {@link
 * dev.jeddy.components.Store} (a sparse set with one primitive array per field), the view base and
 * the snapshot streams. {@code tools/component-processor} generates the rest at compile time;
 * nothing here reflects.
 */
package dev.jeddy.components;
