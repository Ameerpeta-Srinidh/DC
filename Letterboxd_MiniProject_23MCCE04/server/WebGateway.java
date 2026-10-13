package server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves the browser interface locally and bridges its JSON requests to the
 * raw TCP socket API. The application tiers still communicate using
 * newline-delimited JSON over Java sockets.
 */
public class WebGateway {

    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    public static void main(String[] args) throws IOException {
        String appHost = args.length > 0 ? args[0] : "localhost";
        int appPort = args.length > 1 ? Integer.parseInt(args[1]) : 9870;
        int webPort = args.length > 2 ? Integer.parseInt(args[2]) : 8080;
        Path webRoot = Paths.get(args.length > 3 ? args[3] : "../web").toAbsolutePath().normalize();

        if (!Files.isRegularFile(webRoot.resolve("index.html"))) {
            throw new IOException("Could not find web/index.html under " + webRoot);
        }

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), webPort), 0);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        server.setExecutor(pool);
        server.createContext("/api", exchange -> handleApi(exchange, appHost, appPort));
        server.createContext("/", exchange -> servePage(exchange, webRoot));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            pool.shutdownNow();
        }));

        System.out.println("[WebGateway] Open http://localhost:" + webPort);
        System.out.println("[WebGateway] TCP application endpoint: " + appHost + ":" + appPort);
    }

    private static void handleApi(HttpExchange exchange, String host, int port) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error("Use POST for API requests."));
            return;
        }

        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            sendJson(exchange, 413, error("Request body is too large."));
            return;
        }

        JsonObject request;
        try {
            request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!request.has("type") || request.get("type").isJsonNull()) {
                sendJson(exchange, 400, error("A request type is required."));
                return;
            }
        } catch (RuntimeException e) {
            sendJson(exchange, 400, error("Request body must be a JSON object."));
            return;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            socket.setSoTimeout(8000);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                out.println(request);
                String response = in.readLine();
                if (response == null) {
                    sendJson(exchange, 502, error("Application server closed the connection."));
                } else {
                    sendJson(exchange, 200, JsonParser.parseString(response).getAsJsonObject());
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[WebGateway] Socket request failed: " + e.getMessage());
            sendJson(exchange, 502, error("Could not reach the application service. Check that the servers are running."));
        }
    }

    private static void servePage(HttpExchange exchange, Path webRoot) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String requested = exchange.getRequestURI().getPath();
        String fileName;
        if ("/".equals(requested)) {
            fileName = "index.html";
        } else if ("/app.css".equals(requested) || "/app.js".equals(requested)) {
            fileName = requested.substring(1);
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        Path file = webRoot.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        String contentType = fileName.endsWith(".css") ? "text/css; charset=utf-8"
                : fileName.endsWith(".js") ? "text/javascript; charset=utf-8"
                : "text/html; charset=utf-8";
        byte[] content = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static JsonObject error(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ERROR");
        response.addProperty("message", message);
        return response;
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject response) throws IOException {
        byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
