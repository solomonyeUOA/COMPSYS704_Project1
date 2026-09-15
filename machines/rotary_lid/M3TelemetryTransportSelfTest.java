import java.net.*;
import java.io.*;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicReference;

/** Real SimpleClient wire regression with a deliberately slow consumer. */
public final class M3TelemetryTransportSelfTest {
    public static void main(String[] args) throws Exception {
        final ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        final AtomicReference<Object[]> received = new AtomicReference<Object[]>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread receiver = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                ObjectInputStream input = new ObjectInputStream(socket.getInputStream());
                input.readObject();
                socket.getOutputStream().write(1);
                socket.getOutputStream().flush();
                while (!Thread.currentThread().isInterrupted()) received.set((Object[]) input.readObject());
            } catch (EOFException closed) {
                // Sender cleanup closes the stream.
            } catch (Throwable failure) { error.set(failure); }
        }, "slow-telemetry-test-receiver");
        receiver.setDaemon(true);
        receiver.start();
        M3VisualTelemetrySender sender = new M3VisualTelemetrySender();
        Hashtable<String, String> config = new Hashtable<String, String>();
        config.put("Name", "M3_REDUNDANCY_VIEW");
        config.put("To", "ABSVisualisationPlantCD.M3_REDUNDANCY_VIEW");
        config.put("IP", "127.0.0.1");
        config.put("Port", Integer.toString(server.getLocalPort()));
        sender.configure(config);
        try {
            for (int sequence = 1; sequence <= 30; sequence++) {
                String sample = "snapshot-" + sequence;
                sender.setup(new Object[] {Boolean.TRUE, sample});
                sender.run();
                // Poll slower than the asynchronous network sender, as a busy GUI does.
                Thread.sleep(100);
                Object[] value = received.get();
                if (value == null || !Boolean.TRUE.equals(value[0]) || !sample.equals(value[1]))
                    throw new AssertionError("Snapshot overwritten before consumer reaction: " + sequence);
            }
            Thread.sleep(300);
            if (!Boolean.TRUE.equals(received.get()[0])) throw new AssertionError("snapshot must remain held");
            if (error.get() != null) throw new AssertionError(error.get());
            System.out.println("M3TelemetryTransportSelfTest PASS: 30 slow polls; latest snapshot remains present");
        } finally {
            sender.cleanUp();
            server.close();
            receiver.join(2000);
        }
    }
}
