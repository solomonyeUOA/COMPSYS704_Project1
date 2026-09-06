import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback-only HTTP bridge for the browser-based M3 supervisor GUI. */
public final class FaultManagementWebServer {
    private static final AtomicLong TEST_EVENT_SEQUENCE = new AtomicLong(1);
    private static HttpServer server;

    private FaultManagementWebServer() {
    }

    public static synchronized void start() {
        if (server != null || GraphicsEnvironment.isHeadless()) {
            return;
        }
        Path root = Paths.get(System.getProperty(
            "m3.gui.root", "machines/rotary_lid/gui"
        )).toAbsolutePath().normalize();
        try {
            server = bindServer();
            server.createContext("/api/state", new StateHandler());
            server.createContext("/api/action", new ActionHandler());
            server.createContext("/", new StaticHandler(root));
            ExecutorService executor = Executors.newCachedThreadPool(
                new ThreadFactory() {
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(
                            runnable, "m3-fault-gui-http"
                        );
                        thread.setDaemon(true);
                        return thread;
                    }
                }
            );
            server.setExecutor(executor);
            server.start();
            String url = "http://127.0.0.1:" +
                server.getAddress().getPort() + "/";
            System.out.println("M3 fault-management dashboard: " + url);
            if (!Boolean.getBoolean("m3.gui.noBrowser") &&
                Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        }
        catch (IOException exception) {
            server = null;
            System.err.println("M3 dashboard unavailable: " +
                exception.getMessage());
        }
    }

    private static HttpServer bindServer() throws IOException {
        int preferred = Integer.getInteger("m3.gui.port", 18080).intValue();
        IOException last = null;
        for (int port = preferred; port < preferred + 10; port++) {
            try {
                return HttpServer.create(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), port
                ), 0);
            }
            catch (IOException exception) {
                last = exception;
            }
        }
        throw last == null ? new IOException("no available port") : last;
    }

    private static final class StateHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", error("GET required"));
                return;
            }
            send(exchange, 200, "application/json", stateJson());
        }
    }

    private static final class ActionHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", error("POST required"));
                return;
            }
            if (!Boolean.getBoolean("m3.testMode")) {
                send(exchange, 403, "application/json",
                    error("test mode is disabled"));
                return;
            }
            Map<String, String> fields = form(readBody(exchange));
            String action = fields.get("action");
            boolean accepted;
            try {
                accepted = performAction(action, fields.get("fault"));
            }
            catch (RuntimeException exception) {
                send(exchange, 400, "application/json",
                    error(exception.getMessage()));
                return;
            }
            send(exchange, accepted ? 200 : 409, "application/json",
                "{\"accepted\":" + accepted + ",\"state\":" +
                quote(FaultSupervisorStateV2_1.stateName()) + "}");
        }
    }

    private static boolean performAction(String action, String fault) {
        if ("inject".equals(action)) {
            return inject(fault);
        }
        if ("safe-stop".equals(action)) {
            return confirmSafeStop();
        }
        if ("controller-evidence".equals(action)) {
            return returnControllerEvidence();
        }
        if ("manual-evidence".equals(action)) {
            return recordManualEvidence();
        }
        if ("resume".equals(action)) {
            return sendResume();
        }
        if ("reset".equals(action)) {
            FaultSupervisorStateV2_1.reset();
            return true;
        }
        throw new IllegalArgumentException("unknown action");
    }

    private static boolean inject(String fault) {
        String subsystem;
        String severity;
        if ("ALIGNMENT_TIMEOUT".equals(fault)) {
            subsystem = "ROTARY";
            severity = "WARNING";
        }
        else if ("MOTOR_STALL".equals(fault) ||
            "POSITION_SENSOR_FAILURE".equals(fault)) {
            subsystem = "ROTARY";
            severity = "CRITICAL";
        }
        else if ("MAGAZINE_EMPTY".equals(fault)) {
            subsystem = "LID";
            severity = "RESOURCE";
        }
        else if ("PICK_TIMEOUT".equals(fault)) {
            subsystem = "LID";
            severity = "WARNING";
        }
        else if ("PLACEMENT_TIMEOUT".equals(fault) ||
            "LID_SENSOR_FAULT".equals(fault)) {
            subsystem = "LID";
            severity = "CRITICAL";
        }
        else if ("ARRIVAL_TIMEOUT".equals(fault)) {
            subsystem = "TRANSFER";
            severity = "WARNING";
        }
        else if ("DEPARTURE_TIMEOUT".equals(fault) ||
            "PHOTO_EYE_FAILURE".equals(fault) ||
            "POSITION_CONFLICT".equals(fault)) {
            subsystem = "TRANSFER";
            severity = "CRITICAL";
        }
        else {
            throw new IllegalArgumentException("unknown fault");
        }
        long sequence = TEST_EVENT_SEQUENCE.getAndIncrement();
        return FaultSupervisorStateV2_1.onFaultEvent(
            "V2|GUI-" + sequence + "|GUI-TEST|" + subsystem + "|" +
            fault + "|" + severity + "|B-GUI|" + sequence
        );
    }

    private static boolean confirmSafeStop() {
        return FaultSupervisorStateV2_1.onSafeStopAck(
            "V2|" + FaultSupervisorStateV2_1.activeEventId() + "|" +
            FaultSupervisorStateV2_1.activeEpoch() + "|SAFE_STOPPED|" +
            FaultSupervisorStateV2_1.activeStateVersion()
        );
    }

    private static boolean returnControllerEvidence() {
        String state = FaultSupervisorStateV2_1.stateName();
        String event = FaultSupervisorStateV2_1.activeEventId();
        String epoch = FaultSupervisorStateV2_1.activeEpoch();
        long version = FaultSupervisorStateV2_1.activeStateVersion();
        if ("WAITING_ACK".equals(state)) {
            int attempt = FaultSupervisorStateV2_1.activeAttempt();
            boolean ack = FaultSupervisorStateV2_1.onRecoveryAck(
                "V2|" + event + "|" + epoch + "|" + attempt +
                "|ACCEPTED|interlocks_satisfied|" + version
            );
            return ack && FaultSupervisorStateV2_1.onRecoveryResult(
                "V2|" + event + "|" + epoch + "|" + attempt +
                "|SUCCESS|" +
                FaultSupervisorStateV2_1.requiredSafeEvidence() + "|" +
                FaultSupervisorStateV2_1.requiredServiceEvidence() + "|" +
                (version + 1)
            );
        }
        if ("RESOURCE_WAIT".equals(state)) {
            return FaultSupervisorStateV2_1.confirmResourceRestored(
                event, true, version + 1
            );
        }
        if ("LOCKED_OUT".equals(state)) {
            return FaultSupervisorStateV2_1.confirmManualControllerEvidence(
                event, epoch,
                FaultSupervisorStateV2_1.requiredSafeEvidence(),
                FaultSupervisorStateV2_1.requiredServiceEvidence(),
                version + 1
            );
        }
        return false;
    }

    private static boolean recordManualEvidence() {
        return FaultSupervisorStateV2_1.recordManualEvidence(
            new ManualReconciliationEvidenceV2_1(
                FaultSupervisorStateV2_1.activeEventId(),
                FaultSupervisorStateV2_1.activeEpoch(),
                FaultSupervisorStateV2_1.activeSubsystem(),
                FaultSupervisorStateV2_1.activeBottleId(),
                FaultSupervisorStateV2_1.activeStateVersion(),
                System.getProperty("user.name", "operator"),
                "POSITION_AND_BOTTLE_RECONCILED"
            )
        );
    }

    private static boolean sendResume() {
        return FaultSupervisorStateV2_1.onResumeDecision(
            "V2|" + FaultSupervisorStateV2_1.activeEventId() + "|" +
            FaultSupervisorStateV2_1.activeEpoch() +
            "|RESUME|GUI_TEST_APPROVAL|" +
            FaultSupervisorStateV2_1.latestStateVersion()
        );
    }

    private static String stateJson() {
        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "state", FaultSupervisorStateV2_1.stateName());
        field(json, "decision", FaultSupervisorStateV2_1.decision());
        field(json, "eventId", FaultSupervisorStateV2_1.activeEventId());
        field(json, "epoch", FaultSupervisorStateV2_1.activeEpoch());
        field(json, "subsystem", FaultSupervisorStateV2_1.activeSubsystem());
        field(json, "fault", FaultSupervisorStateV2_1.activeFaultCode());
        field(json, "severity", FaultSupervisorStateV2_1.activeSeverity());
        field(json, "bottleId", FaultSupervisorStateV2_1.activeBottleId());
        numberField(json, "stateVersion",
            FaultSupervisorStateV2_1.latestStateVersion());
        numberField(json, "attempt",
            FaultSupervisorStateV2_1.activeAttempt());
        field(json, "policy", FaultSupervisorStateV2_1.policySummary());
        field(json, "evidence", FaultSupervisorStateV2_1.latestEvidence());
        field(json, "local", FaultSupervisorStateV2_1.localSummary());
        field(json, "metrics",
            FaultSupervisorStateV2_1.metricsSnapshot().summary());
        json.append("\"testMode\":")
            .append(Boolean.getBoolean("m3.testMode")).append(',');
        json.append("\"history\":[");
        String[] history = FaultSupervisorStateV2_1.historySnapshot();
        for (int index = 0; index < history.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(quote(history[index]));
        }
        json.append("]}");
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append(quote(name)).append(':').append(quote(value)).append(',');
    }

    private static void numberField(
        StringBuilder json,
        String name,
        long value
    ) {
        json.append(quote(name)).append(':').append(value).append(',');
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '\"') {
                escaped.append('\\').append(character);
            }
            else if (character == '\n') {
                escaped.append("\\n");
            }
            else if (character == '\r') {
                escaped.append("\\r");
            }
            else {
                escaped.append(character);
            }
        }
        return escaped.append('\"').toString();
    }

    private static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    private static Map<String, String> form(String body) throws IOException {
        Map<String, String> values = new HashMap<String, String>();
        if (body.length() == 0) {
            return values;
        }
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], "UTF-8");
            String value = parts.length == 2 ?
                URLDecoder.decode(parts[1], "UTF-8") : "";
            values.put(key, value);
        }
        return values;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(
        HttpExchange exchange,
        int status,
        String contentType,
        String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class StaticHandler implements HttpHandler {
        private final Path root;

        StaticHandler(Path root) {
            this.root = root;
        }

        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "GET required");
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            if ("/".equals(requestPath)) {
                requestPath = "/index.html";
            }
            Path file = root.resolve(requestPath.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                send(exchange, 404, "text/plain", "Not found");
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(file));
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private String contentType(Path file) {
            String name = file.getFileName().toString();
            if (name.endsWith(".css")) {
                return "text/css; charset=utf-8";
            }
            if (name.endsWith(".js")) {
                return "application/javascript; charset=utf-8";
            }
            return "text/html; charset=utf-8";
        }
    }
}
