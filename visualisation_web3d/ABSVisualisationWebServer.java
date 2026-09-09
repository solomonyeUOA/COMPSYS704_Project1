import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Experimental loopback-only, read-only bridge for the M1 Web3D HMI.
 *
 * There are deliberately no POST endpoints and no Controller or Plant output
 * methods. Public update methods accept the same M1 visualisation values as
 * the Swing boundary, but Phase 1 does not modify the canonical SystemJ CD.
 */
public final class ABSVisualisationWebServer {
    private static final String[] MACHINE_NAMES = {
        "Bottle Loader", "Conveyor", "Rotary Turntable", "Filler A",
        "Filler B", "Lid Loader", "Capper", "Bottle Unloader"
    };
    private static final Object STATE_LOCK = new Object();
    private static final CountDownLatch SHUTDOWN = new CountDownLatch(1);

    private static ABSVisualisationFlowModel flowModel =
        new ABSVisualisationFlowModel();
    private static ABSVisualisationTeamIpModel teamIpModel =
        new ABSVisualisationTeamIpModel();
    private static int[] statuses = new int[
        ABSVisualisationFlowModel.MODULE_COUNT
    ];
    private static boolean[] received = new boolean[
        ABSVisualisationFlowModel.MODULE_COUNT
    ];
    private static String latestFtEvidence = "";
    private static boolean demoMode;

    private static HttpServer server;
    private static ExecutorService httpExecutor;
    private static ScheduledExecutorService modelClock;
    private static ScheduledExecutorService demoClock;
    private static String serverUrl;

    private ABSVisualisationWebServer() {
    }

    /** Starts the optional browser bridge and returns its loopback URL. */
    public static synchronized String start() throws IOException {
        if (server != null) {
            return serverUrl;
        }

        Path root = Paths.get(System.getProperty(
            "m1.web3d.root", "visualisation_web3d/gui"
        )).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Web3D asset directory not found: " + root);
        }

        demoMode = Boolean.getBoolean("m1.web3d.demo");
        server = bindServer();
        server.createContext("/api/state", new StateHandler());
        server.createContext("/", new StaticHandler(root));
        httpExecutor = Executors.newCachedThreadPool(
            daemonThreadFactory("m1-web3d-http")
        );
        server.setExecutor(httpExecutor);
        server.start();

        modelClock = Executors.newSingleThreadScheduledExecutor(
            daemonThreadFactory("m1-web3d-model")
        );
        modelClock.scheduleAtFixedRate(new Runnable() {
            public void run() {
                synchronized (STATE_LOCK) {
                    flowModel.tickElapsed(System.nanoTime());
                }
            }
        }, 0L, 30L, TimeUnit.MILLISECONDS);

        if (demoMode) {
            startDemoStateSource();
        }

        serverUrl = "http://127.0.0.1:" +
            server.getAddress().getPort() + "/";
        System.out.println("M1 Web3D HMI: " + serverUrl);
        System.out.println(
            "M1 Web3D boundary: READ-ONLY" +
            (demoMode ? " | DEMO / TEST ONLY" : "")
        );
        openBrowser(serverUrl);
        return serverUrl;
    }

    /** Stops only resources owned by this optional experimental bridge. */
    public static synchronized void stop() {
        if (demoClock != null) {
            demoClock.shutdownNow();
            demoClock = null;
        }
        if (modelClock != null) {
            modelClock.shutdownNow();
            modelClock = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        serverUrl = null;
    }

    /** Accepts one existing VIZ_* machine status; it sends nothing outward. */
    public static void updateStatus(String machine, int status) {
        int index = machineIndex(machine);
        if (index < 0) {
            return;
        }
        synchronized (STATE_LOCK) {
            if (received[index] && statuses[index] == status) {
                return;
            }
            statuses[index] = status;
            received[index] = true;
            flowModel.acceptStatus(index, status);
        }
    }

    /** Accepts the existing VIZ_REQUIRED_BOTTLES value. */
    public static void updateRequiredBottles(int required) {
        synchronized (STATE_LOCK) {
            flowModel.acceptRequired(required);
        }
    }

    /** Accepts the existing VIZ_COMPLETED_BOTTLES value. */
    public static void updateCompletedBottles(int completed) {
        synchronized (STATE_LOCK) {
            flowModel.acceptCompleted(completed);
        }
    }

    /** Accepts Coordinator-validated VIZ_FT_EVIDENCE only. */
    public static boolean updateFtEvidence(String evidence) {
        synchronized (STATE_LOCK) {
            boolean accepted = teamIpModel.acceptM3Evidence(evidence);
            if (accepted) {
                latestFtEvidence = evidence;
            }
            return accepted;
        }
    }

    static String snapshotJson() {
        synchronized (STATE_LOCK) {
            return ABSWebSnapshot.capture(
                flowModel.getSnapshot(),
                teamIpModel.getSnapshot(),
                statuses.clone(),
                received.clone(),
                demoMode,
                latestFtEvidence
            ).toJson();
        }
    }

    static void resetStateForTest() {
        synchronized (STATE_LOCK) {
            flowModel = new ABSVisualisationFlowModel();
            teamIpModel = new ABSVisualisationTeamIpModel();
            statuses = new int[ABSVisualisationFlowModel.MODULE_COUNT];
            received = new boolean[ABSVisualisationFlowModel.MODULE_COUNT];
            latestFtEvidence = "";
        }
    }

    private static HttpServer bindServer() throws IOException {
        int preferred = Integer.getInteger("m1.web3d.port", 18081).intValue();
        if (preferred == 0) {
            return HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), 0
            ), 0);
        }
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
        throw last == null ? new IOException("no loopback port available") :
            last;
    }

    private static void startDemoStateSource() {
        synchronized (STATE_LOCK) {
            flowModel.acceptRequired(3);
            flowModel.acceptCompleted(0);
            for (int index = 0; index < MACHINE_NAMES.length; index++) {
                updateStatus(MACHINE_NAMES[index],
                    ABSVisualisationFlowModel.READY_STATUS);
            }
            updateFtEvidence(
                "V1|NORMAL|FaultSupervisorCD|none|MONITORING|HOLD_RETAINED"
            );
        }
        demoClock = Executors.newSingleThreadScheduledExecutor(
            daemonThreadFactory("m1-web3d-demo")
        );
        demoClock.scheduleAtFixedRate(
            new DemoStateSource(),
            500L,
            700L,
            TimeUnit.MILLISECONDS
        );
    }

    private static void openBrowser(String url) {
        if (Boolean.getBoolean("m1.web3d.noBrowser") ||
            GraphicsEnvironment.isHeadless() ||
            !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        }
        catch (IOException exception) {
            System.err.println(
                "M1 Web3D browser auto-open unavailable: " +
                exception.getMessage()
            );
        }
    }

    private static int machineIndex(String machine) {
        for (int index = 0; index < MACHINE_NAMES.length; index++) {
            if (MACHINE_NAMES[index].equals(machine)) {
                return index;
            }
        }
        return -1;
    }

    private static ThreadFactory daemonThreadFactory(final String prefix) {
        return new ThreadFactory() {
            private int sequence;

            public synchronized Thread newThread(Runnable runnable) {
                Thread thread = new Thread(
                    runnable,
                    prefix + "-" + (++sequence)
                );
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static final class DemoStateSource implements Runnable {
        private int machine;
        private int phase;
        private int rotaryTurns;
        private int completed;
        private boolean finished;

        public void run() {
            if (finished) {
                return;
            }
            int status;
            if (phase == 0) {
                status = ABSVisualisationFlowModel.BUSY_STATUS;
            }
            else if (phase == 1) {
                status = ABSVisualisationFlowModel.DONE_STATUS;
            }
            else {
                status = ABSVisualisationFlowModel.READY_STATUS;
            }
            updateStatus(MACHINE_NAMES[machine], status);

            if (phase == 1 &&
                machine == ABSVisualisationFlowModel.UNLOADER) {
                completed++;
                updateCompletedBottles(completed);
            }

            phase = (phase + 1) % 3;
            if (phase != 0) {
                return;
            }

            if (machine == ABSVisualisationFlowModel.ROTARY &&
                rotaryTurns < 5) {
                rotaryTurns++;
                return;
            }
            rotaryTurns = 0;
            machine++;
            if (machine >= ABSVisualisationFlowModel.MODULE_COUNT) {
                machine = 0;
                finished = completed >= 3;
            }
        }
    }

    private static final class StateHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(
                    exchange,
                    HttpURLConnection.HTTP_BAD_METHOD,
                    "application/json",
                    "{\"error\":\"GET required; Web3D is read-only\"}"
                );
                return;
            }
            send(
                exchange,
                HttpURLConnection.HTTP_OK,
                "application/json",
                snapshotJson()
            );
        }
    }

    private static final class StaticHandler implements HttpHandler {
        private final Path root;

        StaticHandler(Path assetRoot) {
            root = assetRoot;
        }

        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(
                    exchange,
                    HttpURLConnection.HTTP_BAD_METHOD,
                    "text/plain",
                    "GET required; Web3D is read-only"
                );
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            if ("/".equals(requestPath)) {
                requestPath = "/index.html";
            }
            Path file = root.resolve(requestPath.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                send(
                    exchange,
                    HttpURLConnection.HTTP_NOT_FOUND,
                    "text/plain",
                    "Not found"
                );
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            Headers headers = secureHeaders(exchange);
            headers.set("Content-Type", contentType(file));
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK,
                bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static void send(
        HttpExchange exchange,
        int status,
        String contentType,
        String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = secureHeaders(exchange);
        headers.set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Headers secureHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; " +
            "img-src 'self' data:; connect-src 'self'; object-src 'none'; " +
            "base-uri 'none'; frame-ancestors 'none'"
        );
        return headers;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    public static void main(String[] arguments) throws Exception {
        for (String argument : arguments) {
            if ("--demo".equals(argument)) {
                System.setProperty("m1.web3d.demo", "true");
            }
            else if ("--no-browser".equals(argument)) {
                System.setProperty("m1.web3d.noBrowser", "true");
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                stop();
            }
        }, "m1-web3d-shutdown"));
        start();
        SHUTDOWN.await();
    }
}
