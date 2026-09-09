import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/** Framework-free contract and HTTP safety check for the experimental HMI. */
public final class ABSVisualisationWebServerSelfTest {
    private ABSVisualisationWebServerSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        System.setProperty("m1.web3d.port", "0");
        System.setProperty("m1.web3d.noBrowser", "true");
        System.clearProperty("m1.web3d.demo");
        System.setProperty(
            "m1.web3d.root",
            Paths.get("visualisation_web3d/gui").toAbsolutePath().toString()
        );

        ABSVisualisationWebServer.resetStateForTest();
        String base = null;
        try {
            base = ABSVisualisationWebServer.start();
            ABSVisualisationWebServer.updateRequiredBottles(3);
            ABSVisualisationWebServer.updateCompletedBottles(1);
            ABSVisualisationWebServer.updateStatus("Bottle Loader", 2);
            require(
                ABSVisualisationWebServer.updateFtEvidence(
                    "V1|FAULT_HOLD|FaultSupervisorCD|EV-7|" +
                    "SAFE_STOPPED|HOLD_RETAINED"
                ),
                "valid FT evidence must be accepted"
            );

            Thread.sleep(100L);
            Response state = request(base + "api/state", "GET");
            require(state.status == 200, "GET /api/state must return 200");
            requireContains(state.body, "\"readOnly\":true");
            requireContains(state.body, "\"requiredBottles\":3");
            requireContains(state.body, "\"completedBottles\":1");
            requireContains(state.body, "\"key\":\"loader\"");
            requireContains(state.body, "\"statusName\":\"BUSY\"");
            requireContains(state.body, "\"title\":\"DIGITAL TWIN\"");
            requireContains(state.body, "\"title\":\"FAULT TOLERANCE\"");
            requireContains(state.body, "\"title\":\"TWO-SIZE EXTENSION\"");
            requireContains(state.body, "\"ftEvidence\":\"V1|");

            Response rejected = request(base + "api/state", "POST");
            require(
                rejected.status == 405,
                "POST /api/state must be rejected with 405"
            );
            requireContains(rejected.body, "read-only");

            Response index = request(base, "GET");
            require(index.status == 200, "GET / must return 200");
            requireContains(
                index.body,
                "Hierarchical Automated Bottling System 3D Monitoring HMI"
            );
            requireContains(index.body, "READ-ONLY MONITORING");
            require(
                !index.body.contains("method=\"post\"") &&
                !index.body.contains("/api/action"),
                "browser shell must expose no action endpoint"
            );

            Response module = request(base + "vendor/three.module.min.js", "GET");
            require(module.status == 200, "local Three.js module must load");
            requireContains(module.body, "THREE");
        }
        finally {
            ABSVisualisationWebServer.stop();
        }

        System.out.println(
            "ABSVisualisationWebServerSelfTest PASSED " +
            "(GET-only API, static assets, state model and Team-IP evidence)"
        );
    }

    private static Response request(String address, String method)
        throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
            new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ?
            connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            input.close();
        }
        connection.disconnect();
        return new Response(
            status,
            new String(output.toByteArray(), StandardCharsets.UTF_8)
        );
    }

    private static void requireContains(String value, String fragment) {
        require(
            value != null && value.contains(fragment),
            "expected response fragment: " + fragment
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Response {
        private final int status;
        private final String body;

        Response(int responseStatus, String responseBody) {
            status = responseStatus;
            body = responseBody;
        }
    }
}
