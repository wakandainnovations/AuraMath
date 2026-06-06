package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Turns the aggregate Hawkes audit of an entity's keyword conversation into
 * plain-English marketing intelligence — the entity-scoped counterpart to
 * {@link MarketingUserReportController}.
 *
 * Two endpoints return the IDENTICAL payload:
 *   GET /api/marketing/entity-report/{entityId}        – shareable report, e.g.
 *                                                         to show a prospect why
 *                                                         the product is worth it
 *   GET /api/marketing/entity/{entityId}/report        – in-app view a signed-in
 *                                                         user opens for any
 *                                                         entity of their choice
 *
 * Sections returned:
 *   entityProfile          – what this entity is and how much chatter it drives
 *   conversationProfile    – how and when the conversation spikes (virality)
 *   topicIntelligence      – which keywords drive bursts and at what tone
 *   audienceSentiment      – overall sentiment of the conversation
 *   channelStrategy        – where the conversation lives across platforms
 *   topAdvocates           – highest-amplification voices in the conversation
 *   marketingRecommendations – actionable campaign guidance
 *   redFlags               – risks a marketer should know
 *   opportunityFlags       – specific openings to exploit
 */
@RestController
public class EntityReportController {

    @Autowired private EntityIntelService     intel;
    @Autowired private EntityMarketingService marketing;

    private static final DateTimeFormatter TS_FMT       = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ZoneId            IST          = ZoneId.of("Asia/Kolkata");
    private static final String            TZ           = "IST";
    private static final int               ADVOCATE_TOP = 10;

    // -------------------------------------------------------------------------
    // Endpoints — both delegate to the same report builder.
    // -------------------------------------------------------------------------

    /** Shareable report for prospective customers. */
    @GetMapping("/api/marketing/entity-report/{entityId}")
    public ResponseEntity<Map<String, Object>> getShareableReport(@PathVariable String entityId) {
        return ResponseEntity.ok(buildReport(entityId));
    }

    /** In-app report a logged-in user opens for any entity of their choice. */
    @GetMapping("/api/marketing/entity/{entityId}/report")
    public ResponseEntity<Map<String, Object>> getEntityReport(@PathVariable String entityId) {
        return ResponseEntity.ok(buildReport(entityId));
    }

    // -------------------------------------------------------------------------
    // Report builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildReport(String entityId) {
        EntityIntelService.EntityProfile entity = intel.lookup(entityId);
        if (entity == null) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("entityId", entityId);
            notFound.put("message", "No entity found for this id");
            return notFound;
        }

        HawkesAuditService.AuditResult result = intel.computeAudit(entity);
        if (result.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("entityId",        entityId);
            empty.put("name",            entity.name);
            empty.put("trackedKeywords", entity.keywords);
            empty.put("message", "No scored post history found for this entity — cannot generate a report");
            return empty;
        }

        List<HawkesAuditService.AuditEntry> entries = result.entries;
        double branchingRatio = result.alpha / HawkesAuditService.BETA;
        int    distinctBursts = countDistinctClusters(entries);
        List<BurstRegion> burstRegions = extractBurstRegions(entries);
        long   audienceSize   = marketing.audienceSize(entity.keywords);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt",              nowIso());
        report.put("entityProfile",            buildEntityProfile(entity, entries, branchingRatio, audienceSize));
        report.put("conversationProfile",      buildConversationProfile(entries, distinctBursts, burstRegions, branchingRatio));
        report.put("topicIntelligence",        buildTopicIntelligence(entries, burstRegions));
        report.put("audienceSentiment",        buildAudienceSentiment(entries));
        report.put("channelStrategy",          buildChannelStrategy(entries));
        report.put("topAdvocates",             marketing.topSpreaders(entity.keywords, ADVOCATE_TOP));
        report.put("marketingRecommendations", buildRecommendations(entries, branchingRatio, burstRegions, distinctBursts, audienceSize));
        report.put("redFlags",                 buildRedFlags(entries, branchingRatio, burstRegions, audienceSize));
        report.put("opportunityFlags",         buildOpportunities(entries, branchingRatio, burstRegions));
        return report;
    }

    // -------------------------------------------------------------------------
    // Section builders
    // -------------------------------------------------------------------------

    private Map<String, Object> buildEntityProfile(
            EntityIntelService.EntityProfile entity,
            List<HawkesAuditService.AuditEntry> entries,
            double branchingRatio, long audienceSize) {

        int n = entries.size();
        Set<String> platforms = new LinkedHashSet<>();
        for (HawkesAuditService.AuditEntry e : entries) platforms.add(e.platform);

        long spanMs   = entries.get(n - 1).timestamp.getTime() - entries.get(0).timestamp.getTime();
        double spanDays = spanMs / 86_400_000.0;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entityId",               entity.entityId);
        p.put("name",                   entity.name);
        p.put("type",                   entity.type);
        p.put("trackedKeywords",        entity.keywords);
        p.put("activePlatforms",        new ArrayList<>(platforms));
        p.put("totalPosts",             n);
        p.put("audienceSize",           audienceSize);
        p.put("firstSeen",              toIso(entries.get(0).timestamp));
        p.put("lastSeen",               toIso(entries.get(n - 1).timestamp));
        p.put("observationSpanDays",    Math.round(spanDays * 10.0) / 10.0);
        p.put("averagePostsPerDay",     spanDays > 0 ? Math.round((n / spanDays) * 10.0) / 10.0 : n);
        p.put("viralityTier",           viralityTier(branchingRatio));
        p.put("viralityTierExplained",  viralityTierDescription(branchingRatio));
        return p;
    }

    private Map<String, Object> buildConversationProfile(
            List<HawkesAuditService.AuditEntry> entries,
            int distinctBursts, List<BurstRegion> burstRegions, double branchingRatio) {

        // Peak hours (top-3 by post count)
        Map<Integer, Integer> hourHist = new TreeMap<>();
        for (HawkesAuditService.AuditEntry e : entries) {
            hourHist.merge(e.timestamp.toInstant().atZone(IST).getHour(), 1, Integer::sum);
        }
        List<String> peakHours = hourHist.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(3)
                .map(en -> String.format("%02d:00–%02d:00 " + TZ + " (%d posts)", en.getKey(), en.getKey() + 1, en.getValue()))
                .collect(Collectors.toList());

        // Most active day of week
        Map<DayOfWeek, Integer> dayHist = new LinkedHashMap<>();
        for (HawkesAuditService.AuditEntry e : entries) {
            dayHist.merge(e.timestamp.toInstant().atZone(IST).getDayOfWeek(), 1, Integer::sum);
        }
        String busiest = dayHist.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(en -> en.getKey().name() + " (" + en.getValue() + " posts)")
                .orElse("N/A");

        // Largest burst summary
        Map<String, Object> longestBurst = null;
        if (!burstRegions.isEmpty()) {
            BurstRegion best = burstRegions.stream().max(Comparator.comparingInt(b -> b.postCount)).get();
            longestBurst = new LinkedHashMap<>();
            longestBurst.put("keyword",             best.triggerKeyword);
            longestBurst.put("startTime",           toIso(best.startTime));
            longestBurst.put("durationMinutes",     Math.round(best.durationMin * 10.0) / 10.0);
            longestBurst.put("postCount",           best.postCount);
            longestBurst.put("peakExcitationSpike", Math.round(best.peakSpike * 1000.0) / 1000.0);
            longestBurst.put("readableDescription",
                String.format("%d posts about '%s' in %.1f minutes — peak excitation %.2f",
                    best.postCount, best.triggerKeyword, best.durationMin, best.peakSpike));
        }

        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("branchingRatio",         Math.round(branchingRatio * 10000.0) / 10000.0);
        cp.put("amplificationExplained", String.format(
                "Each post about this entity triggers ~%.2f organic follow-up posts on average (Hawkes branching ratio).",
                branchingRatio));
        cp.put("distinctBurstEvents",    distinctBursts);
        cp.put("peakActivityWindows",    peakHours);
        cp.put("mostActiveDayOfWeek",    busiest);
        cp.put("longestBurst",           longestBurst);
        return cp;
    }

    private List<Map<String, Object>> buildTopicIntelligence(
            List<HawkesAuditService.AuditEntry> entries, List<BurstRegion> burstRegions) {

        Map<String, List<HawkesAuditService.AuditEntry>> byKeyword = new LinkedHashMap<>();
        for (HawkesAuditService.AuditEntry e : entries) {
            if (e.keyword != null && !e.keyword.isEmpty())
                byKeyword.computeIfAbsent(e.keyword, k -> new ArrayList<>()).add(e);
        }

        List<Map<String, Object>> topics = new ArrayList<>();
        for (Map.Entry<String, List<HawkesAuditService.AuditEntry>> kv : byKeyword.entrySet()) {
            String kw = kv.getKey();
            List<HawkesAuditService.AuditEntry> kEntries = kv.getValue();

            Map<String, Long> toneDist = kEntries.stream()
                    .collect(Collectors.groupingBy(HawkesAuditService.AuditEntry::tone, Collectors.counting()));
            String dominantTone = toneDist.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("neutral");

            Map<String, Long> catDist = kEntries.stream()
                    .filter(e -> e.sentimentCategory != null)
                    .collect(Collectors.groupingBy(e -> e.sentimentCategory, Collectors.counting()));
            String topCategory = catDist.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("unknown");

            long burstsTriggered = burstRegions.stream().filter(b -> kw.equals(b.triggerKeyword)).count();
            double avgSpike = kEntries.stream().mapToDouble(e -> e.excitationSpike).average().orElse(0);
            OptionalDouble avgScore = kEntries.stream()
                    .filter(e -> e.sentimentScore != null).mapToDouble(e -> e.sentimentScore).average();

            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("keyword",               kw);
            topic.put("totalMentions",         kEntries.size());
            topic.put("burstsTriggered",       burstsTriggered);
            topic.put("contentCategory",       topCategory);
            topic.put("toneBreakdown",         toneDist);
            topic.put("dominantTone",          dominantTone);
            topic.put("averageSentimentScore", avgScore.isPresent() ? Math.round(avgScore.getAsDouble() * 10.0) / 10.0 : null);
            topic.put("averageExcitationSpike",Math.round(avgSpike * 1000.0) / 1000.0);
            topic.put("excitationProfile",     excitationProfile(avgSpike, burstsTriggered, dominantTone));
            topics.add(topic);
        }
        topics.sort((a, b) -> Integer.compare((int) b.get("totalMentions"), (int) a.get("totalMentions")));
        return topics;
    }

    private Map<String, Object> buildAudienceSentiment(List<HawkesAuditService.AuditEntry> entries) {
        Map<String, Long> tones = entries.stream()
                .collect(Collectors.groupingBy(HawkesAuditService.AuditEntry::tone, Collectors.counting()));
        long positive = tones.getOrDefault("positive", 0L);
        long negative = tones.getOrDefault("negative", 0L);
        long neutral  = tones.getOrDefault("neutral", 0L);
        long total    = positive + negative + neutral;

        String dominantTone = tones.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("neutral");
        double netSentiment = total == 0 ? 0 : (double) (positive - negative) / total;

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("toneBreakdown",  tones);
        s.put("dominantTone",   dominantTone);
        s.put("netSentiment",   Math.round(netSentiment * 100.0) / 100.0);
        s.put("sentimentLabel", sentimentLabel(netSentiment));
        return s;
    }

    /** Platform presence derived from the post stream — where the conversation lives. */
    private Map<String, Object> buildChannelStrategy(List<HawkesAuditService.AuditEntry> entries) {
        Map<String, Long> byPlatform = entries.stream()
                .collect(Collectors.groupingBy(e -> e.platform, Collectors.counting()));

        int total = entries.size();
        List<Map<String, Object>> channels = byPlatform.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(en -> {
                    Map<String, Object> ch = new LinkedHashMap<>();
                    ch.put("platform",  platformLabel(en.getKey()));
                    ch.put("postCount", en.getValue());
                    ch.put("share",     Math.round(((double) en.getValue() / total) * 1000.0) / 1000.0);
                    return ch;
                })
                .collect(Collectors.toList());

        String topChannel = channels.isEmpty() ? "Unknown" : (String) channels.get(0).get("platform");
        String headline = channels.size() == 1
                ? "Conversation is concentrated entirely on " + topChannel + "."
                : "Conversation is led by " + topChannel + " — focus campaign spend there first.";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topChannel", topChannel);
        body.put("headline",   headline);
        body.put("channels",   channels);
        return body;
    }

    private Map<String, Object> buildRecommendations(
            List<HawkesAuditService.AuditEntry> entries, double branchingRatio,
            List<BurstRegion> burstRegions, int distinctBursts, long audienceSize) {

        Map<String, Long> platCount = entries.stream()
                .collect(Collectors.groupingBy(e -> e.platform, Collectors.counting()));
        String bestPlatform = platCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("unknown");

        String topKeyword = burstRegions.stream()
                .filter(b -> b.triggerKeyword != null)
                .collect(Collectors.groupingBy(b -> b.triggerKeyword, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        Map<Integer, Integer> hourHist = new TreeMap<>();
        for (HawkesAuditService.AuditEntry e : entries) {
            hourHist.merge(e.timestamp.toInstant().atZone(IST).getHour(), 1, Integer::sum);
        }
        int peakHour = hourHist.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(12);

        Map<String, Long> allTones = entries.stream()
                .collect(Collectors.groupingBy(HawkesAuditService.AuditEntry::tone, Collectors.counting()));
        String dominantTone = allTones.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("neutral");

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("primaryChannel",           platformLabel(bestPlatform));
        rec.put("bestTimeToEngage",         String.format("%02d:00–%02d:00 " + TZ + " — peak conversation window", peakHour, peakHour + 1));
        rec.put("campaignType",             campaignType(distinctBursts, branchingRatio));
        rec.put("amplificationPotential",   amplificationPotential(branchingRatio));
        rec.put("estimatedReachMultiplier", String.format("~%.1fx per seeded post (branching ratio %.2f)", branchingRatio, branchingRatio));
        rec.put("addressableAudience",      audienceSize + " distinct authors are already talking about this entity");
        rec.put("contentTriggers",          topKeyword != null ? Collections.singletonList(topKeyword) : Collections.emptyList());
        rec.put("contentStrategy",          contentStrategy(topKeyword, dominantTone));
        rec.put("actionableAdvice",         actionableAdvice(topKeyword, dominantTone, branchingRatio, peakHour, bestPlatform));
        return rec;
    }

    private List<Map<String, Object>> buildRedFlags(
            List<HawkesAuditService.AuditEntry> entries, double branchingRatio,
            List<BurstRegion> burstRegions, long audienceSize) {

        List<Map<String, Object>> flags = new ArrayList<>();
        Set<String> platforms = entries.stream().map(e -> e.platform).collect(Collectors.toSet());

        if (platforms.size() == 1) {
            flags.add(flag("Single-Platform Conversation",
                "All chatter is on " + platformLabel(platforms.iterator().next()) +
                " — no cross-platform spread. Reach is capped within one network.", "MEDIUM"));
        }
        if (audienceSize < 25) {
            flags.add(flag("Thin Audience",
                "Only " + audienceSize + " distinct authors are discussing this entity. " +
                "The addressable organic audience is small — pair with paid amplification.", "HIGH"));
        }
        if (branchingRatio < 0.3) {
            flags.add(flag("Low Amplification Return",
                "Branching ratio " + Math.round(branchingRatio * 100.0) / 100.0 +
                " — conversation does not self-sustain. Seeded content yields little organic follow-through.", "HIGH"));
        }
        Map<String, Long> tones = entries.stream()
                .collect(Collectors.groupingBy(HawkesAuditService.AuditEntry::tone, Collectors.counting()));
        long total = entries.size();
        if (tones.getOrDefault("negative", 0L) > total / 2) {
            flags.add(flag("Negative Sentiment Majority",
                "Most posts about this entity are negative. Lead with reputation repair before promotional messaging.", "HIGH"));
        }
        long observationDays = (entries.get(entries.size() - 1).timestamp.getTime()
                - entries.get(0).timestamp.getTime()) / 86_400_000L;
        if (observationDays < 7) {
            flags.add(flag("Thin Data Window",
                "Only " + observationDays + " day(s) of history. Hawkes parameters may not be stable — " +
                "revalidate after 2–3 weeks of additional data.", "LOW"));
        }
        return flags;
    }

    private List<Map<String, Object>> buildOpportunities(
            List<HawkesAuditService.AuditEntry> entries, double branchingRatio, List<BurstRegion> burstRegions) {

        List<Map<String, Object>> opps = new ArrayList<>();

        Map<String, Long> tones = entries.stream()
                .collect(Collectors.groupingBy(HawkesAuditService.AuditEntry::tone, Collectors.counting()));
        String dominantTone = tones.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("neutral");

        if (branchingRatio >= 0.7) {
            opps.add(opportunity("High-Velocity Topic",
                "Branching ratio " + Math.round(branchingRatio * 100.0) / 100.0 +
                " — this conversation is near a critical spreading threshold. A single well-timed " +
                "campaign post can cascade into a wave of organic discussion."));
        }
        if ("positive".equals(dominantTone)) {
            opps.add(opportunity("Goodwill Tailwind",
                "Sentiment around this entity is predominantly positive — an ideal moment to associate " +
                "your brand with it and ride the existing enthusiasm."));
        }
        if (!burstRegions.isEmpty()) {
            String topKeyword = burstRegions.stream()
                    .filter(b -> b.triggerKeyword != null)
                    .collect(Collectors.groupingBy(b -> b.triggerKeyword, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
            if (topKeyword != null) {
                opps.add(opportunity("Keyword Anchor Window",
                    "Posts about '" + topKeyword + "' reliably trigger burst activity. Timing campaign " +
                    "content to '" + topKeyword + "'-related news will maximise organic reach."));
            }
        }
        return opps;
    }

    // -------------------------------------------------------------------------
    // Burst region extraction (mirrors the per-user report)
    // -------------------------------------------------------------------------

    static class BurstRegion {
        java.util.Date startTime;
        java.util.Date endTime;
        double         durationMin;
        int            postCount;
        double         peakSpike;
        String         triggerKeyword;
    }

    private List<BurstRegion> extractBurstRegions(List<HawkesAuditService.AuditEntry> entries) {
        List<BurstRegion> regions = new ArrayList<>();
        boolean inside = false;
        BurstRegion cur = null;

        for (HawkesAuditService.AuditEntry e : entries) {
            boolean inBurst = e.burstSize >= HawkesAuditService.CLUSTER_MIN;
            if (inBurst && !inside) {
                cur = new BurstRegion();
                cur.startTime      = e.timestamp;
                cur.postCount      = 0;
                cur.peakSpike      = 0;
                cur.triggerKeyword = e.keyword;
                inside = true;
                regions.add(cur);
            }
            if (inBurst) {
                cur.endTime  = e.timestamp;
                cur.postCount++;
                cur.peakSpike = Math.max(cur.peakSpike, e.excitationSpike);
                if (cur.triggerKeyword == null && e.keyword != null) cur.triggerKeyword = e.keyword;
            } else {
                inside = false;
            }
        }
        for (BurstRegion r : regions) {
            if (r.endTime != null)
                r.durationMin = (r.endTime.getTime() - r.startTime.getTime()) / 60_000.0;
        }
        return regions;
    }

    private int countDistinctClusters(List<HawkesAuditService.AuditEntry> entries) {
        int count = 0;
        boolean inside = false;
        for (HawkesAuditService.AuditEntry e : entries) {
            if (e.burstSize >= HawkesAuditService.CLUSTER_MIN && !inside) { count++; inside = true; }
            else if (e.burstSize < HawkesAuditService.CLUSTER_MIN)        { inside = false; }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Classifiers and text generators
    // -------------------------------------------------------------------------

    private String viralityTier(double br) {
        if (br >= 0.8) return "Viral Topic";
        if (br >= 0.6) return "Trending";
        if (br >= 0.3) return "Active Conversation";
        return "Niche";
    }

    private String viralityTierDescription(double br) {
        if (br >= 0.8) return String.format(
            "Branching ratio %.2f — near-supercritical. Conversation about this entity self-sustains; " +
            "content seeded here is highly likely to cascade organically.", br);
        if (br >= 0.6) return String.format(
            "Branching ratio %.2f — a reliably amplifying topic. Each post drives ~%.1f organic follow-ups. " +
            "Strong candidate for seeding campaigns.", br, br);
        if (br >= 0.3) return String.format(
            "Branching ratio %.2f — an active but measured conversation. Best suited to sustained awareness campaigns.", br);
        return String.format(
            "Branching ratio %.2f — low self-amplification. Conversation does not spread on its own; " +
            "direct-response campaigns will outperform viral strategies.", br);
    }

    private String excitationProfile(double avgSpike, long burstsTriggered, String tone) {
        if (burstsTriggered > 0 && avgSpike > 0.1)
            return String.format(
                "HIGH — keyword consistently triggers burst activity (%d burst(s)). Dominant tone: %s.", burstsTriggered, tone);
        if (avgSpike > 0.05)
            return String.format("MEDIUM — keyword raises activity but rarely produces full bursts. Tone: %s.", tone);
        return String.format("LOW — mentions are scattered with no strong excitation pattern. Tone: %s.", tone);
    }

    private String campaignType(int distinctBursts, double br) {
        if (distinctBursts >= 2 && br >= 0.7)
            return "Reactive Amplification Campaign — time a seeding post to coincide with known burst windows " +
                "to trigger a cascade of organic follow-up content.";
        if (br >= 0.5)
            return "Sustained Awareness Campaign — leverage the steady conversation for drip content over multiple weeks.";
        return "Targeted Engagement Campaign — direct, personalised outreach will outperform viral seeding for this topic.";
    }

    private String amplificationPotential(double br) {
        if (br >= 0.8) return "HIGH — near-supercritical; a single interaction can trigger a content cascade";
        if (br >= 0.5) return "MEDIUM — reliable amplifier; expect ~" + Math.round(br * 10.0) / 10.0 + "x organic reach per interaction";
        if (br >= 0.3) return "LOW-MEDIUM — moderate spread; best paired with paid amplification";
        return "LOW — minimal self-amplification; focus on direct conversion";
    }

    private String contentStrategy(String topKeyword, String tone) {
        String kwStr = topKeyword != null ? "'" + topKeyword + "'" : "this entity's core topics";
        switch (tone) {
            case "negative":
                return "Conversation about " + kwStr + " skews critical. Lead with solutions and resolutions to " +
                    "the pain points raised — brands that visibly address them earn authentic advocacy.";
            case "positive":
                return "Audiences champion " + kwStr + " enthusiastically. Create content that celebrates and deepens " +
                    "that connection — exclusives, behind-the-scenes, or community recognition work well.";
            default:
                return "Conversation about " + kwStr + " is informational. Use value-first content to establish " +
                    "relevance before moving to promotional messaging.";
        }
    }

    private String sentimentLabel(double net) {
        if (net >= 0.3)  return "Predominantly Positive";
        if (net >= 0.05) return "Leaning Positive";
        if (net > -0.05) return "Mixed / Neutral";
        if (net > -0.3)  return "Leaning Negative";
        return "Predominantly Negative";
    }

    private String actionableAdvice(String keyword, String tone, double br, int peakHour, String platform) {
        String kwStr  = keyword != null ? " around '" + keyword + "'" : "";
        String window = String.format("%02d:00–%02d:00 " + TZ, peakHour, peakHour + 1);
        return String.format(
            "1. TIMING: Publish during the %s window when the conversation peaks%s. " +
            "2. PLATFORM: Conversation is strongest on %s — start there. " +
            "3. TONE: %s " +
            "4. TRIGGER: Tie your message to '%s' — it is the proven engagement trigger for this entity.",
            window, kwStr, platformLabel(platform),
            "negative".equals(tone)
                ? "Sentiment is critical — address the complaints publicly and helpfully before promoting."
                : "positive".equals(tone)
                    ? "Sentiment is enthusiastic — offer exclusives or early access to reward it."
                    : "Keep messaging informational and value-first.",
            keyword != null ? keyword : "the core topic");
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private Map<String, Object> flag(String name, String detail, String severity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flag",     name);
        m.put("severity", severity);
        m.put("detail",   detail);
        return m;
    }

    private Map<String, Object> opportunity(String name, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opportunity", name);
        m.put("detail",      detail);
        return m;
    }

    private String platformLabel(String p) {
        if (p == null) return "Unknown";
        switch (p) {
            case "x":         return "X (Twitter)";
            case "youtube":   return "YouTube";
            case "reddit":    return "Reddit";
            case "instagram": return "Instagram";
            default:          return p;
        }
    }

    private String toIso(java.util.Date d) {
        return d.toInstant().atZone(IST).toLocalDateTime().format(TS_FMT) + " " + TZ;
    }

    private String nowIso() {
        return java.time.Instant.now().atZone(IST).toLocalDateTime().format(TS_FMT) + " " + TZ;
    }
}
