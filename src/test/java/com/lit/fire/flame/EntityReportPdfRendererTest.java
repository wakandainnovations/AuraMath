package com.lit.fire.flame;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EntityReportPdfRenderer} against hand-built report maps shaped exactly like
 * {@code EntityReportController.buildReport} output, so we can validate PDF generation without a
 * live database. Asserts a structurally valid PDF is produced and that partial/degenerate maps
 * are handled gracefully rather than throwing.
 */
public class EntityReportPdfRendererTest {

    private final EntityReportPdfRenderer renderer = new EntityReportPdfRenderer();

    @Test
    public void rendersValidPdfForFullReport() {
        byte[] pdf = renderer.render(fullReport());

        assertNotNull(pdf, "renderer must return bytes");
        assertTrue(pdf.length > 1000, "PDF should be non-trivial in size");
        assertTrue(startsWithPdfHeader(pdf), "output must start with the %PDF magic header");
        assertTrue(containsEof(pdf), "output must contain the EOF marker");
    }

    @Test
    public void isRenderableTrueWhenEntityProfilePresent() {
        assertTrue(renderer.isRenderable(fullReport()));
    }

    @Test
    public void isRenderableFalseForNotFoundOrEmptyReport() {
        Map<String, Object> notFound = new LinkedHashMap<>();
        notFound.put("entityId", "missing");
        notFound.put("message", "No entity found for this id");
        assertFalse(renderer.isRenderable(notFound));
        assertFalse(renderer.isRenderable(null));
    }

    @Test
    public void rendersWithoutThrowingWhenOptionalSectionsAreMissingOrEmpty() {
        // Only the mandatory entityProfile and conversationProfile are present; every list/map
        // section is absent. The renderer must skip them and still emit a valid PDF.
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("generatedAt", "2026-06-07T10:15:00 IST");
        sparse.put("entityProfile", entityProfile());
        sparse.put("conversationProfile", conversationProfile());

        byte[] pdf = renderer.render(sparse);
        assertTrue(startsWithPdfHeader(pdf));
        assertTrue(pdf.length > 500);
    }

    @Test
    public void topAdvocatesRenderReadableHandleNotRawMapDump() throws Exception {
        // Regression: the nested platform_handles structure must render as a readable "@handle",
        // never as a raw Java map dump like "{x={post_count=2.0, profile_url=..., ...}}".
        byte[] pdf = renderer.render(fullReport());
        String text = extractPdfText(pdf);

        assertTrue(text.contains("@superfan1"),
                "advocate author should render as a readable handle derived from profile_url");
        assertFalse(text.contains("post_count="),
                "raw map fields must not leak into the rendered report");
        assertFalse(text.contains("by_platform"),
                "raw map structure must not leak into the rendered report");
    }

    @Test
    public void rendersWhenSentimentBreakdownHasZeroSegments() {
        // A degenerate sentiment split (all neutral) exercises the zero-width-segment guard
        // in the stacked bar without producing a malformed table.
        Map<String, Object> report = fullReport();
        Map<String, Object> sentiment = new LinkedHashMap<>();
        Map<String, Object> tones = new LinkedHashMap<>();
        tones.put("neutral", 12L); // no positive / negative segments
        sentiment.put("toneBreakdown", tones);
        sentiment.put("dominantTone", "neutral");
        sentiment.put("netSentiment", 0.0);
        sentiment.put("sentimentLabel", "Mixed / Neutral");
        report.put("audienceSentiment", sentiment);

        byte[] pdf = renderer.render(report);
        assertTrue(startsWithPdfHeader(pdf));
    }

    // -------------------------------------------------------------------------
    // Fixture builders — mirror the structure produced by EntityReportController
    // -------------------------------------------------------------------------

    private Map<String, Object> fullReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", "2026-06-07T10:15:00 IST");
        report.put("entityProfile", entityProfile());
        report.put("conversationProfile", conversationProfile());
        report.put("topicIntelligence", topicIntelligence());
        report.put("audienceSentiment", audienceSentiment());
        report.put("channelStrategy", channelStrategy());
        report.put("topAdvocates", topAdvocates());
        report.put("marketingRecommendations", recommendations());
        report.put("redFlags", redFlags());
        report.put("opportunityFlags", opportunities());
        return report;
    }

    private Map<String, Object> entityProfile() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entityId", "ent-42");
        p.put("name", "Taylor Swift");
        p.put("type", "celebrity");
        p.put("trackedKeywords", Arrays.asList("taylor swift", "swiftie", "eras tour"));
        p.put("activePlatforms", Arrays.asList("x", "youtube", "reddit"));
        p.put("totalPosts", 18432);
        p.put("audienceSize", 9211L);
        p.put("firstSeen", "2026-05-01T08:00:00 IST");
        p.put("lastSeen", "2026-06-06T22:30:00 IST");
        p.put("observationSpanDays", 36.6);
        p.put("averagePostsPerDay", 503.6);
        p.put("viralityTier", "Viral Topic");
        p.put("viralityTierExplained",
                "Branching ratio 0.84 — near-supercritical. Conversation about this entity self-sustains; "
                        + "content seeded here is highly likely to cascade organically.");
        return p;
    }

    private Map<String, Object> conversationProfile() {
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("branchingRatio", 0.84);
        cp.put("amplificationExplained",
                "Each post about this entity triggers ~0.84 organic follow-up posts on average (Hawkes branching ratio).");
        cp.put("distinctBurstEvents", 7);
        cp.put("peakActivityWindows", Arrays.asList(
                "20:00–21:00 IST (1203 posts)", "21:00–22:00 IST (1102 posts)", "19:00–20:00 IST (988 posts)"));
        cp.put("mostActiveDayOfWeek", "FRIDAY (3201 posts)");
        Map<String, Object> burst = new LinkedHashMap<>();
        burst.put("keyword", "eras tour");
        burst.put("startTime", "2026-05-18T20:05:00 IST");
        burst.put("durationMinutes", 42.5);
        burst.put("postCount", 311);
        burst.put("peakExcitationSpike", 0.732);
        burst.put("readableDescription", "311 posts about 'eras tour' in 42.5 minutes — peak excitation 0.73");
        cp.put("longestBurst", burst);
        return cp;
    }

    private List<Map<String, Object>> topicIntelligence() {
        List<Map<String, Object>> topics = new ArrayList<>();
        topics.add(topic("eras tour", 8201, 5L, "announcement", "positive", 8.4, 0.213,
                "HIGH — keyword consistently triggers burst activity (5 burst(s)). Dominant tone: positive."));
        topics.add(topic("swiftie", 6120, 2L, "fan_reaction", "positive", 7.1, 0.092,
                "MEDIUM — keyword raises activity but rarely produces full bursts. Tone: positive."));
        topics.add(topic("taylor swift", 4111, 0L, "discussion", "neutral", 0.0, 0.031,
                "LOW — mentions are scattered with no strong excitation pattern. Tone: neutral."));
        return topics;
    }

    private Map<String, Object> topic(String kw, int mentions, long bursts, String cat,
                                      String tone, double score, double spike, String profile) {
        Map<String, Object> t = new LinkedHashMap<>();
        Map<String, Object> toneDist = new LinkedHashMap<>();
        toneDist.put(tone, (long) mentions);
        t.put("keyword", kw);
        t.put("totalMentions", mentions);
        t.put("burstsTriggered", bursts);
        t.put("contentCategory", cat);
        t.put("toneBreakdown", toneDist);
        t.put("dominantTone", tone);
        t.put("averageSentimentScore", score);
        t.put("averageExcitationSpike", spike);
        t.put("excitationProfile", profile);
        return t;
    }

    private Map<String, Object> audienceSentiment() {
        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, Object> tones = new LinkedHashMap<>();
        tones.put("positive", 9800L);
        tones.put("neutral", 6100L);
        tones.put("negative", 2532L);
        s.put("toneBreakdown", tones);
        s.put("dominantTone", "positive");
        s.put("netSentiment", 0.39);
        s.put("sentimentLabel", "Predominantly Positive");
        return s;
    }

    private Map<String, Object> channelStrategy() {
        List<Map<String, Object>> channels = new ArrayList<>();
        channels.add(channel("X (Twitter)", 9000L, 0.488));
        channels.add(channel("YouTube", 6000L, 0.326));
        channels.add(channel("Reddit", 3432L, 0.186));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topChannel", "X (Twitter)");
        body.put("headline", "Conversation is led by X (Twitter) — focus campaign spend there first.");
        body.put("channels", channels);
        return body;
    }

    private Map<String, Object> channel(String platform, long posts, double share) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("platform", platform);
        c.put("postCount", posts);
        c.put("share", share);
        return c;
    }

    private List<Map<String, Object>> topAdvocates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("global_user_id", "u-" + i);
            a.put("tribe_label", "Pop Superfans");
            // Real production shape: { primary_platform, by_platform: { "x": { profile_url, ... } } }.
            a.put("platform_handles", nestedPlatformHandles("superfan" + i));
            a.put("peak_activity_times", new LinkedHashMap<>());
            a.put("hawkes_alpha", 0.9 - i * 0.1);
            a.put("post_count", 320L - i * 10);
            a.put("total_engagement", 150000L - i * 1000);
            a.put("moi_score", 0.77);
            out.add(a);
        }
        return out;
    }

    /** Mirrors MarketingEnrichmentEngine.buildPlatformHandlesJson — the nested structure stored in the DB. */
    private Map<String, Object> nestedPlatformHandles(String handle) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("profile_url", "https://twitter.com/" + handle);
        entry.put("sample_post_url", "https://twitter.com/" + handle + "/status/2043283814571467216");
        entry.put("post_count", 2.0);
        entry.put("total_likes", 1.0);
        entry.put("total_comments", 0.0);
        entry.put("avg_engagement_per_post", 0.5);
        Map<String, Object> byPlatform = new LinkedHashMap<>();
        byPlatform.put("x", entry);
        Map<String, Object> handles = new LinkedHashMap<>();
        handles.put("primary_platform", "x");
        handles.put("by_platform", byPlatform);
        return handles;
    }

    private Map<String, Object> recommendations() {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("primaryChannel", "X (Twitter)");
        rec.put("bestTimeToEngage", "20:00–21:00 IST — peak conversation window");
        rec.put("campaignType", "Reactive Amplification Campaign — time a seeding post to coincide with known burst windows.");
        rec.put("amplificationPotential", "HIGH — near-supercritical; a single interaction can trigger a content cascade");
        rec.put("estimatedReachMultiplier", "~0.8x per seeded post (branching ratio 0.84)");
        rec.put("addressableAudience", "9211 distinct authors are already talking about this entity");
        rec.put("contentTriggers", Arrays.asList("eras tour"));
        rec.put("contentStrategy", "Audiences champion 'eras tour' enthusiastically. Create content that celebrates that connection.");
        rec.put("actionableAdvice",
                "1. TIMING: Publish during the 20:00–21:00 IST window. 2. PLATFORM: Start on X (Twitter). "
                        + "3. TONE: Sentiment is enthusiastic — offer exclusives. 4. TRIGGER: Tie your message to 'eras tour'.");
        return rec;
    }

    private List<Map<String, Object>> redFlags() {
        List<Map<String, Object>> flags = new ArrayList<>();
        flags.add(flag("Negative Sentiment Pockets", "MEDIUM",
                "A minority of posts are critical — monitor before large promotional pushes."));
        flags.add(flag("Thin Data Window", "LOW",
                "Only 36 days of history. Revalidate Hawkes parameters after additional data."));
        return flags;
    }

    private Map<String, Object> flag(String name, String severity, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flag", name);
        m.put("severity", severity);
        m.put("detail", detail);
        return m;
    }

    private List<Map<String, Object>> opportunities() {
        List<Map<String, Object>> opps = new ArrayList<>();
        opps.add(opportunity("High-Velocity Topic",
                "Branching ratio 0.84 — a single well-timed campaign post can cascade into a wave of organic discussion."));
        opps.add(opportunity("Goodwill Tailwind",
                "Sentiment is predominantly positive — an ideal moment to associate your brand with it."));
        return opps;
    }

    private Map<String, Object> opportunity(String name, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opportunity", name);
        m.put("detail", detail);
        return m;
    }

    // -------------------------------------------------------------------------
    // PDF byte assertions
    // -------------------------------------------------------------------------

    private static boolean startsWithPdfHeader(byte[] pdf) {
        byte[] magic = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (pdf == null || pdf.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (pdf[i] != magic[i]) return false;
        }
        return true;
    }

    private static boolean containsEof(byte[] pdf) {
        String tail = new String(pdf, Math.max(0, pdf.length - 1024), Math.min(pdf.length, 1024), StandardCharsets.ISO_8859_1);
        return tail.contains("%%EOF");
    }

    private static String extractPdfText(byte[] pdf) throws Exception {
        com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
        com.lowagie.text.pdf.parser.PdfTextExtractor extractor =
                new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
        StringBuilder sb = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            sb.append(extractor.getTextFromPage(page)).append('\n');
        }
        reader.close();
        return sb.toString();
    }
}
