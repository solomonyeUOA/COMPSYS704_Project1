import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Verifies that POS can request coordinated launcher shutdown without Swing. */
public final class POSExitProgramSelfTest {
    private POSExitProgramSelfTest() { }

    public static void main(String[] args) throws Exception {
        File request = File.createTempFile("abs-exit-program-", ".request");
        if (!request.delete()) {
            throw new AssertionError("could not prepare temporary shutdown request");
        }
        try {
            POSVisualisation.requestLauncherShutdown(request.getAbsolutePath());
            if (!request.isFile()) {
                throw new AssertionError("POS did not create launcher shutdown request");
            }
            String content = new String(Files.readAllBytes(request.toPath()),
                StandardCharsets.UTF_8);
            if (!"POS Exit Program\n".equals(content)) {
                throw new AssertionError("unexpected shutdown request: " + content);
            }
            System.out.println("POSExitProgramSelfTest PASSED");
        } finally {
            request.delete();
        }
    }
}
