package com.lit.fire.flame;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Renders the entity intelligence report ({@link EntityReportController#buildReport})
 * into a polished, sales-oriented PDF intended to be handed to prospective customers.
 *
 * <p>The renderer is a pure consumer of the report {@code Map} — it adds no new data and
 * performs no queries, so the JSON and PDF representations never drift apart. Layout is
 * deliberately marketing-grade: a branded cover band, headline metric cards, an executive
 * summary, and opportunity/recommendation sections framed to make the product's value
 * obvious at a glance.
 */
@Component
public class EntityReportPdfRenderer {

    // --- Brand palette --------------------------------------------------------
    private static final Color INK        = new Color(24, 27, 47);    // near-black headline ink
    private static final Color BRAND      = new Color(63, 47, 138);   // deep indigo (brand)
    private static final Color ACCENT     = new Color(99, 102, 241);  // vivid indigo accent
    private static final Color ACCENT_SOFT= new Color(238, 239, 252); // accent tint for cards
    private static final Color BODY       = new Color(45, 50, 66);    // body text
    private static final Color MUTED      = new Color(120, 126, 144); // captions / labels
    private static final Color HAIRLINE   = new Color(224, 226, 236); // table borders
    private static final Color ROW_ALT    = new Color(247, 248, 252); // zebra rows
    private static final Color POSITIVE    = new Color(22, 163, 110);
    private static final Color NEUTRAL     = new Color(148, 156, 176);
    private static final Color NEGATIVE    = new Color(225, 70, 70);
    private static final Color GREEN_SOFT  = new Color(232, 247, 240);
    private static final Color AMBER       = new Color(214, 158, 46);
    private static final Color AMBER_SOFT  = new Color(252, 246, 232);
    private static final Color RED_SOFT    = new Color(252, 235, 235);

    // --- Fonts ----------------------------------------------------------------
    private static Font f(String name, float size, Color c) { return FontFactory.getFont(name, size, c); }
    private static final String SANS  = FontFactory.HELVETICA;
    private static final String SANSB = FontFactory.HELVETICA_BOLD;
    private static final String SANSI = FontFactory.HELVETICA_OBLIQUE;

    private static final Font EYEBROW   = f(SANSB, 9.5f, new Color(196, 199, 240));
    private static final Font TITLE      = f(SANSB, 27f, Color.WHITE);
    private static final Font SUBTITLE   = f(SANS, 11f, new Color(213, 215, 245));
    private static final Font BADGE      = f(SANSB, 10.5f, BRAND);
    private static final Font SECTION    = f(SANSB, 14.5f, BRAND);
    private static final Font SECTION_NO = f(SANSB, 14.5f, new Color(205, 208, 230));
    private static final Font LEAD       = f(SANS, 11.5f, BODY);
    private static final Font BODY_F     = f(SANS, 10f, BODY);
    private static final Font BODY_B     = f(SANSB, 10f, BODY);
    private static final Font SMALL      = f(SANS, 9f, MUTED);
    private static final Font SMALL_B    = f(SANSB, 8.5f, MUTED);
    private static final Font METRIC     = f(SANSB, 23f, BRAND);
    private static final Font METRIC_LBL = f(SANSB, 8f, MUTED);
    private static final Font TH         = f(SANSB, 8.5f, Color.WHITE);
    private static final Font TD         = f(SANS, 9.5f, BODY);
    private static final Font TD_B       = f(SANSB, 9.5f, INK);

    /**
     * Render the given report map to PDF bytes.
     *
     * @param report the map produced by the report builder (rich form expected — see
     *               {@link #isRenderable(Map)})
     */
    public byte[] render(Map<String, Object> report) {
        Document doc = new Document(PageSize.A4, 40, 40, 46, 56);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer());
            doc.open();

            Map<String, Object> entity     = asMap(report.get("entityProfile"));
            Map<String, Object> conv        = asMap(report.get("conversationProfile"));
            Map<String, Object> sentiment   = asMap(report.get("audienceSentiment"));
            Map<String, Object> channels    = asMap(report.get("channelStrategy"));
            Map<String, Object> recs        = asMap(report.get("marketingRecommendations"));

            coverBand(doc, entity, str(report, "generatedAt"));
            metricCards(doc, entity, conv);
            executiveSummary(doc, entity, conv, sentiment, channels, recs);

            entityProfile(doc, entity);
            viralityDynamics(doc, conv);
            topicIntelligence(doc, asList(report.get("topicIntelligence")));
            audienceSentiment(doc, sentiment);
            channelStrategy(doc, channels);
            topAdvocates(doc, asList(report.get("topAdvocates")));
            opportunities(doc, asList(report.get("opportunityFlags")));
            recommendedPlay(doc, recs);
            considerations(doc, asList(report.get("redFlags")));

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render entity report PDF", e);
        }
    }

    /** True when the report contains the rich section payload (i.e. an entity was found with history). */
    public boolean isRenderable(Map<String, Object> report) {
        return report != null && report.get("entityProfile") instanceof Map;
    }

    // =========================================================================
    // Sections
    // =========================================================================

    /** Full-width branded header band with entity name, type, keywords and a virality badge. */
    private void coverBand(Document doc, Map<String, Object> entity, String generatedAt) throws DocumentException {
        PdfPTable band = fullWidth(1);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(BRAND);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(22f);
        cell.setPaddingBottom(24f);

        cell.addElement(new Paragraph("CONVERSATION INTELLIGENCE REPORT", EYEBROW));

        Paragraph name = new Paragraph(str(entity, "name", "Unknown Entity"), TITLE);
        name.setSpacingBefore(6f);
        cell.addElement(name);

        String type = str(entity, "type", "");
        List<Object> kws = asList(entity.get("trackedKeywords"));
        StringBuilder sub = new StringBuilder();
        if (!type.isEmpty()) sub.append(cap(type));
        if (!kws.isEmpty()) {
            if (sub.length() > 0) sub.append("   •   ");
            sub.append("Tracking: ").append(joinTrim(kws, 6));
        }
        Paragraph subP = new Paragraph(sub.toString(), SUBTITLE);
        subP.setSpacingBefore(7f);
        cell.addElement(subP);

        band.addCell(cell);
        doc.add(band);

        // Virality badge + generated-at strip directly under the band.
        PdfPTable strip = fullWidth(new float[]{ 60, 40 });
        strip.setSpacingBefore(0f);

        PdfPCell badge = new PdfPCell();
        badge.setBorder(Rectangle.NO_BORDER);
        badge.setPaddingTop(8f);
        Phrase bp = new Phrase();
        bp.add(new com.lowagie.text.Chunk("  " + str(entity, "viralityTier", "—").toUpperCase() + "  ", BADGE));
        PdfPTable badgeWrap = new PdfPTable(1);
        badgeWrap.setHorizontalAlignment(Element.ALIGN_LEFT);
        try { badgeWrap.setTotalWidth(150f); badgeWrap.setLockedWidth(true); } catch (Exception ignore) {}
        PdfPCell bc = new PdfPCell(new Phrase(str(entity, "viralityTier", "—").toUpperCase(), BADGE));
        bc.setBackgroundColor(ACCENT_SOFT);
        bc.setBorder(Rectangle.NO_BORDER);
        bc.setHorizontalAlignment(Element.ALIGN_CENTER);
        bc.setPadding(6f);
        badgeWrap.addCell(bc);
        badge.addElement(badgeWrap);
        strip.addCell(badge);

        PdfPCell when = new PdfPCell(new Phrase("Generated " + safe(generatedAt), SMALL));
        when.setBorder(Rectangle.NO_BORDER);
        when.setHorizontalAlignment(Element.ALIGN_RIGHT);
        when.setVerticalAlignment(Element.ALIGN_BOTTOM);
        when.setPaddingTop(14f);
        strip.addCell(when);
        doc.add(strip);
    }

    /** Headline metric cards — the "wow" numbers a prospect sees first. */
    private void metricCards(Document doc, Map<String, Object> entity, Map<String, Object> conv) throws DocumentException {
        double br = num(conv.get("branchingRatio"));
        PdfPTable cards = fullWidth(new float[]{ 1, 1, 1, 1 });
        cards.setSpacingBefore(16f);
        cards.addCell(card(fmtInt(entity.get("totalPosts")), "POSTS ANALYZED"));
        cards.addCell(card(fmtInt(entity.get("audienceSize")), "DISTINCT AUTHORS"));
        cards.addCell(card(String.format("%.2f×", br), "REACH PER POST"));
        cards.addCell(card(str(entity, "viralityTier", "—"), "VIRALITY TIER"));
        doc.add(cards);
    }

    private PdfPCell card(String big, String label) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(ACCENT_SOFT);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(12f);
        c.setPaddingBottom(13f);
        Paragraph n = new Paragraph(safe(big), METRIC);
        n.setLeading(24f);
        c.addElement(n);
        Paragraph l = new Paragraph(label, METRIC_LBL);
        l.setSpacingBefore(3f);
        c.addElement(l);
        return c;
    }

    private void executiveSummary(Document doc, Map<String, Object> entity, Map<String, Object> conv,
                                  Map<String, Object> sentiment, Map<String, Object> channels,
                                  Map<String, Object> recs) throws DocumentException {
        String name = str(entity, "name", "This entity");
        String tier = str(entity, "viralityTier", "active").toLowerCase();
        double br   = num(conv.get("branchingRatio"));
        String sLabel = str(sentiment, "sentimentLabel", "mixed").toLowerCase();
        String topChannel = str(channels, "topChannel", "social");
        List<Object> plats = asList(entity.get("activePlatforms"));

        StringBuilder s = new StringBuilder();
        s.append(name).append(" is a ").append(tier).append(" conversation spanning ")
         .append(plats.isEmpty() ? "social media" : plats.size() + " platform" + (plats.size() == 1 ? "" : "s"))
         .append(", with ").append(fmtInt(entity.get("totalPosts"))).append(" posts from ")
         .append(fmtInt(entity.get("audienceSize"))).append(" distinct authors. ")
         .append("Every post seeded into this topic drives roughly ").append(String.format("%.2f", br))
         .append(" organic follow-ups, and audience sentiment is ").append(sLabel).append(". ")
         .append(topChannel).append(" leads the conversation");
        String when = str(recs, "bestTimeToEngage", "");
        if (!when.isEmpty()) s.append(" — engage during ").append(when.replace(" — peak conversation window", ""));
        s.append(".");

        sectionHeader(doc, "01", "The Opportunity");
        Paragraph lead = new Paragraph(s.toString(), LEAD);
        lead.setLeading(17f);
        lead.setSpacingAfter(4f);
        doc.add(lead);
    }

    private void entityProfile(Document doc, Map<String, Object> e) throws DocumentException {
        sectionHeader(doc, "02", "Entity Profile");
        PdfPTable t = kvTable();
        kv(t, "Name", str(e, "name", "—"));
        kv(t, "Type", cap(str(e, "type", "—")));
        kv(t, "Tracked Keywords", joinTrim(asList(e.get("trackedKeywords")), 12));
        kv(t, "Active Platforms", joinTrim(asList(e.get("activePlatforms")), 8));
        kv(t, "Posts Analyzed", fmtInt(e.get("totalPosts")));
        kv(t, "Audience Size", fmtInt(e.get("audienceSize")) + " distinct authors");
        kv(t, "Observation Window", str(e, "firstSeen", "—") + "  →  " + str(e, "lastSeen", "—"));
        kv(t, "Span", num(e.get("observationSpanDays")) + " days  (" + num(e.get("averagePostsPerDay")) + " posts/day)");
        doc.add(t);
        calloutNote(doc, str(e, "viralityTierExplained", ""));
    }

    private void viralityDynamics(Document doc, Map<String, Object> c) throws DocumentException {
        sectionHeader(doc, "03", "Virality & Conversation Dynamics");
        Paragraph p = new Paragraph(str(c, "amplificationExplained", ""), BODY_F);
        p.setLeading(15f);
        p.setSpacingAfter(8f);
        doc.add(p);

        PdfPTable t = kvTable();
        kv(t, "Branching Ratio", String.valueOf(num(c.get("branchingRatio"))));
        kv(t, "Distinct Burst Events", fmtInt(c.get("distinctBurstEvents")));
        kv(t, "Most Active Day", str(c, "mostActiveDayOfWeek", "—"));
        kv(t, "Peak Activity Windows", joinTrim(asList(c.get("peakActivityWindows")), 5));
        doc.add(t);

        Map<String, Object> burst = asMap(c.get("longestBurst"));
        if (!burst.isEmpty()) {
            calloutNote(doc, "Largest burst observed: " + str(burst, "readableDescription", ""));
        }
    }

    private void topicIntelligence(Document doc, List<Object> topics) throws DocumentException {
        if (topics.isEmpty()) return;
        sectionHeader(doc, "04", "Topic Intelligence");
        PdfPTable t = fullWidth(new float[]{ 26, 13, 12, 16, 33 });
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        headerRow(t, "KEYWORD", "MENTIONS", "BURSTS", "DOMINANT TONE", "EXCITATION PROFILE");
        int i = 0;
        for (Object o : topics) {
            Map<String, Object> tp = asMap(o);
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            td(t, str(tp, "keyword", "—"), bg, true, Element.ALIGN_LEFT);
            td(t, fmtInt(tp.get("totalMentions")), bg, false, Element.ALIGN_CENTER);
            td(t, fmtInt(tp.get("burstsTriggered")), bg, false, Element.ALIGN_CENTER);
            tdTone(t, str(tp, "dominantTone", "neutral"), bg);
            td(t, str(tp, "excitationProfile", "—"), bg, false, Element.ALIGN_LEFT);
        }
        doc.add(t);
    }

    private void audienceSentiment(Document doc, Map<String, Object> s) throws DocumentException {
        sectionHeader(doc, "05", "Audience Sentiment");
        Map<String, Object> tones = asMap(s.get("toneBreakdown"));
        long pos = lng(tones.get("positive"));
        long neu = lng(tones.get("neutral"));
        long neg = lng(tones.get("negative"));
        long total = pos + neu + neg;

        Paragraph head = new Paragraph();
        head.add(new com.lowagie.text.Chunk(str(s, "sentimentLabel", "Mixed"), f(SANSB, 12.5f, sentimentColor(num(s.get("netSentiment"))))));
        head.add(new com.lowagie.text.Chunk("    (net sentiment " + num(s.get("netSentiment")) + ")", SMALL));
        head.setSpacingAfter(8f);
        doc.add(head);

        if (total > 0) {
            doc.add(stackedBar(
                new long[]{ pos, neu, neg },
                new Color[]{ POSITIVE, NEUTRAL, NEGATIVE },
                new String[]{ "Positive", "Neutral", "Negative" }, total));
        }
    }

    private void channelStrategy(Document doc, Map<String, Object> ch) throws DocumentException {
        sectionHeader(doc, "06", "Channel Strategy");
        Paragraph p = new Paragraph(str(ch, "headline", ""), BODY_B);
        p.setSpacingAfter(8f);
        doc.add(p);

        List<Object> channels = asList(ch.get("channels"));
        if (channels.isEmpty()) return;
        PdfPTable t = fullWidth(new float[]{ 30, 16, 54 });
        headerRow(t, "PLATFORM", "POSTS", "SHARE OF CONVERSATION");
        int i = 0;
        for (Object o : channels) {
            Map<String, Object> c = asMap(o);
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            td(t, str(c, "platform", "—"), bg, true, Element.ALIGN_LEFT);
            td(t, fmtInt(c.get("postCount")), bg, false, Element.ALIGN_CENTER);
            t.addCell(shareBarCell(num(c.get("share")), bg));
        }
        doc.add(t);
    }

    private void topAdvocates(Document doc, List<Object> advocates) throws DocumentException {
        if (advocates.isEmpty()) return;
        sectionHeader(doc, "07", "Top Advocates");
        Paragraph p = new Paragraph("The highest-amplification voices already driving this conversation — natural seeding targets.", SMALL);
        p.setSpacingAfter(7f);
        doc.add(p);

        PdfPTable t = fullWidth(new float[]{ 34, 24, 14, 14, 14 });
        headerRow(t, "AUTHOR", "SEGMENT", "POSTS", "ENGAGEMENT", "INFLUENCE");
        int i = 0;
        int shown = 0;
        for (Object o : advocates) {
            if (shown++ >= 8) break;
            Map<String, Object> a = asMap(o);
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            td(t, advocateHandle(a), bg, true, Element.ALIGN_LEFT);
            td(t, str(a, "tribe_label", "—"), bg, false, Element.ALIGN_LEFT);
            td(t, fmtInt(a.get("post_count")), bg, false, Element.ALIGN_CENTER);
            td(t, fmtInt(a.get("total_engagement")), bg, false, Element.ALIGN_CENTER);
            td(t, String.format("%.2f", num(a.get("hawkes_alpha"))), bg, false, Element.ALIGN_CENTER);
        }
        doc.add(t);
    }

    private void opportunities(Document doc, List<Object> opps) throws DocumentException {
        if (opps.isEmpty()) return;
        sectionHeader(doc, "08", "Why Now — Opportunities");
        for (Object o : opps) {
            Map<String, Object> op = asMap(o);
            doc.add(highlightCard(str(op, "opportunity", "Opportunity"), str(op, "detail", ""),
                    GREEN_SOFT, POSITIVE));
        }
    }

    private void recommendedPlay(Document doc, Map<String, Object> r) throws DocumentException {
        if (r.isEmpty()) return;
        sectionHeader(doc, "09", "Recommended Play");
        PdfPTable t = kvTable();
        kv(t, "Primary Channel", str(r, "primaryChannel", "—"));
        kv(t, "Best Time to Engage", str(r, "bestTimeToEngage", "—"));
        kv(t, "Campaign Type", str(r, "campaignType", "—"));
        kv(t, "Amplification Potential", str(r, "amplificationPotential", "—"));
        kv(t, "Estimated Reach", str(r, "estimatedReachMultiplier", "—"));
        kv(t, "Addressable Audience", str(r, "addressableAudience", "—"));
        kv(t, "Content Strategy", str(r, "contentStrategy", "—"));
        doc.add(t);

        String advice = str(r, "actionableAdvice", "");
        if (!advice.isEmpty()) {
            PdfPTable box = fullWidth(1);
            box.setSpacingBefore(8f);
            PdfPCell c = new PdfPCell();
            c.setBackgroundColor(BRAND);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(14f);
            Paragraph h = new Paragraph("YOUR ACTION PLAN", f(SANSB, 9f, new Color(196, 199, 240)));
            h.setSpacingAfter(5f);
            c.addElement(h);
            Paragraph body = new Paragraph(advice, f(SANS, 10f, Color.WHITE));
            body.setLeading(15f);
            c.addElement(body);
            box.addCell(c);
            doc.add(box);
        }
    }

    private void considerations(Document doc, List<Object> flags) throws DocumentException {
        if (flags.isEmpty()) return;
        sectionHeader(doc, "10", "Considerations");
        for (Object o : flags) {
            Map<String, Object> fl = asMap(o);
            String sev = str(fl, "severity", "LOW").toUpperCase();
            Color soft = "HIGH".equals(sev) ? RED_SOFT : "MEDIUM".equals(sev) ? AMBER_SOFT : ROW_ALT;
            Color bar  = "HIGH".equals(sev) ? NEGATIVE : "MEDIUM".equals(sev) ? AMBER : NEUTRAL;
            doc.add(highlightCard(str(fl, "flag", "Note") + "   [" + sev + "]", str(fl, "detail", ""), soft, bar));
        }
    }

    // =========================================================================
    // Reusable layout helpers
    // =========================================================================

    private void sectionHeader(Document doc, String no, String title) throws DocumentException {
        PdfPTable t = fullWidth(new float[]{ 6, 94 });
        t.setSpacingBefore(20f);
        t.setSpacingAfter(8f);
        PdfPCell numCell = new PdfPCell(new Phrase(no, SECTION_NO));
        numCell.setBorder(Rectangle.NO_BORDER);
        numCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        t.addCell(numCell);

        PdfPCell tc = new PdfPCell();
        tc.setBorder(Rectangle.BOTTOM);
        tc.setBorderColorBottom(ACCENT);
        tc.setBorderWidthBottom(1.4f);
        tc.setPaddingBottom(5f);
        tc.addElement(new Paragraph(title, SECTION));
        t.addCell(tc);
        doc.add(t);
    }

    private void calloutNote(Document doc, String text) throws DocumentException {
        if (text == null || text.isEmpty()) return;
        PdfPTable t = fullWidth(new float[]{ 1.2f, 98.8f });
        t.setSpacingBefore(8f);
        PdfPCell stripe = new PdfPCell();
        stripe.setBackgroundColor(ACCENT);
        stripe.setBorder(Rectangle.NO_BORDER);
        t.addCell(stripe);
        PdfPCell c = new PdfPCell(new Phrase(text, f(SANSI, 9.5f, BODY)));
        c.setBackgroundColor(ACCENT_SOFT);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(10f);
        t.addCell(c);
        doc.add(t);
    }

    private PdfPTable highlightCard(String title, String detail, Color bg, Color stripeColor) {
        PdfPTable t = fullWidth(new float[]{ 1.4f, 98.6f });
        t.setSpacingBefore(7f);
        PdfPCell stripe = new PdfPCell();
        stripe.setBackgroundColor(stripeColor);
        stripe.setBorder(Rectangle.NO_BORDER);
        t.addCell(stripe);

        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(bg);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(11f);
        Paragraph h = new Paragraph(title, f(SANSB, 10.5f, INK));
        h.setSpacingAfter(3f);
        c.addElement(h);
        Paragraph d = new Paragraph(detail, BODY_F);
        d.setLeading(14f);
        c.addElement(d);
        t.addCell(c);
        return t;
    }

    /** Two-column key/value table used for profile-style sections. */
    private PdfPTable kvTable() {
        PdfPTable t = fullWidth(new float[]{ 30, 70 });
        t.setSpacingBefore(2f);
        return t;
    }

    private void kv(PdfPTable t, String k, String v) {
        PdfPCell kc = new PdfPCell(new Phrase(k, SMALL_B));
        kc.setBorder(Rectangle.BOTTOM);
        kc.setBorderColorBottom(HAIRLINE);
        kc.setPadding(7f);
        kc.setVerticalAlignment(Element.ALIGN_TOP);
        t.addCell(kc);

        PdfPCell vc = new PdfPCell(new Phrase(safe(v), BODY_F));
        vc.setBorder(Rectangle.BOTTOM);
        vc.setBorderColorBottom(HAIRLINE);
        vc.setPadding(7f);
        t.addCell(vc);
    }

    private void headerRow(PdfPTable t, String... labels) {
        for (String l : labels) {
            PdfPCell c = new PdfPCell(new Phrase(l, TH));
            c.setBackgroundColor(BRAND);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(7f);
            c.setHorizontalAlignment(Element.ALIGN_LEFT);
            t.addCell(c);
        }
    }

    private void td(PdfPTable t, String text, Color bg, boolean bold, int align) {
        PdfPCell c = new PdfPCell(new Phrase(safe(text), bold ? TD_B : TD));
        c.setBackgroundColor(bg);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(7f);
        c.setHorizontalAlignment(align);
        t.addCell(c);
    }

    private void tdTone(PdfPTable t, String tone, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(cap(tone), f(SANSB, 9.5f, toneColor(tone))));
        c.setBackgroundColor(bg);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(7f);
        t.addCell(c);
    }

    /** Horizontal stacked proportion bar (used for sentiment split). */
    private PdfPTable stackedBar(long[] counts, Color[] colors, String[] labels, long total) {
        // Build widths only for non-zero segments to avoid zero-width cells.
        int n = 0;
        for (long c : counts) if (c > 0) n++;
        float[] widths = new float[n];
        int idx = 0;
        for (long c : counts) if (c > 0) widths[idx++] = c;
        PdfPTable bar = fullWidth(widths);
        bar.setSpacingBefore(2f);
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            int pct = (int) Math.round(100.0 * counts[i] / total);
            PdfPCell c = new PdfPCell(new Phrase(labels[i] + "  " + pct + "%", f(SANSB, 8.5f, Color.WHITE)));
            c.setBackgroundColor(colors[i]);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(7f);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setMinimumHeight(22f);
            bar.addCell(c);
        }
        return bar;
    }

    /** A table cell containing a proportional share bar for the given fraction (0..1). */
    private PdfPCell shareBarCell(double frac, Color bg) {
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        int pct = (int) Math.round(frac * 100);
        PdfPCell outer = new PdfPCell();
        outer.setBackgroundColor(bg);
        outer.setBorder(Rectangle.NO_BORDER);
        outer.setPadding(7f);

        PdfPTable bar = new PdfPTable(new float[]{ 78, 22 });
        bar.setWidthPercentage(100);
        // Inner two-column track: filled portion + remainder.
        float fill = (float) Math.max(0.02, Math.min(1.0, frac));
        float rest = Math.max(0.0001f, 1f - fill);
        PdfPTable track = new PdfPTable(new float[]{ fill, rest });
        track.setWidthPercentage(100);
        PdfPCell filled = new PdfPCell(new Phrase(" ", SMALL));
        filled.setBackgroundColor(ACCENT);
        filled.setBorder(Rectangle.NO_BORDER);
        filled.setFixedHeight(11f);
        track.addCell(filled);
        if (frac < 0.999) {
            PdfPCell empty = new PdfPCell(new Phrase(" ", SMALL));
            empty.setBackgroundColor(HAIRLINE);
            empty.setBorder(Rectangle.NO_BORDER);
            empty.setFixedHeight(11f);
            track.addCell(empty);
        } else {
            PdfPCell empty = new PdfPCell(new Phrase(" ", SMALL));
            empty.setBackgroundColor(ACCENT);
            empty.setBorder(Rectangle.NO_BORDER);
            empty.setFixedHeight(11f);
            track.addCell(empty);
        }
        PdfPCell trackCell = new PdfPCell(track);
        trackCell.setBorder(Rectangle.NO_BORDER);
        trackCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        bar.addCell(trackCell);

        PdfPCell pctCell = new PdfPCell(new Phrase(pct + "%", TD_B));
        pctCell.setBorder(Rectangle.NO_BORDER);
        pctCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        pctCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        bar.addCell(pctCell);

        outer.addElement(bar);
        return outer;
    }

    private PdfPTable fullWidth(int cols) {
        PdfPTable t = new PdfPTable(cols);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        return t;
    }

    private PdfPTable fullWidth(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        return t;
    }

    // =========================================================================
    // Footer
    // =========================================================================

    private static class Footer extends PdfPageEventHelper {
        private final Font brand = f(SANSB, 8f, BRAND);
        private final Font note  = f(SANS, 7.5f, MUTED);

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            PdfContentByte cb = writer.getDirectContent();
            float y = doc.bottom() - 18f;
            // hairline above the footer
            cb.setColorStroke(HAIRLINE);
            cb.setLineWidth(0.6f);
            cb.moveTo(doc.left(), y + 12f);
            cb.lineTo(doc.right(), y + 12f);
            cb.stroke();

            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("AuraMath — Conversation Intelligence", brand), doc.left(), y, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase("Confidential — prepared for prospective partners", note),
                    (doc.left() + doc.right()) / 2f, y, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), note), doc.right(), y, 0);
        }
    }

    // =========================================================================
    // Value helpers — defensive coercion over the loosely-typed report map
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : java.util.Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : java.util.Collections.emptyList();
    }

    private static String str(Map<String, Object> m, String k) { return str(m, k, ""); }

    private static String str(Map<String, Object> m, String k, String dflt) {
        Object v = m.get(k);
        return v == null ? dflt : String.valueOf(v);
    }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static long lng(Object o) { return (long) num(o); }

    private static String fmtInt(Object o) {
        return String.format("%,d", Math.round(num(o)));
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String joinTrim(List<Object> items, int max) {
        if (items == null || items.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(max, items.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.valueOf(items.get(i)));
        }
        if (items.size() > max) sb.append(", +").append(items.size() - max).append(" more");
        return sb.toString();
    }

    private static String advocateHandle(Map<String, Object> a) {
        Object handles = a.get("platform_handles");
        if (handles instanceof Map && !((Map<?, ?>) handles).isEmpty()) {
            Object first = ((Map<?, ?>) handles).values().iterator().next();
            String h = String.valueOf(first);
            if (!h.isEmpty() && !"null".equals(h)) return h;
        }
        String gid = str(a, "global_user_id", "");
        return gid.isEmpty() ? "—" : gid;
    }

    private static Color toneColor(String tone) {
        if (tone == null) return BODY;
        switch (tone.toLowerCase()) {
            case "positive": return POSITIVE;
            case "negative": return NEGATIVE;
            default:         return NEUTRAL;
        }
    }

    private static Color sentimentColor(double net) {
        if (net >= 0.05) return POSITIVE;
        if (net <= -0.05) return NEGATIVE;
        return NEUTRAL;
    }
}
