package server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LoadBalancer — a pure RAW-SOCKET reverse proxy. This is the socket-
 * programming-heaviest file in the whole project: it never looks at the
 * JSON protocol at all, never parses a line — it just relays raw bytes in
 * both directions between whichever client connects to it and whichever
 * App Server instance it picks next.
 *
 * THIS is what a real load balancer (nginx, HAProxy, an AWS ELB) does at
 * its core: accept a connection, choose a backend, shuttle bytes until
 * either side closes. Everything above (HTTP routing, health checks,
 * TLS termination) is an elaboration of exactly this loop.
 *
 * STRATEGY: round-robin. Each new client connection is assigned the next
 * backend in the list, wrapping around — a simple, stateless, fair policy.
 * (A fancier version would track active-connection counts per backend for
 * "least connections" routing — noted as an extension, not implemented
 * here, to keep the core loop easy to follow.)
 *
 * PER-CONNECTION THREADING: for each client, we open ONE socket to the
 * chosen backend, then spin up TWO pump threads:
 *   clientSocket -> backendSocket   (forward the request bytes)
 *   backendSocket -> clientSocket   (forward the response bytes)
 * Both run concurrently because a connection is a conversation: the
 * backend can be sending a response while the client is still mid-way
 * through writing a later request (and with our line-based protocol the
 * two directions are logically independent streams of bytes).
 *
 * USAGE:
 *   java -cp ".;lib/*" server.LoadBalancer [lbPort] [backendHost:port,backendHost:port,...]
 *   Default: 9870, localhost:9876,localhost:9877,localhost:9878
 */
public class LoadBalancer {

    private static final int BUFFER_SIZE = 4096;

    public static void main(String[] args) throws IOException {
        int lbPort = args.length > 0 ? Integer.parseInt(args[0]) : 9870;
        String backendsArg = args.length > 1 ? args[1] : "localhost:9876,localhost:9877,localhost:9878";

        List<InetSocketAddress> backends = new ArrayList<>();
        for (String entry : backendsArg.split(",")) {
            String[] parts = entry.trim().split(":");
            backends.add(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
        }

        AtomicInteger nextBackend = new AtomicInteger(0);
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(lbPort)) {
            System.out.println("================================================");
            System.out.println(" LOAD BALANCER listening on port " + lbPort);
            System.out.println(" Backends (round-robin): " + backends);
            System.out.println("================================================");

            while (true) {
                Socket clientSocket = serverSocket.accept();

                // Round-robin pick: atomically advance a shared counter, wrap by backend count.
                // getAndIncrement() is used (not a plain `i++`) specifically because MULTIPLE
                // accept-loop... well, there's only one accept loop here, but many CLIENT
                // connections can race to pick their backend concurrently once each is handed
                // off to the thread pool below — AtomicInteger keeps that increment lock-free
                // and still race-free (compare-and-swap under the hood), the modern alternative
                // to wrapping a plain int in `synchronized`.
                int index = Math.floorMod(nextBackend.getAndIncrement(), backends.size());
                InetSocketAddress backend = backends.get(index);

                System.out.println("[LB] " + clientSocket.getRemoteSocketAddress() + " -> " + backend);
                pool.submit(() -> proxy(clientSocket, backend));
            }
        }
    }

    private static void proxy(Socket clientSocket, InetSocketAddress backendAddress) {
        try (Socket backendSocket = new Socket(backendAddress.getHostString(), backendAddress.getPort())) {

            Thread forward = pump(clientSocket, backendSocket, "client->backend");
            Thread backward = pump(backendSocket, clientSocket, "backend->client");

            forward.join();
            backward.join();

        } catch (IOException e) {
            System.out.println("[LB] could not reach backend " + backendAddress + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    /** Starts (and returns) a thread that copies raw bytes from `from` to `to` until
     *  one side closes. This is the literal mechanism of "relaying a connection" —
     *  no JSON parsing, no line framing, just bytes. */
    private static Thread pump(Socket from, Socket to, String label) {
        Thread t = new Thread(() -> {
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                // normal when either side closes the connection
            } finally {
                try { to.shutdownOutput(); } catch (IOException ignored) {}
            }
        }, label);
        t.start();
        return t;
    }
}
