package server;

import data.PrimaryDatabase;
import data.ReplicaDatabase;
import data.ReplicationManager;
import data.SessionManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DatabaseServer — the DATA TIER. One process, owns the ONLY PrimaryDatabase
 * in the whole system (so writes are always safely serialized — see
 * PrimaryDatabase's class comment) plus a ReplicaDatabase for reads and a
 * ReplicationManager that asynchronously mirrors primary writes onto it.
 *
 * Every LetterboxdAppServer instance (there can be many, see
 * LetterboxdAppServer.java) is a CLIENT of this process, connecting over a
 * plain TCP socket exactly the way end users connect to an App Server —
 * same accept-loop-plus-thread-pool shape, just one layer further down.
 *
 * ARCHITECTURE THIS DEMONSTRATES:
 *   data tier            (this process)   — the only primary, the only
 *                                            "source of truth"
 *   app tier              (N processes)    — stateless, horizontally
 *                                            scalable, can be restarted or
 *                                            added/removed freely since
 *                                            they hold no data of their own
 *   load-balancer tier    (1 process)      — spreads client connections
 *                                            across the app tier (see
 *                                            LoadBalancer.java)
 *
 * USAGE:
 *   java -cp ".;lib/*" server.DatabaseServer [port] [primaryFile] [replicaFile] [lagMs]
 *   Defaults: 9800, primary.db, replica.db, 1500
 */
public class DatabaseServer {

    private static final int DEFAULT_PORT = 9800;
    private static final int THREAD_POOL_SIZE = 10;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String primaryFile = args.length > 1 ? args[1] : "primary.db";
        String replicaFile = args.length > 2 ? args[2] : "replica.db";
        long lagMillis = args.length > 3 ? Long.parseLong(args[3]) : 1500;

        PrimaryDatabase primary = new PrimaryDatabase(primaryFile);
        ReplicaDatabase replica = new ReplicaDatabase(replicaFile);
        ReplicationManager replication = new ReplicationManager(replica, lagMillis);
        replication.start();
        SessionManager sessions = new SessionManager();

        InternalRouter router = new InternalRouter(primary, replica, replication, sessions);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[DatabaseServer] Shutting down...");
            replication.stop();
            pool.shutdownNow();
            primary.close();
            replica.close();
        }));

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("================================================");
            System.out.println(" DATABASE SERVER (data tier) running on port " + port);
            System.out.println(" Primary: " + primaryFile + "   Replica: " + replicaFile +
                    "   Simulated lag: " + lagMillis + "ms");
            System.out.println("================================================");

            while (true) {
                Socket appServerSocket = serverSocket.accept();
                pool.submit(new ConnectionHandler(appServerSocket, router, "DB"));
            }
        } catch (IOException e) {
            System.err.println("[DatabaseServer] Fatal: " + e.getMessage());
        }
    }
}
