package dev.jeddy.samples.iso;

import dev.jeddy.components.Component;
import dev.jeddy.components.Default;
import dev.jeddy.components.Doc;

/**
 * A skeleton's identity (DESIGN §22, the demo's first component): on the root entity of its
 * character. The dynamic state is in {@link Mind}, {@link Sight} and {@link Flash}.
 *
 * @param model 0 warrior, 1 stalker, 2 adept (the enemy scenes in that order)
 * @param agent its crowd agent, or -1 while dead
 * @param character its {@link Animator} slot
 * @param index its index in {@link Combat}'s and {@link Enemies}' tables (equal to its row)
 */
@Component(doc = "A skeleton: model, crowd agent, animator slot")
public record Monster(
        @Doc("0 warrior, 1 stalker, 2 adept") int model,
        @Default("-1") int agent,
        @Default("-1") int character,
        int index) {}
