/**
 * The isometric demo (DESIGN §13.3) and, since M22, the first game of {@code components}: the
 * skeletons and the fireballs are records in generated stores and their logic is static behaviours;
 * {@code CryptGame} is the generated registry (DESIGN §22).
 */
@Components(name = "CryptGame", capacity = 512)
package dev.jeddy.samples.iso;

import dev.jeddy.components.Components;
