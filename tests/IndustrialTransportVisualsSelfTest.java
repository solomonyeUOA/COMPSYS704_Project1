import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Geometry and paint regression for the shared industrial transport style. */
public final class IndustrialTransportVisualsSelfTest {
    private static int assertions;

    private IndustrialTransportVisualsSelfTest() { }

    public static void main(String[] args) {
        require(IndustrialTransportVisuals.rollerCount(416.0, 52.0) == 8,
            "detail conveyor has eight repeated roller assemblies");
        require(IndustrialTransportVisuals.rollerCount(38.0, 10.0) >= 3,
            "compact overview conveyor keeps repeated rollers");

        double first = IndustrialTransportVisuals.rollerCenterX(
            42.0, 416.0, 52.0, 0
        );
        double last = IndustrialTransportVisuals.rollerCenterX(
            42.0, 416.0, 52.0, 7
        );
        require(first > 42.0 && last < 458.0 && last > first,
            "roller hubs are ordered inside the frame");

        require(IndustrialTransportVisuals.surfaceYAlong(
                50.0, 0.0, 100.0, 100.0, 200.0) == 150.0,
            "sloped transport surface interpolates deterministically");
        require(IndustrialTransportVisuals.surfaceYAlong(
                -20.0, 0.0, 100.0, 100.0, 200.0) == 100.0,
            "sloped surface clamps before its entry joint");
        require(IndustrialTransportVisuals.surfaceYAlong(
                120.0, 0.0, 100.0, 100.0, 200.0) == 200.0,
            "sloped surface clamps after its exit joint");

        BottleVisualGeometry small = BottleVisualGeometry.forSizeCode("S");
        BottleVisualGeometry large = BottleVisualGeometry.forSizeCode("L");
        double surfaceY = 260.0;
        double smallBase = small.topY(surfaceY, 76) +
            small.totalHeight(76);
        double largeBase = large.topY(surfaceY, 76) +
            large.totalHeight(76);
        require(IndustrialTransportVisuals.isBottleBaseAligned(
                smallBase, surfaceY),
            "S bottle base shares the conveyor contact surface");
        require(IndustrialTransportVisuals.isBottleBaseAligned(
                largeBase, surfaceY),
            "L bottle base shares the conveyor contact surface");

        BufferedImage image = new BufferedImage(
            500, 300, BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            IndustrialTransportVisuals.drawRollerConveyor(
                graphics, 20, 70, 210, 30, 32, true, true, null
            );
            IndustrialTransportVisuals.drawBeltConveyor(
                graphics, 260, 70, 210, 30, true, true,
                new Color(31, 132, 190), 11.0
            );
            IndustrialTransportVisuals.drawSlopedRollerConveyor(
                graphics, 20, 210, 190, 140, 24, 30, null
            );
            IndustrialTransportVisuals.drawTransferLane(
                graphics, 260, 210, 455, 140, true
            );
            IndustrialTransportVisuals.drawMachineBed(
                graphics, 215, 238, 72, 18, null
            );
        }
        finally {
            graphics.dispose();
        }
        require(nonWhitePixels(image) > 9000,
            "shared helpers paint layered mechanical assemblies");
        require(!Color.WHITE.equals(new Color(image.getRGB(22, 71))),
            "roller conveyor has a visible transport surface");
        require(!Color.WHITE.equals(new Color(image.getRGB(265, 72))),
            "belt conveyor has a visible transport surface");

        System.out.println("OVERVIEW_TRACK_HELPER = PASS");
        System.out.println("ROLLER_ASSEMBLIES = PASS");
        System.out.println("BELT_ASSEMBLY = PASS");
        System.out.println("S_BOTTLE_BASE_ALIGNED = PASS");
        System.out.println("L_BOTTLE_BASE_ALIGNED = PASS");
        System.out.println(
            "IndustrialTransportVisualsSelfTest PASSED assertions=" +
            assertions
        );
    }

    private static int nonWhitePixels(BufferedImage image) {
        int count = 0;
        int white = Color.WHITE.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != white) count++;
            }
        }
        return count;
    }

    private static void require(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
