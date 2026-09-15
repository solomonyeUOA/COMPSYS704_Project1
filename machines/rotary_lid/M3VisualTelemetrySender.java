import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicReference;
import com.systemj.ipc.GenericSignalSender;
import com.systemj.ipc.SimpleClient;

/** Best-effort latest-only telemetry. Network work never runs on the SystemJ thread. */
public final class M3VisualTelemetrySender extends GenericSignalSender {
    private final AtomicReference<Object[]> latest = new AtomicReference<Object[]>();
    private volatile SimpleClient client;
    private Thread worker;
    public void configure(Hashtable config) {
        final Hashtable copy = new Hashtable(config);
        worker = new Thread(() -> {
            client = new SimpleClient();
            client.configure(copy);
            while (!Thread.currentThread().isInterrupted()) {
                Object[] sample = latest.getAndSet(null);
                if (sample != null && client.setup(sample)) {
                    client.run();
                    // This is a level-held snapshot, not an event pulse. Sending
                    // ABSENT here can overwrite PRESENT before M1's next reaction.
                    // M1 rejects repeated sequences, so holding cannot fake freshness.
                }
                try { Thread.sleep(50); }
                catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            }
        }, "M3-read-only-visual-telemetry");
        worker.setDaemon(true);
        worker.start();
    }
    public boolean setup(Object[] sample) { buffer = sample.clone(); return true; }
    public void run() { latest.set(buffer.clone()); }
    public void arun() { }
    public void cleanUp() {
        if (worker != null) worker.interrupt();
        SimpleClient current = client;
        if (current != null) current.cleanUp();
    }
}
