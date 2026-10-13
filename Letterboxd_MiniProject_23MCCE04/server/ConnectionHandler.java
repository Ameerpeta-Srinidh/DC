package server;

import java.io.*;
import java.net.Socket;

/**
 * ConnectionHandler — ONE connected peer's request/response loop, running
 * on its own pooled thread. Used for both kinds of socket connection in
 * this system:
 *   - a real client talking to a LetterboxdAppServer (LineRouter = RequestRouter)
 *   - an App Server talking to the DatabaseServer    (LineRouter = InternalRouter)
 *
 * Exactly the v1 ClientHandler's logic, generalized over WHICH router it
 * calls, so it isn't duplicated between the two server processes.
 */
public class ConnectionHandler implements Runnable {

    private final Socket socket;
    private final LineRouter router;
    private final String label;      // e.g. "App" or "DB" — just for log prefixes
    private final int connectionId;

    private static int nextId = 1;
    private static synchronized int nextId() { return nextId++; }

    public ConnectionHandler(Socket socket, LineRouter router, String label) {
        this.socket = socket;
        this.router = router;
        this.label = label;
        this.connectionId = nextId();
    }

    @Override
    public void run() {
        String peer = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        System.out.println("[" + label + "-" + connectionId + "] connected from " + peer);

        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String response = router.route(line);
                out.println(response);
            }
        } catch (IOException e) {
            System.out.println("[" + label + "-" + connectionId + "] connection error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("[" + label + "-" + connectionId + "] disconnected");
        }
    }
}
