import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

/**
 * Shared Java2D transport assemblies for the read-only M1 visualisation.
 *
 * Every horizontal helper treats {@code surfaceY} as the physical contact
 * line for the bottom of a bottle.  Structural depth is drawn below that
 * line, so S and L bottle profiles can share one stable production baseline.
 */
final class IndustrialTransportVisuals {
    static final Color FRAME_DARK = new Color(55, 70, 84);
    static final Color FRAME_MID = new Color(91, 108, 123);
    static final Color FRAME_LIGHT = new Color(206, 216, 225);
    static final Color SURFACE = new Color(232, 238, 243);
    static final Color HIGHLIGHT = new Color(250, 252, 254);
    static final Color BOLT = new Color(43, 57, 70);

    private IndustrialTransportVisuals() { }

    static void drawRollerConveyor(
        Graphics2D g2,
        double x,
        double surfaceY,
        double width,
        double depth,
        double preferredSpacing,
        boolean guideRail,
        boolean supports,
        Color activity
    ) {
        double safeWidth = Math.max(12.0, width);
        double safeDepth = Math.max(8.0, depth);
        double bottomY = surfaceY + safeDepth;
        Stroke original = g2.getStroke();

        g2.setColor(FRAME_DARK);
        g2.fillRoundRect(
            round(x), round(surfaceY), round(safeWidth), round(safeDepth),
            round(Math.min(8.0, safeDepth)), round(Math.min(8.0, safeDepth))
        );
        g2.setColor(FRAME_MID);
        g2.fillRect(
            round(x + 3.0), round(surfaceY + 3.0),
            round(safeWidth - 6.0), round(safeDepth - 6.0)
        );

        int count = rollerCount(safeWidth, preferredSpacing);
        double rollerDiameter = Math.max(4.0, safeDepth - 7.0);
        double rollerY = surfaceY + 3.5;
        for (int index = 0; index < count; index++) {
            double centreX = rollerCenterX(
                x, safeWidth, preferredSpacing, index
            );
            g2.setColor(FRAME_LIGHT);
            g2.fill(new Ellipse2D.Double(
                centreX - rollerDiameter / 2.0,
                rollerY,
                rollerDiameter,
                rollerDiameter
            ));
            g2.setColor(FRAME_DARK);
            g2.draw(new Ellipse2D.Double(
                centreX - rollerDiameter / 2.0,
                rollerY,
                rollerDiameter,
                rollerDiameter
            ));
            double hub = Math.max(1.5, rollerDiameter * 0.18);
            g2.setColor(BOLT);
            g2.fill(new Ellipse2D.Double(
                centreX - hub / 2.0,
                rollerY + rollerDiameter / 2.0 - hub / 2.0,
                hub,
                hub
            ));
        }

        g2.setColor(activity == null ? SURFACE : activity);
        g2.fillRect(
            round(x), round(surfaceY), round(safeWidth),
            Math.max(2, round(Math.min(3.0, safeDepth / 3.0)))
        );
        g2.setColor(HIGHLIGHT);
        g2.draw(new Line2D.Double(
            x + 2.0, surfaceY + 0.5,
            x + safeWidth - 2.0, surfaceY + 0.5
        ));
        g2.setColor(FRAME_DARK);
        g2.setStroke(new BasicStroke(1.4f));
        g2.draw(new Line2D.Double(x, bottomY, x + safeWidth, bottomY));
        drawEndPlate(g2, x, surfaceY, safeDepth);
        drawEndPlate(g2, x + safeWidth, surfaceY, safeDepth);

        if (guideRail) {
            drawGuideRail(g2, x + 4.0, surfaceY, safeWidth - 8.0,
                Math.max(12.0, safeDepth + 7.0));
        }
        if (supports) {
            drawSupports(g2, x, bottomY, safeWidth,
                Math.max(8.0, safeDepth * 0.75));
        }
        g2.setStroke(original);
    }

    static void drawBeltConveyor(
        Graphics2D g2,
        double x,
        double surfaceY,
        double width,
        double depth,
        boolean guideRail,
        boolean supports,
        Color activity,
        double motionOffset
    ) {
        double safeWidth = Math.max(16.0, width);
        double safeDepth = Math.max(9.0, depth);
        Stroke original = g2.getStroke();

        g2.setColor(FRAME_DARK);
        g2.fillRoundRect(
            round(x), round(surfaceY), round(safeWidth), round(safeDepth),
            round(safeDepth), round(safeDepth)
        );
        g2.setColor(new Color(74, 88, 102));
        g2.fillRoundRect(
            round(x + 3.0), round(surfaceY + 3.0),
            round(safeWidth - 6.0), round(safeDepth - 6.0),
            round(safeDepth - 4.0), round(safeDepth - 4.0)
        );
        double pulleyDiameter = Math.max(5.0, safeDepth - 5.0);
        drawPulley(g2, x + safeDepth / 2.0,
            surfaceY + safeDepth / 2.0, pulleyDiameter);
        drawPulley(g2, x + safeWidth - safeDepth / 2.0,
            surfaceY + safeDepth / 2.0, pulleyDiameter);

        g2.setColor(activity == null ? FRAME_LIGHT : activity);
        g2.fillRoundRect(
            round(x + 2.0), round(surfaceY), round(safeWidth - 4.0),
            Math.max(3, round(safeDepth * 0.27)), 3, 3
        );
        g2.setColor(HIGHLIGHT);
        g2.draw(new Line2D.Double(
            x + 5.0, surfaceY + 0.5,
            x + safeWidth - 5.0, surfaceY + 0.5
        ));

        g2.setColor(activity == null ? new Color(139, 152, 164) : activity);
        g2.setStroke(new BasicStroke(1.5f));
        double offset = positiveModulo(motionOffset, 22.0);
        for (double markerX = x - 18.0 + offset;
            markerX < x + safeWidth - 7.0;
            markerX += 22.0) {
            g2.draw(new Line2D.Double(
                markerX, surfaceY + safeDepth * 0.38,
                markerX + 7.0, surfaceY + safeDepth * 0.55
            ));
        }

        if (guideRail) {
            drawGuideRail(g2, x + 5.0, surfaceY, safeWidth - 10.0,
                Math.max(13.0, safeDepth + 8.0));
        }
        if (supports) {
            drawSupports(g2, x, surfaceY + safeDepth, safeWidth,
                Math.max(8.0, safeDepth * 0.75));
        }
        g2.setStroke(original);
    }

    static void drawMachineBed(
        Graphics2D g2,
        double x,
        double surfaceY,
        double width,
        double depth,
        Color activity
    ) {
        double safeWidth = Math.max(12.0, width);
        double safeDepth = Math.max(7.0, depth);
        Stroke original = g2.getStroke();
        g2.setColor(FRAME_DARK);
        g2.fillRoundRect(
            round(x), round(surfaceY), round(safeWidth), round(safeDepth),
            5, 5
        );
        g2.setColor(FRAME_MID);
        g2.fillRect(
            round(x + 3.0), round(surfaceY + 3.0),
            round(safeWidth - 6.0), round(safeDepth - 5.0)
        );
        g2.setColor(activity == null ? FRAME_LIGHT : activity);
        g2.fillRect(round(x + 2.0), round(surfaceY),
            round(safeWidth - 4.0), 3);
        g2.setColor(HIGHLIGHT);
        g2.draw(new Line2D.Double(
            x + 3.0, surfaceY + 0.5,
            x + safeWidth - 3.0, surfaceY + 0.5
        ));
        for (double boltX = x + 8.0;
            boltX <= x + safeWidth - 7.0;
            boltX += Math.max(18.0, safeWidth / 3.0)) {
            g2.setColor(BOLT);
            g2.fill(new Ellipse2D.Double(
                boltX - 1.5, surfaceY + safeDepth - 4.0, 3.0, 3.0
            ));
        }
        g2.setStroke(original);
    }

    static void drawSlopedRollerConveyor(
        Graphics2D g2,
        double startX,
        double startSurfaceY,
        double endX,
        double endSurfaceY,
        double depth,
        double preferredSpacing,
        Color activity
    ) {
        double safeDepth = Math.max(8.0, depth);
        Stroke original = g2.getStroke();
        Polygon frame = new Polygon();
        frame.addPoint(round(startX), round(startSurfaceY));
        frame.addPoint(round(endX), round(endSurfaceY));
        frame.addPoint(round(endX), round(endSurfaceY + safeDepth));
        frame.addPoint(round(startX), round(startSurfaceY + safeDepth));
        g2.setColor(FRAME_DARK);
        g2.fillPolygon(frame);

        g2.setColor(FRAME_MID);
        g2.setStroke(new BasicStroke((float)Math.max(3.0, safeDepth - 5.0),
            BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(
            startX + 2.0, startSurfaceY + safeDepth / 2.0,
            endX - 2.0, endSurfaceY + safeDepth / 2.0
        ));

        double length = Math.hypot(endX - startX, endSurfaceY - startSurfaceY);
        int count = rollerCount(length, preferredSpacing);
        double rollerDiameter = Math.max(4.0, safeDepth - 6.0);
        for (int index = 0; index < count; index++) {
            double ratio = count == 1 ? 0.5 : index / (double)(count - 1);
            double rollerX = startX + (endX - startX) * ratio;
            double rollerY = startSurfaceY +
                (endSurfaceY - startSurfaceY) * ratio + safeDepth / 2.0;
            g2.setColor(FRAME_LIGHT);
            g2.fill(new Ellipse2D.Double(
                rollerX - rollerDiameter / 2.0,
                rollerY - rollerDiameter / 2.0,
                rollerDiameter,
                rollerDiameter
            ));
            g2.setColor(BOLT);
            g2.fill(new Ellipse2D.Double(
                rollerX - 1.2, rollerY - 1.2, 2.4, 2.4
            ));
        }

        g2.setColor(activity == null ? SURFACE : activity);
        g2.setStroke(new BasicStroke(3.0f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(
            startX, startSurfaceY, endX, endSurfaceY
        ));
        g2.setColor(HIGHLIGHT);
        g2.setStroke(new BasicStroke(1.0f));
        g2.draw(new Line2D.Double(
            startX, startSurfaceY - 0.5, endX, endSurfaceY - 0.5
        ));
        drawJoint(g2, startX, startSurfaceY + safeDepth / 2.0,
            Math.max(4.0, safeDepth * 0.42));
        drawJoint(g2, endX, endSurfaceY + safeDepth / 2.0,
            Math.max(4.0, safeDepth * 0.42));
        g2.setStroke(original);
    }

    static void drawTransferLane(
        Graphics2D g2,
        double startX,
        double startY,
        double endX,
        double endY,
        boolean active
    ) {
        Stroke original = g2.getStroke();
        g2.setColor(FRAME_DARK);
        g2.setStroke(new BasicStroke(
            12.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND
        ));
        g2.draw(new Line2D.Double(startX, startY + 5.0, endX, endY + 5.0));
        g2.setColor(active ? new Color(115, 190, 218) : FRAME_MID);
        g2.setStroke(new BasicStroke(
            7.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND
        ));
        g2.draw(new Line2D.Double(startX, startY + 4.0, endX, endY + 4.0));
        g2.setColor(active ? new Color(195, 235, 247) : SURFACE);
        g2.setStroke(new BasicStroke(
            2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND
        ));
        g2.draw(new Line2D.Double(startX, startY, endX, endY));
        drawJoint(g2, startX, startY + 5.0, 5.0);
        drawJoint(g2, endX, endY + 5.0, 5.0);
        g2.setStroke(original);
    }

    static void drawGuideRail(
        Graphics2D g2,
        double x,
        double surfaceY,
        double width,
        double height
    ) {
        if (width < 8.0) return;
        Stroke original = g2.getStroke();
        double railY = surfaceY - Math.max(7.0, height);
        g2.setColor(new Color(109, 126, 141));
        g2.setStroke(new BasicStroke(2.2f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(x, railY, x + width, railY));
        double supportStep = Math.max(28.0, width / 3.0);
        for (double supportX = x + 4.0;
            supportX <= x + width - 3.0;
            supportX += supportStep) {
            g2.draw(new Line2D.Double(
                supportX, railY, supportX, surfaceY - 1.0
            ));
            g2.setColor(BOLT);
            g2.fill(new Ellipse2D.Double(
                supportX - 1.7, railY - 1.7, 3.4, 3.4
            ));
            g2.setColor(new Color(109, 126, 141));
        }
        g2.setStroke(original);
    }

    static int rollerCount(double width, double preferredSpacing) {
        double spacing = Math.max(8.0, preferredSpacing);
        return Math.max(2, (int)Math.floor(Math.max(12.0, width) / spacing));
    }

    static double rollerCenterX(
        double x,
        double width,
        double preferredSpacing,
        int index
    ) {
        int count = rollerCount(width, preferredSpacing);
        int bounded = Math.max(0, Math.min(count - 1, index));
        double inset = Math.min(Math.max(4.0, preferredSpacing * 0.35),
            Math.max(4.0, width * 0.18));
        if (count == 1) return x + width / 2.0;
        return x + inset + bounded * (width - inset * 2.0) / (count - 1);
    }

    static double surfaceYAlong(
        double x,
        double startX,
        double startY,
        double endX,
        double endY
    ) {
        if (Math.abs(endX - startX) < 0.0001) return endY;
        double ratio = (x - startX) / (endX - startX);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        return startY + (endY - startY) * ratio;
    }

    static boolean isBottleBaseAligned(
        double bottleBaseY,
        double surfaceY
    ) {
        return Math.abs(bottleBaseY - surfaceY) < 0.01;
    }

    private static void drawSupports(
        Graphics2D g2,
        double x,
        double bottomY,
        double width,
        double height
    ) {
        if (width < 35.0) return;
        Stroke original = g2.getStroke();
        g2.setColor(FRAME_DARK);
        g2.setStroke(new BasicStroke(3.0f,
            BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        double left = x + Math.min(13.0, width * 0.22);
        double right = x + width - Math.min(13.0, width * 0.22);
        g2.draw(new Line2D.Double(left, bottomY, left, bottomY + height));
        g2.draw(new Line2D.Double(right, bottomY, right, bottomY + height));
        g2.draw(new Line2D.Double(left - 5.0, bottomY + height,
            left + 5.0, bottomY + height));
        g2.draw(new Line2D.Double(right - 5.0, bottomY + height,
            right + 5.0, bottomY + height));
        g2.setStroke(original);
    }

    private static void drawEndPlate(
        Graphics2D g2,
        double x,
        double surfaceY,
        double depth
    ) {
        g2.setColor(FRAME_DARK);
        g2.fillRoundRect(round(x - 2.0), round(surfaceY - 1.0), 4,
            round(depth + 2.0), 3, 3);
        g2.setColor(BOLT);
        g2.fill(new Ellipse2D.Double(
            x - 1.4, surfaceY + depth / 2.0 - 1.4, 2.8, 2.8
        ));
    }

    private static void drawPulley(
        Graphics2D g2,
        double centreX,
        double centreY,
        double diameter
    ) {
        g2.setColor(FRAME_LIGHT);
        g2.fill(new Ellipse2D.Double(
            centreX - diameter / 2.0,
            centreY - diameter / 2.0,
            diameter,
            diameter
        ));
        g2.setColor(BOLT);
        g2.fill(new Ellipse2D.Double(
            centreX - 1.5, centreY - 1.5, 3.0, 3.0
        ));
    }

    private static void drawJoint(
        Graphics2D g2,
        double centreX,
        double centreY,
        double diameter
    ) {
        g2.setColor(FRAME_DARK);
        g2.fill(new Ellipse2D.Double(
            centreX - diameter / 2.0,
            centreY - diameter / 2.0,
            diameter,
            diameter
        ));
        g2.setColor(FRAME_LIGHT);
        double hub = Math.max(1.5, diameter * 0.34);
        g2.fill(new Ellipse2D.Double(
            centreX - hub / 2.0,
            centreY - hub / 2.0,
            hub,
            hub
        ));
    }

    private static double positiveModulo(double value, double modulus) {
        double result = value % modulus;
        return result < 0.0 ? result + modulus : result;
    }

    private static int round(double value) {
        return (int)Math.round(value);
    }
}
