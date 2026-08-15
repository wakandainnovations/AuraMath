package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Translates the mathematical Hawkes audit into plain-English marketing intelligence.
 *
 * GET /api/marketing/user-report/{author}
 *
 * Sections returned:
 *   userProfile            – who this user is, influence tier
 *   engagementProfile      – how and when they post
 *   topicIntelligence      – which topics drive them and at what tone
 *   marketingRecommendations – actionable campaign guidance
 *   redFlags               – risks a marketer should know
 *   opportunityFlags       – specific openings to exploit
 */
@RestController
@RequestMapping("/api/marketing")
public class MarketingUserReportController {

    @Autowired
    private HawkesAuditService service;

    @Autowired
    private AuthorCategoryRepository categoryRepository;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ZoneId            IST    = ZoneId.of("Asia/Kolkata");
    private static final String            TZ     = "IST";

    // -------------------------------------------------------------------------
    // Endpoint
    // -------------------------------------------------------------------------

    @GetMapping("/user-report/{author}")
    public ResponseEntity<Map<String, Object>> getUserReport(@PathVariable String author) {

        HawkesAuditService.AuditResult result = service.compute(author);

        if (result.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("author", author);
            empty.put("message", "No post history found — cannot generate a report");
            return ResponseEntity.ok(empty);
        }

        List<HawkesAuditService.AuditEntry> entries = result.entries;
        double alpha          = result.alpha;
        double branchingRatio = alpha / HawkesAuditService.BETA;
        int    distinctBursts = service.countDistinctClusters(entries);
        List<BurstRegion> burstRegions = extractBurstRegions(entries);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt",              nowIso());
        report.put("userProfile",              buildUserProfile(result, branchingRatio));
        report.put("engagementProfile",        buildEngagementProfile(entries, branchingRatio, distinctBursts, burstRegions));
        report.put("topicIntelligence",        buildTopicIntelligence(entries, burstRegions));
        report.put("marketingRecommendations", buildRecommendations(entries, result, branchingRatio, burstRegions));
        report.put("redFlags",                 buildRedFlags(entries, branchingRatio, burstRegions));
        report.put("opportunityFlags",         buildOpportunities(entries, branchingRatio, burstRegions));

        // Persist categorical labels so the marketing team can later list users by category.
        categoryRepository.upsert(categorize(result, burstRegions, distinctBursts));
        return ResponseEntity.ok(report);
    }

    /**
     * Builds a flat {@link AuthorCategorization} record from an audit result —
     * the same labels surfaced in the report response, but trimmed to short
     * filterable values so they can be persisted and queried.
     *
     * Package-private so the sync controller can reuse it.
     */
    AuthorCategorization categorize(
            HawkesAuditService.AuditResult result,
            List<BurstRegion> burstRegions,
            int distinctBursts) {

        List<HawkesAuditService.AuditEntry> entries = result.entries;
        double branchingRatio = result.alpha / HawkesAuditService.BETA;

        Map<String, Long> tones = entries.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                HawkesAuditService.AuditEntry::tone, java.util.stream.Collectors.counting()));
        String dominantTone = tones.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("neutral");

        Map<String, Long> plats = entries.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                e -> e.platform, java.util.stream.Collectors.counting()));
        String primaryPlatform = plats.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("unknown");

        // audienceType() returns "Label — description"; strip to the label only for filtering.
        String audienceFull  = audienceType(dominantTone, branchingRatio);
        String audienceLabel = audienceFull.split(" — ", 2)[0].trim();

        AuthorCategorization c = new AuthorCategorization();
        c.author                 = result.author;
        c.audienceClassification = audienceLabel;
        c.influenceTier          = influenceTier(branchingRatio);
        c.postingStyle           = postingStyle(distinctBursts, burstRegions);
        c.dominantTone           = dominantTone;
        c.primaryPlatform        = primaryPlatform;
        c.branchingRatio         = Math.round(branchingRatio * 10000.0) / 10000.0;
        c.totalPosts             = entries.size();
        c.lastCategorizedAt      = java.time.LocalDateTime.now();
        return c;
    }

    // -------------------------------------------------------------------------
    // Section builders
    // -------------------------------------------------------------------------

    private Map<String, Object> buildUserProfile(
            HawkesAuditService.AuditResult result, double branchingRatio) {

        List<HawkesAuditService.AuditEntry> entries = result.entries;
        int n = entries.size();

        Set<String> platforms = new LinkedHashSet<>();
        for (HawkesAuditService.AuditEntry e : entries) platforms.add(e.platform);

        long spanMs  = entries.get(n - 1).timestamp.getTime() - entries.get(0).timestamp.getTime();
        double spanDays = spanMs / 86_400_000.0;

        String tier = influenceTier(branchingRatio);
        String tierDesc = influenceTierDescription(branchingRatio, result.alpha);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("author",                  result.author);
        p.put("activePlatforms",         new ArrayList<>(platforms));
        p.put("totalPosts",              n);
        p.put("firstSeen",               toIso(entries.get(0).timestamp));
        p.put("lastSeen",                toIso(entries.get(n - 1).timestamp));
        p.put("observationSpanDays",     Math.round(spanDays * 10.0) / 10.0);
        p.put("averagePostsPerDay",      spanDays > 0 ? Math.round((n / spanDays) * 10.0) / 10.0 : n);
        p.put("influenceTier",           tier);
        p.put("influenceTierExplained",  tierDesc);
        return p;
    }

    private Map<String, Object> buildEngagementProfile(
            List<HawkesAuditService.AuditEntry> entries,
            double branchingRatio, int distinctBursts, List<BurstRegion> burstRegions) {

        int n = entries.size();
        String style     = postingStyle(distinctBursts, burstRegions);
        String styleDesc = postingStyleDescription(style, distinctBursts, burstRegions);

        // Average gap between posts inside bursts
        List<Double> inBurstGaps = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            if (entries.get(i).burstSize >= HawkesAuditService.CLUSTER_MIN
                    && entries.get(i - 1).burstSize >= HawkesAuditService.CLUSTER_MIN) {
                inBurstGaps.add(entries.get(i).tMin - entries.get(i - 1).tMin);
            }
        }
        double avgGapInBurst = inBurstGaps.isEmpty() ? 0
                : inBurstGaps.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        // Peak hours (top-3 by post count)
        Map<Integer, Integer> hourHist = new TreeMap<>();
        for (HawkesAuditService.AuditEntry e : entries) {
            int hour = e.timestamp.toInstant().atZone(IST).getHour();
            hourHist.merge(hour, 1, Integer::sum);
        }
        List<String> peakHours = hourHist.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(3)
                .map(en -> String.format("%02d:00–%02d:00 " + TZ + " (%d posts)", en.getKey(), en.getKey() + 1, en.getValue()))
                .collect(Collectors.toList());

        // Most active day of week
        Map<DayOfWeek, Integer> dayHist = new LinkedHashMap<>();
        for (HawkesAuditService.AuditEntry e : entries) {
            DayOfWeek dow = e.timestamp.toInstant().atZone(IST).getDayOfWeek();
            dayHist.merge(dow, 1, Integer::sum);
        }
        String busiest = dayHist.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(en -> en.getKey().name() + " (" + en.getValue() + " posts)")
                .orElse("N/A");

        // Longest burst summary
        Map<String, Object> longestBurst = null;
        if (!burstRegions.isEmpty()) {
            BurstRegion best = burstRegions.stream()
                    .max(Comparator.comparingInt(b -> b.postCount))
                    .get();
            longestBurst = new LinkedHashMap<>();
            longestBurst.put("keyword",               best.triggerKeyword);
            longestBurst.put("startTime",             toIso(best.startTime));
            longestBurst.put("durationMinutes",       Math.round(best.durationMin * 10.0) / 10.0);
            longestBurst.put("postCount",             best.postCount);
            longestBurst.put("peakExcitationSpike",   Math.round(best.peakSpike * 1000.0) / 1000.0);
            longestBurst.put("readableDescription",
                String.format("%d posts about '%s' in %.1f minutes — peak excitation %.2f",
                    best.postCount, best.triggerKeyword, best.durationMin, best.peakSpike));
        }

        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("postingStyle",                style);
        ep.put("postingStyleExplained",       styleDesc);
        ep.put("distinctBurstEvents",         distinctBursts);
        ep.put("averageGapInsideBurstMinutes",Math.round(avgGapInBurst * 100.0) / 100.0);
        ep.put("peakActivityWindows",         peakHours);
        ep.put("mostActiveDayOfWeek",         busiest);
        ep.put("longestBurst",                longestBurst);
        return ep;
    }

    private List<Map<String, Object>> buildTopicIntelligence(
            List<HawkesAuditService.AuditEntry> entries, List<BurstRegion> burstRegions) {

        // Group entries by keyword (skip blanks)
        Map<String, List<HawkesAuditService.AuditEntry>> byKeyword = new LinkedHashMap<>();
        for (HawkesAuditService.AuditEntry e : entries) {
            if (e.keyword != null && !e.keyword.isEmpty())
                byKeyword.computeIfAbsent(e.keyword, k -> new ArrayList<>()).add(e);
        }

        List<Map<String, Object>> topics = new ArrayList<>();
        for (Map.Entry<String, List<HawkesAuditService.AuditEntry>> kv : byKeyword.entrySet()) {
            String kw   = kv.getKey();
            List<HawkesAuditService.AuditEntry> kEntries = kv.getValue();

            // Sentiment tone breakdown
            Map<String, Long> toneDist = kEntries.stream()
                    .collect(Collectors.groupingBy(HawkesAuditService.AuditEntry::tone, Collectors.counting()));

            String dominantTone = toneDist.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("neutral");

            // Content category distribution
            Map<String, Long> catDist = kEntries.stream()
                    .filter(e -> e.sentimentCategory != null)
                    .collect(Collectors.groupingBy(e -> e.sentimentCategory, Collectors.counting()));

            String topCategory = catDist.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("unknown");

            // Bursts triggered by this keyword
            long burstsTriggered = burstRegions.stream()
                    .filter(b -> kw.equals(b.triggerKeyword)).count();

            // Average excitation spike for posts about this keyword
            double avgSpike = kEntries.stream()
                    .mapToDouble(e -> e.excitationSpike).average().orElse(0);

            // Average sentiment score
            OptionalDouble avgScore = kEntries.stream()
                    .filter(e -> e.sentimentScore != null)
                    .mapToDouble(e -> e.sentimentScore).average();

            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("keyword",              kw);
            topic.put("totalMentions",        kEntries.size());
            topic.put("burstsTriggered",      burstsTriggered);
            topic.put("contentCategory",      topCategory);
            topic.put("toneBreakdown",        toneDist);
            topic.put("dominantTone",         dominantTone);
            topic.put("averageSentimentScore",avgScore.isPresent() ? Math.round(avgScore.getAsDouble() * 10.0) / 10.0 : null);
            topic.put("averageExcitationSpike", Math.round(avgSpike * 1000.0) / 1000.0);
            topic.put("excitationProfile",    excitationProfile(avgSpike, burstsTriggered, dominantTone));
            topics.add(topic);
        }

        return topics;
    }

    private Map<String, Object> buildRecommendations(
            List<HawkesAuditService.AuditEntry> entries,
            HawkesAuditService.AuditResult result,
            double branchingRatio, List<BurstRegion> burstRegions) {

        int n = entries.size();
        String style = postingStyle(service.countDistinctClusters(entries), burstRegions);

        // Best platform
        Map<String, Long> platCount = entries.stream()
                .collect(Collectors.groupingBy(e -> e.platform, Collectors.counting()));
        String bestPlatform = platCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("unknown");

        // Most burst-triggering keyword
        String topKeyword = burstRegions.stream()
                .filter(b -> b.triggerKeyword != null)
                .collect(Collectors.groupingBy(b -> b.triggerKeyword, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        // Peak hour range for targeting window
        Map<Integer, Integer> hourHist = new TreeMap<>();
        for (HawkesAuditService.AuditEntry e : entries) {
            int hr = e.timestamp.toInstant().atZone(IST).getHour();
            hourHist.merge(hr, 1, Integer::sum);
        }
        int peakHour = hourHist.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(12);

        // Dominant tone across all posts
        Map<String, Long> allTones = entries.stream()
                .collect(Collectors.groupingBy(HawkesAuditService.AuditEntry::tone, Collectors.counting()));
        String dominantTone = allTones.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("neutral");

        String campaignType   = campaignType(style, branchingRatio);
        String amplPotential  = amplificationPotential(branchingRatio);
        String contentStrat   = contentStrategy(topKeyword, dominantTone, style);
        String audienceType   = audienceType(dominantTone, branchingRatio);

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("primaryChannel",          platformLabel(bestPlatform));
        rec.put("bestTimeToEngage",        String.format("%02d:00–%02d:00 " + TZ + " — peak activity window", peakHour, peakHour + 1));
        rec.put("campaignType",            campaignType);
        rec.put("audienceClassification",  audienceType);
        rec.put("amplificationPotential",  amplPotential);
        rec.put("estimatedReachMultiplier",String.format("~%.1fx per post (branching ratio %.2f)", branchingRatio, branchingRatio));
        rec.put("contentTriggers",         topKeyword != null ? Collections.singletonList(topKeyword) : Collections.emptyList());
        rec.put("contentStrategy",         contentStrat);
        rec.put("actionableAdvice",        actionableAdvice(topKeyword, dominantTone, style, branchingRatio, peakHour, bestPlatform));
        return rec;
    }

    private List<Map<String, Object>> buildRedFlags(
            List<HawkesAuditService.AuditEntry> entries,
            double branchingRatio, List<BurstRegion> burstRegions) {

        List<Map<String, Object>> flags = new ArrayList<>();
        Set<String> platforms = entries.stream().map(e -> e.platform).collect(Collectors.toSet());

        if (platforms.size() == 1) {
            flags.add(flag("Single-Platform Dependency",
                "All activity is on " + platformLabel(platforms.iterator().next()) +
                " — no cross-platform amplification. Reach is capped within one network.", "MEDIUM"));
        }
        if (!burstRegions.isEmpty()) {
            int maxBurst = burstRegions.stream().mapToInt(b -> b.postCount).max().orElse(0);
            if (maxBurst > 15) {
                flags.add(flag("Burst Saturation Risk",
                    "Single burst of " + maxBurst + " posts detected. Over-targeting during these windows " +
                    "may drown branded content in noise or fatigue the audience.", "HIGH"));
            }
        }
        if (branchingRatio < 0.3) {
            flags.add(flag("Low Amplification Return",
                "Branching ratio " + Math.round(branchingRatio * 100.0) / 100.0 +
                " — each targeted post yields minimal organic follow-through. " +
                "Direct conversion campaigns will outperform viral strategies.", "HIGH"));
        }
        long observationDays = (entries.get(entries.size() - 1).timestamp.getTime()
                - entries.get(0).timestamp.getTime()) / 86_400_000L;
        if (observationDays < 7) {
            flags.add(flag("Thin Data Window",
                "Only " + observationDays + " day(s) of history. Hawkes parameters may not be stable — " +
                "validate again after 2–3 weeks of additional data.", "LOW"));
        }
        return flags;
    }

    private List<Map<String, Object>> buildOpportunities(
            List<HawkesAuditService.AuditEntry> entries,
            double branchingRatio, List<BurstRegion> burstRegions) {

        List<Map<String, Object>> opps = new ArrayList<>();

        Map<String, Long> allTones = entries.stream()
                .collect(Collectors.groupingBy(HawkesAuditService.AuditEntry::tone, Collectors.counting()));
        String dominantTone = allTones.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("neutral");

        if (branchingRatio >= 0.7) {
            opps.add(opportunity("High-Velocity Amplifier",
                "Branching ratio " + Math.round(branchingRatio * 100.0) / 100.0 +
                " means this user is close to a critical spreading threshold. " +
                "A single well-timed interaction can cascade into a burst of organic posts."));
        }
        if ("negative".equals(dominantTone) && !burstRegions.isEmpty()) {
            opps.add(opportunity("Unmet Needs Advocate",
                "User posts negatively at high velocity about specific topics — they are an active " +
                "critic looking for solutions. A brand that addresses their pain point publicly " +
                "can earn an authentic, high-energy advocate."));
        }
        if (!burstRegions.isEmpty()) {
            String topKeyword = burstRegions.stream()
                    .filter(b -> b.triggerKeyword != null)
                    .collect(Collectors.groupingBy(b -> b.triggerKeyword, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
            if (topKeyword != null) {
                opps.add(opportunity("Keyword Anchor Window",
                    "Posts about '" + topKeyword + "' reliably trigger burst activity. " +
                    "Launching campaign content coinciding with " + topKeyword +
                    "-related news events will maximise organic reach from this user."));
            }
        }
        return opps;
    }

    // -------------------------------------------------------------------------
    // Burst region extraction
    // -------------------------------------------------------------------------

    static class BurstRegion {
        java.util.Date startTime;
        java.util.Date endTime;
        double         durationMin;
        int            postCount;
        double         peakSpike;
        String         triggerKeyword;
    }

    List<BurstRegion> extractBurstRegions(List<HawkesAuditService.AuditEntry> entries) {
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

    // -------------------------------------------------------------------------
    // Classifiers and text generators
    // -------------------------------------------------------------------------

    private String influenceTier(double br) {
        if (br >= 0.8) return "Viral Node";
        if (br >= 0.6) return "Amplifier";
        if (br >= 0.3) return "Participant";
        return "Observer";
    }

    private String influenceTierDescription(double br, double alpha) {
        if (br >= 0.8) return String.format(
            "Branching ratio %.2f — near-supercritical Hawkes process. Almost every post self-excites " +
            "into additional activity. Content posted to or by this user has a high chance of cascading " +
            "organically through their network.", br);
        if (br >= 0.6) return String.format(
            "Branching ratio %.2f — reliable content amplifier. Each post is expected to generate " +
            "~%.1f organic follow-ups. Strong candidate for seeding campaigns.", br, br);
        if (br >= 0.3) return String.format(
            "Branching ratio %.2f — moderate engagement. User participates actively but content " +
            "spreads at a measured pace. Best suited for sustained awareness campaigns.", br);
        return String.format(
            "Branching ratio %.2f — low self-amplification. User posts independently without strong " +
            "self-exciting patterns. Direct-response campaigns will outperform viral strategies.", br);
    }

    private String postingStyle(int distinctBursts, List<BurstRegion> regions) {
        if (regions.isEmpty()) return "Steady Poster";
        int maxBurst = regions.stream().mapToInt(b -> b.postCount).max().orElse(0);
        if (maxBurst >= 10) return "Power Burst Poster";
        if (distinctBursts >= 2) return "Burst Poster";
        return "Reactive Poster";
    }

    private String postingStyleDescription(String style, int bursts, List<BurstRegion> regions) {
        switch (style) {
            case "Power Burst Poster":
                int max = regions.stream().mapToInt(b -> b.postCount).max().orElse(0);
                return String.format(
                    "%d burst event(s) detected, largest containing %d posts. " +
                    "This user reacts to specific triggers with extremely high-velocity posting. " +
                    "Content they engage with is amplified in rapid, concentrated waves.", bursts, max);
            case "Burst Poster":
                return String.format(
                    "%d burst event(s) detected. User posts in focused storms around specific keywords. " +
                    "Activity is episodic but intense when triggered.", bursts);
            case "Reactive Poster":
                return "Single burst detected. User responds to specific triggers with a short, " +
                    "intense flurry before returning to baseline — highly responsive to timely content.";
            default:
                return "Posts at a consistent pace without clustering. Reliable, predictable engagement " +
                    "pattern — well suited for drip campaigns and evergreen content strategies.";
        }
    }

    private String excitationProfile(double avgSpike, long burstsTriggered, String tone) {
        if (burstsTriggered > 0 && avgSpike > 0.1)
            return String.format(
                "HIGH — keyword consistently triggers burst activity (%d burst(s)). " +
                "Dominant tone: %s. Highly reactive to this topic.", burstsTriggered, tone);
        if (avgSpike > 0.05)
            return String.format("MEDIUM — keyword raises activity but rarely produces full bursts. Tone: %s.", tone);
        return String.format("LOW — mentions are scattered with no strong excitation pattern. Tone: %s.", tone);
    }

    private String campaignType(String style, double br) {
        if (style.contains("Burst") && br >= 0.7)
            return "Reactive Amplification Campaign — time a seeding post to coincide with the user's " +
                "known burst windows to trigger a cascade of organic follow-up content.";
        if (style.equals("Steady Poster") && br >= 0.5)
            return "Sustained Awareness Campaign — leverage consistent posting cadence for drip content " +
                "delivery over multiple weeks.";
        return "Targeted Engagement Campaign — direct, personalised outreach will outperform " +
            "viral seeding strategies for this user profile.";
    }

    private String amplificationPotential(double br) {
        if (br >= 0.8) return "HIGH — near-supercritical; a single interaction can trigger a content cascade";
        if (br >= 0.5) return "MEDIUM — reliable amplifier; expect ~" + Math.round(br * 10.0) / 10.0 + "x organic reach per interaction";
        if (br >= 0.3) return "LOW-MEDIUM — moderate spread; best paired with paid amplification";
        return "LOW — minimal self-amplification; focus on direct conversion";
    }

    private String contentStrategy(String topKeyword, String tone, String style) {
        String kwStr = topKeyword != null ? "'" + topKeyword + "'" : "their core interest area";
        switch (tone) {
            case "negative":
                return "User posts critically about " + kwStr + ". Lead with solutions, improvements, or " +
                    "resolutions to the pain points they repeatedly raise. Brands that visibly address " +
                    "these issues earn authentic, high-energy endorsements from this type of user.";
            case "positive":
                return "User champions " + kwStr + " enthusiastically. Create content that celebrates and " +
                    "deepens their connection to this topic — behind-the-scenes, exclusive access, or " +
                    "community recognition content works well.";
            default:
                return "User engages informally with " + kwStr + ". Use informational or conversational " +
                    "content to establish brand relevance before moving to promotional messaging.";
        }
    }

    private String audienceType(String tone, double br) {
        if ("negative".equals(tone) && br >= 0.7)
            return "Critical Power Influencer — vocal critic with high amplification potential. High risk, high reward.";
        if ("negative".equals(tone))
            return "Active Critic — expresses dissatisfaction publicly. Engage carefully to convert to advocate.";
        if ("positive".equals(tone) && br >= 0.7)
            return "Movie Buff — enthusiastic, high-reach advocate. Priority for ambassador programs.";
        if ("positive".equals(tone))
            return "Positive Engager — supportive and consistent. Good candidate for loyalty programs.";
        return "Neutral Informer — shares factual content without strong emotional bias. Useful for awareness campaigns.";
    }

    private String actionableAdvice(String keyword, String tone, String style,
                                    double br, int peakHour, String platform) {
        String kwStr = keyword != null ? " about '" + keyword + "'" : "";
        String window = String.format("%02d:00–%02d:00 " + TZ, peakHour, peakHour + 1);
        return String.format(
            "1. POST TIMING: Publish your content during the %s window when this user is most " +
            "active%s — excitation spikes show they respond rapidly in this window. " +
            "2. PLATFORM: All activity is on %s — focus your campaign there. " +
            "3. TONE: %s " +
            "4. TRIGGER: If possible, tie your message to '%s' — it is the proven engagement trigger for this user.",
            window, kwStr, platformLabel(platform),
            "negative".equals(tone)
                ? "User is a vocal critic. Respond to their complaints publicly and helpfully before launching promotional content."
                : "positive".equals(tone)
                    ? "User is an enthusiast. Offer exclusives or early access to reward their loyalty."
                    : "Keep messaging informational and value-first.",
            keyword != null ? keyword : "their core topic");
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
