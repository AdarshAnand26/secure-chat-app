import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class WebSocketServer {
    private static final int PORT = 8080;
    private static final String WEBSOCKET_MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static Map<String, ClientHandler> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("🚀 WebSocket Server started on port " + PORT);
        System.out.println("📍 Server URL: ws://localhost:" + PORT);
        System.out.println("🌐 For ngrok, use: ngrok http " + PORT);
        System.out.println("Waiting for connections...\n");

        while (true) {
            Socket socket = serverSocket.accept();
            System.out.println("🔗 New connection from: " + socket.getInetAddress());

            ClientHandler handler = new ClientHandler(socket);
            new Thread(handler).start();
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private String username;
        private boolean isWebSocket = false;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();

                // Read the HTTP request
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String line;
                Map<String, String> headers = new HashMap<>();
                String firstLine = reader.readLine();

                if (firstLine != null && firstLine.contains("GET")) {
                    System.out.println("📥 Request: " + firstLine);

                    // Read headers
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        String[] parts = line.split(": ", 2);
                        if (parts.length == 2) {
                            headers.put(parts[0].toLowerCase(), parts[1]);
                        }
                    }

                    // Check if it's a WebSocket upgrade request
                    String upgrade = headers.get("upgrade");
                    String connection = headers.get("connection");

                    if ("websocket".equalsIgnoreCase(upgrade) &&
                            connection != null && connection.toLowerCase().contains("upgrade")) {

                        // Perform WebSocket handshake
                        if (performHandshake(headers)) {
                            isWebSocket = true;
                            System.out.println("✅ WebSocket handshake successful");
                            listenForMessages();
                        }
                    } else {
                        // Serve the HTML file or handle regular HTTP request
                        handleHttpRequest(firstLine);
                    }
                }
            } catch (Exception e) {
                System.out.println("❌ Client disconnected: " + username);
            } finally {
                cleanup();
            }
        }

        private void handleHttpRequest(String requestLine) throws IOException {
            // Simple HTTP response for non-WebSocket requests
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: *\r\n" +
                    "\r\n" +
                    "<html><body><h1>WebSocket Chat Server</h1>" +
                    "<p>Server is running on port " + PORT + "</p>" +
                    "<p>Use WebSocket connection to chat</p></body></html>";

            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private boolean performHandshake(Map<String, String> headers) throws IOException {
            String key = headers.get("sec-websocket-key");
            if (key == null) return false;

            try {
                String acceptKey = key + WEBSOCKET_MAGIC_STRING;
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(acceptKey.getBytes(StandardCharsets.UTF_8));
                String encodedKey = Base64.getEncoder().encodeToString(digest);

                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + encodedKey + "\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "\r\n";

                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private void listenForMessages() throws IOException {
            while (true) {
                // Read WebSocket frame
                int firstByte = in.read();
                if (firstByte == -1) break;

                boolean fin = (firstByte & 0x80) != 0;
                int opcode = firstByte & 0x0F;

                if (opcode == 8) break; // Close frame

                int secondByte = in.read();
                if (secondByte == -1) break;

                boolean masked = (secondByte & 0x80) != 0;
                int payloadLength = secondByte & 0x7F;

                // Handle extended payload length
                if (payloadLength == 126) {
                    payloadLength = (in.read() << 8) | in.read();
                } else if (payloadLength == 127) {
                    // Skip 8 bytes for simplicity (we don't expect huge messages)
                    for (int i = 0; i < 8; i++) in.read();
                    payloadLength = 0; // This would need proper handling for large messages
                }

                // Read masking key if present
                byte[] maskingKey = new byte[4];
                if (masked) {
                    for (int i = 0; i < 4; i++) {
                        maskingKey[i] = (byte) in.read();
                    }
                }

                // Read payload
                byte[] payload = new byte[payloadLength];
                for (int i = 0; i < payloadLength; i++) {
                    payload[i] = (byte) in.read();
                    if (masked) {
                        payload[i] ^= maskingKey[i % 4];
                    }
                }

                String message = new String(payload, StandardCharsets.UTF_8);
                handleMessage(message);
            }
        }

        private void handleMessage(String message) throws IOException {
            try {
                // Parse JSON message
                if (message.contains("\"type\":\"join\"")) {
                    // Extract username from JSON
                    Pattern pattern = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");
                    Matcher matcher = pattern.matcher(message);
                    if (matcher.find()) {
                        username = matcher.group(1);
                        clients.put(username, this);
                        System.out.println("👤 " + username + " joined the chat");
                    }
                } else if (message.contains("\"type\":\"message\"")) {
                    // Broadcast message to all other clients
                    broadcast(message);

                    // Extract username for logging
                    Pattern pattern = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");
                    Matcher matcher = pattern.matcher(message);
                    if (matcher.find()) {
                        System.out.println("💬 " + matcher.group(1) + ": [encrypted message]");
                    }
                }
            } catch (Exception e) {
                System.out.println("Error parsing message: " + e.getMessage());
            }
        }

        private void sendWebSocketFrame(String message) throws IOException {
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            // Simple frame for text messages
            if (messageBytes.length < 126) {
                out.write(0x81); // FIN + text frame
                out.write(messageBytes.length);
            } else {
                out.write(0x81); // FIN + text frame
                out.write(126);
                out.write((messageBytes.length >> 8) & 0xFF);
                out.write(messageBytes.length & 0xFF);
            }

            out.write(messageBytes);
            out.flush();
        }

        private void broadcast(String message) {
            for (ClientHandler client : clients.values()) {
                if (client != this && client.isWebSocket) {
                    try {
                        client.sendWebSocketFrame(message);
                    } catch (IOException e) {
                        System.out.println("Failed to send message to client");
                    }
                }
            }
        }

        private void cleanup() {
            try {
                socket.close();
            } catch (IOException e) {}

            if (username != null) {
                clients.remove(username);
                System.out.println("👋 " + username + " left the chat");
            }
        }
    }
}