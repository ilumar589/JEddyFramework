/**
 * Save games (DESIGN §21): {@link dev.jeddy.save.SaveWriter} captures meta entries and tagged,
 * versioned chunks; {@link dev.jeddy.save.SaveFile} encodes and reads the {@code .jsave} container
 * (zstd, CRC-32C, a meta block the slot list reads alone); {@link dev.jeddy.save.SaveJob} writes on
 * a virtual thread with an atomic replace; {@link dev.jeddy.save.SaveSlots} and {@link
 * dev.jeddy.save.SaveDirectory} are the quick, auto and named slots in the user's data directory.
 */
package dev.jeddy.save;
