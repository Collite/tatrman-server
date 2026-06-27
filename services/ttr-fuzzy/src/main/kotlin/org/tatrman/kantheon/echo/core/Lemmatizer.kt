package org.tatrman.kantheon.echo.core

/**
 * Maps surface tokens to their lemma (folded via [TextNormalizer]) so inflected
 * Czech forms ("zákazníků", "zákazníkem") collapse onto a stable key ("zakaznik").
 *
 * Implementations must be degradable: a lemmatiser backend being unavailable must
 * not break matching — return an identity map and let the caller fall back to
 * folded-surface matching (Phase 02 Stage A behaviour).
 */
interface Lemmatizer {
    /** @return token → folded lemma. Tokens absent from the result keep their (folded) surface form. */
    suspend fun lemmatize(tokens: Collection<String>): Map<String, String>
}

/** Identity lemmatiser — used when the NLP backend is disabled or unreachable. */
object NoopLemmatizer : Lemmatizer {
    override suspend fun lemmatize(tokens: Collection<String>): Map<String, String> =
        tokens.associateWith { TextNormalizer.fold(it) }
}
