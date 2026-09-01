import com.systemj.ipc.SimpleClient;

/**
 * SimpleClient variant for an optional receiver.
 *
 * SystemJ's desktop SimpleClient attempts to connect even while an output
 * signal is absent. That behaviour is useful for required peers, but it makes
 * every Coordinator reaction wait for the socket timeout when an optional
 * FaultSupervisor is not running. This sender remains dormant until the
 * application actually emits the signal, then delegates to SimpleClient.
 */
public final class OptionalSimpleClient extends SimpleClient {
    private boolean activated = false;

    @Override
    public void run() {
        activated = true;
        super.run();
    }

    @Override
    public void arun() {
        if (activated) {
            super.arun();
        }
    }
}
