// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * RV-38 — what kind of thing a declared term points at, carried per row in the compiled lexicon.
 *
 * The **kind is never stored**; it derives from this (RV-38), which is why the artifact ships the
 * class and not a kind string. Null on a member candidate: a data value points at nothing but
 * itself.
 *
 * T5 makes it a *query filter*: the lookup rung asks "what operators could this word be?" without
 * having to know which target refs exist, which is the difference between a scoped deterministic
 * round and a scan. Carried through the loader and the cascade for the same reason T2 carried
 * `matchMethod` before T4 dispatched on it — a value has to survive the path before anything can
 * be trusted to read it.
 */
enum class TargetClass {
    /** An `er.`/`db.`/`md.` model object, at any attribute depth. */
    MODEL_OBJECT,

    /** A member of a model object — a value, not a structure. */
    MEMBER,

    /** An `op:` skill trigger (RV-35 — the skill *body* never enters the matcher). */
    OPERATOR,

    /** A `ground:` trigger slice — chrono · money · geo (RV-42). */
    GROUNDING_TRIGGER,
}
