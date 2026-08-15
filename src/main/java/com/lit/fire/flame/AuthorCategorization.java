package com.lit.fire.flame;

import java.time.LocalDateTime;

/**
 * Persisted classification record for an author.  One row per author in the
 * {@code author_categories} table — produced by the marketing report pipeline
 * and consumed by the user-lookup endpoint.
 */
public class AuthorCategorization {

    public String        author;
    public String        audienceClassification; // e.g. "Movie Buff"
    public String        influenceTier;          // e.g. "Viral Node"
    public String        postingStyle;           // e.g. "Power Burst Poster"
    public String        dominantTone;           // positive / neutral / negative
    public String        primaryPlatform;        // x / youtube / reddit / instagram
    public double        branchingRatio;
    public int           totalPosts;
    public LocalDateTime lastCategorizedAt;
}
