import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

/** Stateless, bottle-specific label presentation; never infers production success. */
final class BottleLabelVisuals {
    static final class State {
        final String phase;
        final boolean applied, floating, confirmed;
        final double coverage;

        State(String phase, boolean applied, boolean floating,
              boolean confirmed, double coverage) {
            this.phase = phase;
            this.applied = applied;
            this.floating = floating;
            this.confirmed = confirmed;
            this.coverage = coverage;
        }

        String evidenceText() {
            if (confirmed) return applied ? "WRAP: LIVE / CONFIRMED" :
                "WRAP: AWAITING LIVE LABEL EVIDENCE";
            return applied ? "WRAP: SYMBOLIC APPLICATION" :
                "WRAP: UNAPPLIED / SYMBOLIC";
        }
    }

    private BottleLabelVisuals() { }

    static State resolve(int module, boolean activeBottle, double progress,
                         boolean liveBottle, String stage, String operation) {
        boolean downstream = module > ABSVisualisationFlowModel.LABELLER;
        boolean labeller = module == ABSVisualisationFlowModel.LABELLER;
        double p = Math.max(0.0, Math.min(100.0, progress));
        String phase = !activeBottle || p < 20 ? "WAIT" : p < 40 ?
            "FEED LABEL" : p < 60 ? "APPLY" : p < 80 ? "VERIFY" : "HANDOFF";
        boolean evidence = labelledStage(stage) ||
            "LABEL_VERIFIED".equals(normalise(operation));
        if (!labeller && !downstream) return new State("WAIT", false, false,
            liveBottle, 0);
        if (liveBottle && evidence) return new State(
            downstream || p >= 80 ? "HANDOFF" : "VERIFY", true, false, true, 1);
        // An unrelated global DONE or order-size fallback cannot label this bottle.
        if (liveBottle) return new State(phase, false,
            labeller && activeBottle && p >= 20 && p < 60, true, 0);
        boolean applied = activeBottle && (downstream || p >= 60);
        double coverage = applied ? 1 : activeBottle && labeller && p >= 40 ?
            Math.min(1, (p - 40) / 20) : 0;
        return new State(phase, applied,
            labeller && activeBottle && p >= 20 && p < 60, false, coverage);
    }

    private static boolean labelledStage(String stage) {
        String value = normalise(stage);
        return "LABELLED".equals(value) || "UNLOADED".equals(value) ||
            "SORTED".equals(value) || "COMPLETE".equals(value);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().replace(' ', '_');
    }

    static void drawWrap(Graphics2D graphics, double centreX, double baseY,
                         int width, int height, BottleVisualGeometry geometry,
                         double coverage) {
        if (coverage <= 0) return;
        Rectangle2D band = geometry.labelBounds(centreX, baseY, width, height);
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            // Both the band and its reveal stay strictly inside the body.
            g.clip(band);
            g.clip(new Rectangle2D.Double(band.getX(), band.getY(),
                band.getWidth() * Math.min(1, coverage), band.getHeight()));
            g.setColor(new Color(234, 250, 253));
            g.fill(band);
            g.setColor(new Color(31, 131, 168));
            double trim = Math.max(1, band.getHeight() * 0.14);
            g.fill(new Rectangle2D.Double(band.getX(), band.getY(), band.getWidth(), trim));
            g.fill(new Rectangle2D.Double(band.getX(), band.getMaxY() - trim,
                band.getWidth(), trim));
            g.setColor(new Color(180, 222, 236));
            g.fill(new Rectangle2D.Double(band.getX(), band.getY(),
                Math.max(1, band.getWidth() * 0.09), band.getHeight()));
            g.setColor(new Color(24, 95, 129));
            g.setStroke(new BasicStroke(1));
            g.draw(band);
            if (band.getWidth() >= 16 && band.getHeight() >= 8) {
                int font = Math.max(5, Math.min(12,
                    (int)Math.min(band.getHeight() * 0.48, band.getWidth() / 3.8)));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, font));
                String text = band.getWidth() < 32 ? "P1" : "WATER";
                g.drawString(text, (float)(centreX - g.getFontMetrics().stringWidth(text) / 2.0),
                    (float)(band.getCenterY() + g.getFontMetrics().getAscent() / 2.0 - 1));
            }
        }
        finally { g.dispose(); }
    }
}
