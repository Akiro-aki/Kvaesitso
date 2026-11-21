package de.mm20.launcher2.search

import com.aallam.similarity.JaroWinkler
import de.mm20.launcher2.search.fuzzy.FzfMatcher

/**
 * How well a search result matches a query, between 0 and 1. Higher is better. Any score of at
 * least [MatchThreshold] is a match.
 */
@JvmInline
value class ResultScore(val score: Float) : Comparable<ResultScore> {

    override fun compareTo(other: ResultScore): Int {
        return score.compareTo(other.score)
    }

    companion object {
        /**
         * Scores [query] against the best of [primaryFields] and [secondaryFields]. Queries and
         * fields are expected to be normalized (see [StringNormalizer]) by the caller.
         *
         * A field matches if it contains the query's characters in order (an fzf-style
         * subsequence match, e.g. "ytm" in "yt music"), or failing that, if it is close enough to
         * the query to be a typo of it (Jaro-Winkler similarity, e.g. "settimgs" for "settings").
         *
         * Every match lands in one of three non-overlapping bands, so that the kind of evidence
         * decides the order before the similarity within a band does:
         *
         * - `[0.95, 1]` Strong literal match: the characters are all there, tightly clustered or at
         *   word boundaries. An exact match scores 1.
         * - `[0.90, 0.95)` Weak literal match: the characters are all there, but scattered.
         * - `[0.80, 0.90)` Typo match: some typed character is not in the field at all, so this is
         *   a guess at a correction.
         *
         * All bands sit above [MatchThreshold], where the previous substring-or-similar scorer put
         * its matches too, so the balance against usage weights in ranking is unchanged.
         */
        fun from(
            query: String,
            primaryFields: Iterable<String> = emptyList(),
            secondaryFields: Iterable<String> = emptyList(),
        ): ResultScore {
            // An empty query is a prefix of everything.
            if (query.isEmpty()) return ResultScore(1f)

            val maxScore = FzfMatcher.maxScoreFor(query)
            val bestPrimary = primaryFields.maxOfOrNull { scoreField(query, it, maxScore) } ?: 0f
            val bestSecondary = secondaryFields.maxOfOrNull {
                scoreField(query, it, maxScore) * SecondaryFieldFactor
            } ?: 0f
            return ResultScore(maxOf(bestPrimary, bestSecondary))
        }

        private fun scoreField(query: String, term: String, maxScore: Int): Float {
            val literal = literalScore(query, term, maxScore)
            if (literal > 0f) {
                return if (literal >= StrongLiteralThreshold) {
                    lerp(
                        StrongLiteralBand,
                        1f,
                        (literal - StrongLiteralThreshold) / (1f - StrongLiteralThreshold)
                    )
                } else {
                    lerp(WeakLiteralBand, StrongLiteralBand, literal / StrongLiteralThreshold)
                }
            }

            // With only one or two characters, there is nothing to base a correction on.
            if (query.length < MinTypoQueryLength) return 0f

            val similarity = typoSimilarity(query, term)
            if (similarity < MatchThreshold) return 0f
            // Similarity is only 1 for identical strings, which the literal path has already
            // caught, so this stays below WeakLiteralBand.
            return lerp(
                TypoBand,
                WeakLiteralBand,
                (similarity - MatchThreshold) / (1f - MatchThreshold)
            )
        }

        /**
         * The normalized fzf score of [query] in [term]. If the query has several words and does
         * not match as a whole, each word is matched on its own, so that word order does not
         * matter ("music yt" finds "yt music"). Such a match is worth a bit less than an in-order
         * one.
         */
        private fun literalScore(query: String, term: String, maxScore: Int): Float {
            val whole = FzfMatcher.normalizedScore(query, term, maxScore)
            if (whole > 0f) return whole

            val words = query.split(Whitespace).filter { it.isNotEmpty() }
            if (words.size < 2) return 0f
            var sum = 0f
            for (word in words) {
                val score = FzfMatcher.normalizedScore(word, term)
                if (score <= 0f) return 0f
                sum += score
            }
            return sum / words.size * UnorderedWordsFactor
        }

        /**
         * How likely [query] is a misspelling of [term]. Besides the whole term, the query is
         * compared to each word of the term ("yuotube" for "youtube music") and to the start of
         * the term, as if the rest has not been typed yet ("clac" for "calculator").
         *
         * The partial comparisons use plain Jaro similarity: Jaro-Winkler's bonus for a shared
         * prefix would reward exactly the case where the user is typing a different word that
         * starts the same ("calc" against "cale…" of "calendar"). They also have to clear a higher
         * bar, since short strings are similar by chance more often.
         */
        private fun typoSimilarity(query: String, term: String): Float {
            var best = JaroWinkler().similarity(query, term).toFloat()
            if (query.length < MinPartialTypoQueryLength) return best

            fun considerPartial(candidate: String) {
                if (candidate.length < query.length - 1 || candidate == term) return
                val similarity = jaro(query, candidate)
                if (similarity >= PartialTypoThreshold && similarity > best) best = similarity
            }

            if (term.length > query.length) {
                considerPartial(term.substring(0, query.length))
                considerPartial(term.substring(0, query.length + 1))
            }
            val words = term.split(Whitespace)
            if (words.size > 1) {
                for (word in words) considerPartial(word)
            }
            return best
        }

        /**
         * Jaro similarity of [a] and [b], between 0 and 1.
         */
        private fun jaro(a: String, b: String): Float {
            if (a == b) return 1f
            if (a.isEmpty() || b.isEmpty()) return 0f
            val window = maxOf(maxOf(a.length, b.length) / 2 - 1, 0)
            val aMatched = BooleanArray(a.length)
            val bMatched = BooleanArray(b.length)
            var matches = 0
            for (i in a.indices) {
                val from = maxOf(0, i - window)
                val to = minOf(b.length - 1, i + window)
                for (j in from..to) {
                    if (!bMatched[j] && a[i] == b[j]) {
                        aMatched[i] = true
                        bMatched[j] = true
                        matches++
                        break
                    }
                }
            }
            if (matches == 0) return 0f
            var transpositions = 0
            var j = 0
            for (i in a.indices) {
                if (!aMatched[i]) continue
                while (!bMatched[j]) j++
                if (a[i] != b[j]) transpositions++
                j++
            }
            val m = matches.toFloat()
            return (m / a.length + m / b.length + (m - transpositions / 2f) / m) / 3f
        }

        private fun lerp(start: Float, end: Float, fraction: Float): Float {
            return start + (end - start) * fraction.coerceIn(0f, 1f)
        }

        /**
         * The lowest score that counts as a match.
         */
        internal const val MatchThreshold = 0.8f

        /**
         * Normalized fzf score from which a literal match is considered strong.
         */
        private const val StrongLiteralThreshold = 0.5f

        private const val StrongLiteralBand = 0.95f
        private const val WeakLiteralBand = 0.9f
        private const val TypoBand = MatchThreshold

        private const val MinTypoQueryLength = 3

        /**
         * Minimum query length to compare against parts of a term, see [typoSimilarity].
         */
        private const val MinPartialTypoQueryLength = 4

        /**
         * Minimum similarity for a typo match against part of a term, see [typoSimilarity].
         */
        private const val PartialTypoThreshold = 0.85f

        /**
         * How much a match of the query's words in a different order is worth relative to an
         * in-order match.
         */
        private const val UnorderedWordsFactor = 0.9f

        private val Whitespace = Regex("\\s+")

        /**
         * How much a match on a secondary field is worth relative to the same match on a primary
         * field.
         */
        private const val SecondaryFieldFactor = 0.8f

        val Zero = ResultScore(0f)

        /**
         * No score has been computed yet. Results that carry this are scored lazily when they are
         * ranked, since not every repository scores its own results.
         */
        val Unspecified = ResultScore(Float.NaN)
    }
}

inline val ResultScore.isUnspecified: Boolean
    get() = score.isNaN()
