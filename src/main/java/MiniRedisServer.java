import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class MiniRedisServer {
    public static void main(String[] args) throws  Exception{
        int port = 6379;
        try (ServerSocket serverSocket = new ServerSocket(port)){
            System.out.println("Server is Running on port: "+port);

            // OUTER LOOP: Keeps the server alive forever
            while (true) {
                System.out.println("Waiting for a client to connect...");
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected!");

                // Wrap the conversation in a try-catch!
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);

                    String clientMessage;
                    while ((clientMessage = in.readLine()) != null) {
                        System.out.println("Client said: " + clientMessage);
                        out.println("Server Received: " + clientMessage);
                    }
                    System.out.println("Client politely disconnected.");

                } catch (Exception e) {
                    // If they press Ctrl+C, we catch the "Connection reset" here instead of crashing!
                    System.out.println("Client abruptly disconnected!");
                } finally {
                    // This 'finally' block ensures we ALWAYS clean up and close the door,
                    // whether they were polite or abrupt.
                    clientSocket.close();
                }
            }
        }

    }
}
