public final class M1RedundancyViewSelfTest {
    public static void main(String[] args) {
        M1RedundancyView model = new M1RedundancyView();
        String rows = "|ROTARY,B,true,false,DEGRADED,1|PICK,A,false,false,AVAILABLE,0" +
            "|PLACE,A,false,false,AVAILABLE,0|POSITION,A,false,false,AVAILABLE,0";
        require(model.stale(100), "initially unknown");
        require(model.accept("M3R1|E|1|100" + rows, 100), "valid snapshot");
        require(!model.stale(5000), "fresh snapshot");
        require(!model.accept("M3R1|E|1|100" + rows, 5000), "duplicate cannot refresh freshness");
        require(model.stale(5101), "lost publisher is stale");
        for (int tick = 5102; tick < 6000; tick++) {
            require(!model.accept("M3R1|E|1|100" + rows, tick), "held snapshot is not a heartbeat");
            require(model.stale(tick), "repeated held value cannot hide disconnection");
        }
        require(!model.accept("M3R1|E|2|5102" + rows.replace("DEGRADED", "INVALID"), 5102), "invalid state rejected");
        require(model.accept("M3R1|NEW|1|5102" + rows, 5102), "publisher restart");
        require(!model.accept("M3R1|E|3|100" + rows, 5103), "old session rejected");
        String controllers = "|ROTARY_CTRL,B,true,false,FAILOVER_VERIFYING,1|LID_CTRL,B,true,false,DEGRADED,1";
        long now = System.currentTimeMillis();
        for (String state : new String[] {"STOPPING", "ISOLATING_PRIMARY", "CONNECTING_BACKUP"}) {
            M1RedundancyView phases = new M1RedundancyView();
            require(phases.accept("M3R1|PHASE|1|" + now + rows.replace("DEGRADED", state) + controllers, now),
                "physical transfer phase " + state);
        }
        require(model.accept("M3R1|NEW|2|" + now + rows + controllers, now), "controller pair telemetry accepted");
        require(!model.accept("M3R1|NEW|3|" + now + rows + controllers.replace("LID_CTRL", "UNKNOWN"), now),
            "unknown controller rejected atomically");
        java.awt.image.BufferedImage preview = new java.awt.image.BufferedImage(1360, 115,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = preview.createGraphics();
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 1360, 115);
        model.draw(g, 4, 1360); g.dispose();
        try { javax.imageio.ImageIO.write(preview, "png", new java.io.File("build/controller-redundancy-preview.png")); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        System.out.println("M1RedundancyViewSelfTest PASSED");
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
