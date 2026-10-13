package data;

import model.DiaryEntry;

import java.util.List;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * ReplicationManager — the asynchronous PRIMARY -> REPLICA mirror.
 *
 * This is the piece that makes the system EVENTUALLY consistent instead of
 * immediately consistent: every successful write against PrimaryDatabase is
 * turned into a small replication task and dropped onto a DelayQueue with
 * an artificial "network lag" (REPLICATION_LAG_MS). A single background
 * thread pulls tasks off the queue — a task only becomes eligible to be
 * taken once its delay has elapsed — and applies them to ReplicaDatabase.
 *
 * Why a DelayQueue specifically: it's a blocking queue that orders its
 * elements by "time until ready" and simply refuses to hand out an element
 * before that time arrives — exactly the semantics of "apply this write,
 * but not until N milliseconds have passed", with no manual
 * sleep()/timer bookkeeping needed.
 *
 * Turn REPLICATION_LAG_MS up (e.g. to 3000) and you can literally watch,
 * in the server logs, a GET_FEED issued immediately after a LOG_FILM
 * NOT yet show the new entry — then show it a few seconds later. That is
 * eventual consistency made observable, not just a comment in the code.
 */
public class ReplicationManager {

    private final ReplicaDatabase replica;
    private final long lagMillis;
    private final DelayQueue<DelayedTask> queue = new DelayQueue<>();
    private volatile boolean running = false;
    private Thread worker;

    public ReplicationManager(ReplicaDatabase replica, long lagMillis) {
        this.replica = replica;
        this.lagMillis = lagMillis;
    }

    public void start() {
        running = true;
        worker = new Thread(this::runLoop, "replication-worker");
        worker.setDaemon(true);
        worker.start();
        System.out.println("[Replication] Worker started (simulated lag = " + lagMillis + "ms)");
    }

    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    private void runLoop() {
        while (running) {
            try {
                DelayedTask task = queue.take();   // blocks until a task's delay has elapsed
                task.action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[Replication] task failed: " + e.getMessage());
            }
        }
    }

    private void enqueue(Runnable action) {
        queue.put(new DelayedTask(action, lagMillis));
    }

    // ─── High-level replication operations — one per PrimaryDatabase write ──

    public void replicateUserRegistered(String username, String passwordHash) {
        enqueue(() -> replica.applyMirroredWrite(
            "INSERT OR IGNORE INTO users (username, password_hash) VALUES (?, ?)",
            username, passwordHash));
    }

    public void replicateFilmLogged(PrimaryDatabase.LogFilmResult result) {
        DiaryEntry e = result.entry;
        enqueue(() -> replica.applyMirroredWrite(
            "INSERT OR IGNORE INTO diary_entries (id, username, film_id, rating, review, date) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            e.getId(), e.getUsername(), e.getFilmId(), e.getRating(), e.getReview(), e.getDate()));

        for (String follower : result.fannedOutTo) {
            enqueue(() -> replica.applyMirroredWrite(
                "INSERT OR IGNORE INTO user_feed (owner, entry_id) VALUES (?, ?)",
                follower, e.getId()));
        }
    }

    public void replicateFollow(String follower, String followee, List<Integer> backfilledEntryIds) {
        // (follows table lives only on the primary in this design — the replica only
        //  needs the DENORMALIZED user_feed rows to answer GET_FEED, which is exactly
        //  what we mirror here: the backfill results of the follow, not the follow edge itself.)
        for (int entryId : backfilledEntryIds) {
            enqueue(() -> replica.applyMirroredWrite(
                "INSERT OR IGNORE INTO user_feed (owner, entry_id) VALUES (?, ?)",
                follower, entryId));
        }
    }

    public void replicateUnfollow(String follower, String followee) {
        enqueue(() -> replica.applyMirroredWrite(
            "DELETE FROM user_feed WHERE owner = ? AND entry_id IN " +
            "(SELECT id FROM diary_entries WHERE username = ?)",
            follower, followee));
    }

    // ─── DelayQueue plumbing ──────────────────────────────────────────────

    private static class DelayedTask implements Delayed {
        final Runnable action;
        final long readyAtNanos;

        DelayedTask(Runnable action, long delayMillis) {
            this.action = action;
            this.readyAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(readyAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(readyAtNanos, ((DelayedTask) other).readyAtNanos);
        }
    }
}
