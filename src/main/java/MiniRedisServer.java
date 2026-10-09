import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class MiniRedisServer {

    // ========================== STORAGE ==========================
    // The heart of Redis — an in-memory key-value store.
    // ConcurrentHashMap because multiple client threads read/write simultaneously.
    // 'static' = one shared store for the entire server (not per-client).
    // 'final' = the reference can't be reassigned, but we can still put/get/remove.
    private static final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    // Stores expiry timestamps for keys. Key → time in milliseconds when it should die.
    // Only keys with TTL appear here. No entry = lives forever.
    private static final ConcurrentHashMap<String, Long> expiryMap = new ConcurrentHashMap<>();

    // ========================== MAIN / ACCEPT LOOP ==========================
    // Entry point — starts the TCP server and listens for client connections forever.
    public static void main(String[] args) throws Exception {
        int port = 6379;  // Redis's default port

        // Thread pool that creates a new thread per client, reuses idle ones,
        // and kills threads idle for 60 seconds.
        ExecutorService threadPool = Executors.newCachedThreadPool();

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();

            for(String key : expiryMap.keySet()) {
                Long expiresAt = expiryMap.get(key);

                if(expiresAt != null && now > expiresAt) {
                    store.remove(key);
                    expiryMap.remove(key);
                    System.out.println("Key expired and removed: "+key);
                }
            }
        }, 1,1, TimeUnit.SECONDS);
        // try-with-resources: auto-closes the ServerSocket when the program exits
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("MinRedis is running on port " + port);

            // Infinite loop — a server never stops on its own
            while (true) {
                // BLOCKS here until a client connects via TCP
                Socket clientSocket = serverSocket.accept();

                // Hand the client to a separate thread so we can immediately
                // go back to accept() and wait for the next client
                threadPool.submit(() -> handleClient(clientSocket));
            }
        }
    }

    // ========================== CLIENT HANDLER ==========================
    // Runs on a SEPARATE THREAD for each connected client.
    // Reads commands in a loop, processes them, sends back RESP responses.
    private static void handleClient(Socket clientSocket) {
        try {
            // Wrap the raw TCP input stream in a BufferedReader for line-by-line reading
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            // Wrap the raw TCP output stream in a PrintWriter for easy writing
            // 'true' = auto-flush on println() (but we use print() + manual flush())
            OutputStream out = clientSocket.getOutputStream();

            // Conversation loop — keeps talking to this client until they disconnect
            while (true) {

                // Read and parse the next command from the client
                List<String> command = parseRespCommand(in);

                // readLine() returns null when the client closes the connection
                if (command == null) {
                    break;
                }

                // Debug log (server-side only — client doesn't see this)
                System.out.println("Client sent command: " + command);

                // Route the command to the appropriate handler and get the RESP response
                String response = handleCommand(command);

                // Send the RESP-formatted response back to the client
                // Using print() not println() because RESP strings already contain \r\n
                out.write(response.getBytes());

                // Force the buffered data to travel over the network immediately
                // (auto-flush only works with println, not print)
                out.flush();
            }
        } catch (Exception e) {
            // If a client crashes or disconnects mid-command, we catch it here.
            // Other clients keep working — one client's crash doesn't kill the server.
            System.out.println("Client abruptly disconnected!");
        } finally {
            // ALWAYS close the socket — even if an exception happened.
            // This frees up the port and prevents resource leaks.
            try {
                clientSocket.close();
            } catch (Exception e) {}
        }
    }

    // ========================== RESP PARSER ==========================
    // Reads raw bytes from the TCP stream and converts them into a structured command.
    // Supports two formats:
    //   1. Plain text (telnet): "SET name prakash" → ["SET", "name", "prakash"]
    //   2. RESP protocol (redis-cli): *3\r\n$3\r\nSET\r\n$4\r\nname\r\n$7\r\nprakash\r\n → ["SET", "name", "prakash"]
    private static List<String> parseRespCommand(BufferedReader in) throws Exception {
        String line = in.readLine();

        // null = client closed connection (TCP stream ended)
        if (line == null)
            return null;

        // If it doesn't start with '*', it's plain text (telnet mode)
        // Just split by spaces and return
        if (!line.startsWith("*")) {
            return List.of(line.split(" "));
        }

        // RESP mode: first line is "*N" where N = number of arguments
        // Example: "*3" means 3 words are coming (SET, name, prakash)
        int numWords = Integer.parseInt(line.substring(1));
        List<String> commandArgs = new ArrayList<>();

        for (int i = 0; i < numWords; i++) {
            // Skip the "$N" line (byte length prefix) — we don't need it
            // because readLine() already handles line boundaries
            in.readLine();

            // Read the actual word (e.g., "SET", "name", "prakash")
            String word = in.readLine();
            commandArgs.add(word);
        }

        return commandArgs;
    }

    // ========================== RESP RESPONSE WRITERS ==========================
    // These methods convert Java values into RESP wire format.
    // Every response sent to the client goes through one of these 4 methods.

    // Simple String: +OK\r\n — used for status messages (SET success, PONG)
    private static String simpleString(String msg) {
        return "+" + msg + "\r\n";
    }

    // Error: -ERR message\r\n — used when something goes wrong
    private static String error(String msg) {
        return "-ERR " + msg + "\r\n";
    }

    // Integer: :1\r\n — used for numeric responses (DEL count, EXISTS result)
    private static String integer(int num) {
        return ":" + num + "\r\n";
    }

    // Bulk String: $7\r\nprakash\r\n — used for data values (GET result)
    // Returns $-1\r\n for null (key doesn't exist)
    private static String bulkString(String value) {
        if (value == null) {
            return "$-1\r\n";
        }
        return "$" + value.length() + "\r\n" + value + "\r\n";
    }

    // ========================== COMMAND ROUTER ==========================
    // Takes a parsed command like ["SET", "name", "prakash"] and:
    //   1. Identifies which command it is (SET, GET, DEL, etc.)
    //   2. Validates the number of arguments
    //   3. Executes the command on the store
    //   4. Returns a RESP-formatted response string
    public static String handleCommand(List<String> command) {
        String cmd = command.get(0).toUpperCase();  // case-insensitive: "set" → "SET"

        switch (cmd) {
            case "PING":
                // Health check — always returns PONG
                return simpleString("PONG");

            case "SET":
                // SET key value [EX seconds] — stores key-value pair
                if (command.size() < 3)
                    return error("wrong number of arguments for 'SET'");

                String key = command.get(1);
                String value = command.get(2);
                store.put(key, value);

                // Check if EX (expiry) is provided: SET name prakash EX 30
                if (command.size() == 5 && command.get(3).equalsIgnoreCase("EX")) {
                    int seconds = Integer.parseInt(command.get(4));
                    // Store the exact timestamp when this key should die
                    // System.currentTimeMillis() = current time in ms since 1970
                    // + (seconds * 1000) = add the TTL in milliseconds
                    expiryMap.put(key, System.currentTimeMillis() + (seconds * 1000L));
                }

                return simpleString("OK");

            case "GET":
                if (command.size() < 2)
                    return error("wrong number of arguments for 'GET'");

                // Lazy expiry — check if key has expired before returning
                String getKey = command.get(1);
                Long expiresAt = expiryMap.get(getKey);
                if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
                    // Key is dead — clean up and return null
                    store.remove(getKey);
                    expiryMap.remove(getKey);
                    return bulkString(null);
                }
                return bulkString(store.get(getKey));

            case "DEL":
                // DEL key — removes key from the HashMap
                // Returns :1 if key existed, :0 if it didn't
                if (command.size() < 2)
                    return error("wrong number of arguments for 'DEL'");

                String removed = store.remove(command.get(1));
                return integer(removed != null ? 1 : 0);

            case "EXISTS":
                // EXISTS key — checks if key exists in the HashMap
                // Returns :1 if exists, :0 if not
                if (command.size() < 2)
                    return error("wrong number of arguments for 'EXISTS'");

                return integer(store.containsKey(command.get(1)) ? 1 : 0);

            case "KEYS":
                //Returns : all the keys that are present in the store

                StringBuilder sb = new StringBuilder();
                sb.append("*").append(store.size()).append("\r\n");

                for(String k : store.keySet()) {
                    sb.append("$").append(k.length()).append("\r\n");
                    sb.append(k).append("\r\n");
                }
                return sb.toString();
            default:
                // Unknown command — return error
                return error("unknown command '" + cmd + "'");
        }
    }
}
