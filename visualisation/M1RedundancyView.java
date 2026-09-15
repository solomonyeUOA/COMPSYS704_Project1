import java.awt.*;

/** Read-only M3 projection; never supplies controller or twin state. */
final class M1RedundancyView {
    private static final String[] IDS = {"ROTARY", "PICK", "PLACE", "POSITION", "ROTARY_CTRL", "LID_CTRL"};
    private static final String[] TITLES = {"Rotary / Drive", "Lid / Pick drive", "Lid / Place drive", "Rotary / Feedback", "Rotary / Controller", "Lid / Controller"};
    private Row[] rows;
    private String epoch = "";
    private long sequence = -1, sentAt, receivedAt;
    private String notice = "Awaiting M3 redundancy telemetry";
    private long noticeUntil;

    synchronized boolean accept(String payload, long now) {
        try {
            String[] parts = payload.split("\\|", -1);
            if ((parts.length != 8 && parts.length != 10) || !"M3R1".equals(parts[0]) || parts[1].isEmpty()) return false;
            long seq = Long.parseLong(parts[2]), timestamp = Long.parseLong(parts[3]);
            if (seq < 1 || timestamp < sentAt || timestamp > now + 10000 || now - timestamp > 5000 ||
                (epoch.equals(parts[1]) && seq <= sequence)) return false;
            Row[] next = new Row[parts.length - 4];
            for (int i = 0; i < next.length; i++) {
                String[] f = parts[i + 4].split(",", -1);
                if (f.length != 6 || !IDS[i].equals(f[0]) || !f[1].matches("A|B") ||
                    !f[2].matches("true|false") || !f[3].matches("true|false") ||
                    !f[4].matches("AVAILABLE|DEGRADED|SAFE_ERROR|DRIVE_FAULT_PENDING|FEEDBACK_UNAVAILABLE|FAILOVER_VERIFYING|STOPPING|ISOLATING_PRIMARY|CONNECTING_BACKUP")) return false;
                int count = Integer.parseInt(f[5]);
                if (count < 0) return false;
                next[i] = new Row(f[1], Boolean.parseBoolean(f[2]), Boolean.parseBoolean(f[3]), f[4], count);
            }
            if (rows != null && epoch.equals(parts[1])) {
                for (int i = 0; i < Math.min(next.length, rows.length); i++) {
                    if (next[i].count != rows[i].count || !next[i].state.equals(rows[i].state)) {
                        notice = TITLES[i] + ": " + next[i].label();
                        noticeUntil = now + 8000;
                    }
                }
            }
            rows = next; epoch = parts[1]; sequence = seq; sentAt = timestamp; receivedAt = now;
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    synchronized boolean stale(long now) { return rows == null || now - receivedAt > 5000; }

    synchronized void outline(Graphics2D g, int x, int y, int width, int height, int first, int second) {
        if (stale(System.currentTimeMillis())) return;
        Row a = rows[first], b = rows[second];
        boolean fault = a.state.equals("SAFE_ERROR") || b.state.equals("SAFE_ERROR");
        boolean verifying = a.state.equals("FAILOVER_VERIFYING") || b.state.equals("FAILOVER_VERIFYING");
        if (!fault && !verifying && a.count == 0 && b.count == 0) return;
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(3));
        g.setColor(fault ? new Color(185, 40, 45) : verifying && (System.currentTimeMillis() / 300) % 2 == 0 ?
            new Color(240, 188, 55) : new Color(177, 115, 14));
        g.drawRoundRect(x - 3, y - 3, width + 6, height + 6, 8, 8);
        g.setStroke(old);
    }

    synchronized void draw(Graphics2D g, int y, int width) {
        long now = System.currentTimeMillis();
        boolean stale = stale(now);
        g.setColor(new Color(225, 232, 235));
        g.drawLine(20, y, width - 20, y);
        int count = rows == null ? 6 : rows.length;
        int column = (width - 40) / count;
        for (int i = 0; i < count; i++) {
            int x = 24 + i * column;
            Row r = rows == null ? null : rows[i];
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.setColor(new Color(45, 65, 71));
            g.drawString(TITLES[i], x, y + 20);
            if (stale) {
                g.setColor(Color.GRAY);
                g.drawString(rows == null ? "UNKNOWN - no telemetry" : "STALE - telemetry timeout", x, y + 43);
                continue;
            }
            drawChannel(g, x, y + 29, "A", r.aFailed, r.active.equals("A"), r.state.equals("FAILOVER_VERIFYING"));
            drawChannel(g, x + 77, y + 29, "B", r.bFailed, r.active.equals("B"), r.state.equals("FAILOVER_VERIFYING"));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(r.state.equals("SAFE_ERROR") ? new Color(185, 40, 45) : new Color(55, 71, 78));
            g.drawString(r.label() + " / " + r.count, x, y + 71);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(stale ? Color.GRAY : new Color(158, 94, 0));
        g.drawString(stale ? "M3 redundancy: UNKNOWN / shared telemetry not fresh" : now < noticeUntil ? notice : summary(), 24, y + 94);
    }

    private String summary() {
        for (Row r : rows) if (r.state.equals("SAFE_ERROR") || r.aFailed && r.bFailed) return "M3 redundancy: UNAVAILABLE / SAFE STOP";
        for (Row r : rows) if (r.state.equals("STOPPING") || r.state.equals("ISOLATING_PRIMARY") ||
            r.state.equals("CONNECTING_BACKUP")) return "M3 redundancy: TRANSFER IN PROGRESS / motion inhibited";
        for (Row r : rows) if (r.aFailed || r.bFailed) return "M3 redundancy: DEGRADED - failed channels remain isolated";
        return "M3 redundancy: ALL PRIMARY / STANDBYS AVAILABLE";
    }

    private void drawChannel(Graphics2D g, int x, int y, String label, boolean failed, boolean active, boolean verifying) {
        g.setColor(failed ? new Color(185, 40, 45) : active && verifying ? new Color(177, 115, 14) :
            active ? new Color(0, 132, 92) : new Color(130, 143, 149));
        g.fillOval(x, y + 2, 10, 10);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString(label + (failed ? " FAILED" : active && verifying ? " CHECK" : active ? " ACTIVE" : " READY"), x + 14, y + 12);
    }

    private static final class Row {
        final String active, state;
        final boolean aFailed, bFailed;
        final int count;
        Row(String active, boolean a, boolean b, String state, int count) {
            this.active = active; aFailed = a; bFailed = b; this.state = state; this.count = count;
        }
        String label() {
            if (state.equals("STOPPING")) return "STOP / HOLD";
            if (state.equals("ISOLATING_PRIMARY")) return "ISOLATING " + active;
            if (state.equals("CONNECTING_BACKUP")) return "CONNECTING BACKUP";
            if (state.equals("SAFE_ERROR")) return "NO SAFE BACKUP";
            if (state.equals("FAILOVER_VERIFYING")) return "A -> " + active + " / VERIFYING";
            if (state.equals("DRIVE_FAULT_PENDING") || state.equals("FEEDBACK_UNAVAILABLE")) return "FAULT / awaiting selection";
            return active.equals("B") ? "BACKUP B ACTIVE" : "PRIMARY A ACTIVE";
        }
    }
}
