import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MiniRedisServer is the network entry point.
 * Listens on port 6379, accepts TCP socket connections,
 * and delegates client handling to a cached thread pool.
 */
public class MiniRedisServer {

    public static void main(String[] args) throws Exception {
        int port = 6379;
        ExecutorService threadPool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("MiniRedis is running on port " + port);

            // Infinite loop: accepts incoming TCP connections
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            }
        }
    }

    private static void handleClient(Socket clientSocket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            OutputStream out = clientSocket.getOutputStream();

            while (true) {
                // Read and parse command
                List<String> command = RespProtocol.parseCommand(in);
                if (command == null) {
                    break; // Client disconnected
                }
                if (command.isEmpty()) {
                    continue; // Skip blank lines
                }

                System.out.println("Client sent command: " + command);

                // Execute command and send RESP formatted response
                String response = CommandRouter.execute(command);
                out.write(response.getBytes());
                out.flush();
            }
        } catch (Exception e) {
            System.out.println("Client abruptly disconnected!");
        } finally {
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }
}
