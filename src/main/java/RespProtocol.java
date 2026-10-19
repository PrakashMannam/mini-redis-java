import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * RespProtocol handles parsing incoming bytes from clients
 * and formatting responses into the Redis Serialization Protocol (RESP).
 */
public class RespProtocol {

    // ========================== RESP PARSER ==========================
    // Supports both plain text (telnet) and binary-safe RESP (*N\r\n$len\r\n...)
    public static List<String> parseCommand(BufferedReader in) throws Exception {
        String line = in.readLine();
        if (line == null) {
            return null; // Client closed connection
        }

        line = line.trim();
        if (line.isEmpty()) {
            return List.of();
        }

        // Plain text fallback (e.g. from telnet typing)
        if (!line.startsWith("*")) {
            return List.of(line.split("\\s+"));
        }

        // RESP protocol array format: *<numArgs>
        int numWords = Integer.parseInt(line.substring(1));
        List<String> commandArgs = new ArrayList<>(numWords);

        for (int i = 0; i < numWords; i++) {
            in.readLine(); // skip length prefix line ($N)
            String word = in.readLine();
            commandArgs.add(word);
        }

        return commandArgs;
    }

    // ========================== RESP RESPONSE FORMATTERS ==========================

    public static String simpleString(String msg) {
        return "+" + msg + "\r\n";
    }

    public static String error(String msg) {
        return "-ERR " + msg + "\r\n";
    }

    public static String integer(long num) {
        return ":" + num + "\r\n";
    }

    public static String bulkString(String value) {
        if (value == null) {
            return "$-1\r\n";
        }
        return "$" + value.length() + "\r\n" + value + "\r\n";
    }

    public static String array(Collection<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(items.size()).append("\r\n");
        for (String item : items) {
            sb.append("$").append(item.length()).append("\r\n");
            sb.append(item).append("\r\n");
        }
        return sb.toString();
    }
}
