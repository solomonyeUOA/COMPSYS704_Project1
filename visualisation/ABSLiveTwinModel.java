import java.math.BigInteger;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validated, read-only projection of M2's complete, generation-tagged snapshots. */
final class ABSLiveTwinModel {
    static final class Snapshot {
        final BigInteger generation;
        final long sequence;
        final int rejected;
        private final List<String[]> workpieces;
        private final List<String[]> resources;

        Snapshot(BigInteger epoch, long serial, int failures,
                 List<String[]> bottles, List<String[]> machines) {
            generation = epoch;
            sequence = serial;
            rejected = failures;
            workpieces = Collections.unmodifiableList(bottles);
            resources = Collections.unmodifiableList(machines);
        }

        int workpieceCount() { return workpieces.size(); }
        int resourceCount() { return resources.size(); }
        String[][] workpieces() { return copy(workpieces); }
        String[][] resources() { return copy(resources); }

        private static String[][] copy(List<String[]> rows) {
            String[][] result = new String[rows.size()][];
            for (int i = 0; i < rows.size(); i++) result[i] = rows.get(i).clone();
            return result;
        }
    }

    private BigInteger minimumGeneration = BigInteger.ZERO;
    private Snapshot current;

    synchronized boolean accept(String payload) {
        try {
            String[] fields = payload == null ? new String[0] : payload.split("\\|", -1);
            if (fields.length != 9 || !"V2".equals(fields[0]) || !"TWIN".equals(fields[1])) return false;
            BigInteger generation = unsigned(fields[2]);
            long sequence = unsigned(fields[3]).longValueExact();
            if (generation.compareTo(minimumGeneration) < 0) return false;
            if (current != null && (generation.compareTo(current.generation) < 0 ||
                (generation.equals(current.generation) && sequence <= current.sequence))) return false;
            int w = number(fields[4], "W=");
            int r = number(fields[5], "R=");
            int rejected = number(fields[6], "REJECTED=");
            List<String[]> bottles = rows(fields[7], "WORKPIECES=", 6);
            List<String[]> machines = rows(fields[8], "RESOURCES=", 7);
            if (bottles.size() != w || machines.size() != r) return false;
            for (String[] row : bottles) {
                if (!row[1].matches("CREATED|LOADED|P1|FILLED|LIDDED|CAPPED|P6|LABELLED|UNLOADED|SORTED|COMPLETE|FAULT")) return false;
                unsigned(row[3]);
                if (!("S".equals(row[4]) && "200".equals(row[5])) &&
                    !("L".equals(row[4]) && "500".equals(row[5]))) return false;
            }
            for (String[] row : machines) {
                int status = unsigned(row[3]).intValueExact();
                if (status > 4) return false;
                unsigned(row[6]);
            }
            current = new Snapshot(generation, sequence, rejected, bottles, machines);
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    synchronized void observeReset(String resetId) {
        if (resetId == null || !resetId.matches("RST[0-9]{4,}")) return;
        BigInteger epoch = new BigInteger(resetId.substring(3)).add(BigInteger.ONE);
        if (epoch.compareTo(minimumGeneration) <= 0) return;
        minimumGeneration = epoch;
        if (current != null && current.generation.compareTo(epoch) < 0) current = null;
    }

    synchronized Snapshot snapshot() { return current; }

    private static BigInteger unsigned(String value) {
        if (!value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("unsigned number required");
        return new BigInteger(value);
    }

    private static int number(String field, String prefix) {
        if (!field.startsWith(prefix)) throw new IllegalArgumentException("missing " + prefix);
        return unsigned(field.substring(prefix.length())).intValueExact();
    }

    private static List<String[]> rows(String field, String prefix, int columns) throws Exception {
        if (!field.startsWith(prefix)) throw new IllegalArgumentException("missing " + prefix);
        List<String[]> result = new ArrayList<String[]>();
        String body = field.substring(prefix.length());
        if (body.isEmpty()) return result;
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (String encoded : body.split(";", -1)) {
            String[] cells = encoded.split(",", -1);
            if (cells.length != columns) throw new IllegalArgumentException("wrong row width");
            for (int i = 0; i < cells.length; i++) {
                cells[i] = URLDecoder.decode(cells[i], "UTF-8");
                if (cells[i].isEmpty() || cells[i].indexOf('\n') >= 0 || cells[i].indexOf('\r') >= 0)
                    throw new IllegalArgumentException("invalid cell");
            }
            if (!ids.add(cells[0])) throw new IllegalArgumentException("duplicate identity");
            result.add(cells);
        }
        return result;
    }
}
