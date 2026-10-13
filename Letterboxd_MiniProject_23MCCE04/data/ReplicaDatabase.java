package data;

import model.DiaryEntry;
import model.Film;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * ReplicaDatabase — the read side of the primary/replica split.
 *
 * CONCURRENCY MODEL — a real connection POOL, not one synchronized lock:
 *   v1's Database serialized every operation (reads AND writes) behind one
 *   `synchronized` keyword — a single reader could block every other
 *   reader even though reads don't conflict with each other. Here we
 *   instead keep POOL_SIZE independent JDBC Connections open to replica.db
 *   and hand them out from a BlockingQueue: up to POOL_SIZE threads can run
 *   SELECTs truly in parallel (SQLite itself allows many concurrent
 *   readers against one file as long as nobody is writing it, which is
 *   exactly our situation — this class never writes).
 *
 * EVENTUAL CONSISTENCY:
 *   This file is NOT updated synchronously when the primary is written —
 *   ReplicationManager copies rows over on a background thread, after an
 *   artificial delay. So a read through this class can legitimately be a
 *   few hundred milliseconds stale. That staleness is the whole point:
 *   it's the same trade-off a real PostgreSQL read-replica makes.
 */
public class ReplicaDatabase {

    private static final int POOL_SIZE = 4;
    private final BlockingQueue<Connection> pool = new ArrayBlockingQueue<>(POOL_SIZE);
    private final String dbFile;

    public ReplicaDatabase(String dbFile) {
        this.dbFile = dbFile;
        try {
            for (int i = 0; i < POOL_SIZE; i++) {
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                pool.put(c);
            }
            Connection c = borrow();
            initializeSchema(c);
            seedFilmCatalog(c);
            giveBack(c);
            System.out.println("[Replica] Schema ready, film catalog seeded.");
        } catch (Exception e) {
            throw new RuntimeException("[Replica] Could not initialize: " + e.getMessage(), e);
        }
        System.out.println("[Replica] Ready — " + dbFile + " (pool size " + POOL_SIZE + ")");
    }

    /** Checkout a connection from the pool. Blocks if all POOL_SIZE are currently in use
     *  (this is the back-pressure mechanism — it naturally caps concurrent read load). */
    private Connection borrow() {
        try {
            Connection c = pool.take();
            return c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for a replica connection", e);
        }
    }

    private void giveBack(Connection c) {
        try { pool.put(c); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void initializeSchema(Connection c) {
        try {
            Statement stmt = c.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "  username TEXT PRIMARY KEY, password_hash TEXT NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS films (" +
                    "  id INTEGER PRIMARY KEY, title TEXT NOT NULL, year INTEGER NOT NULL," +
                    "  director TEXT NOT NULL, genre TEXT NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS diary_entries (" +
                    "  id INTEGER PRIMARY KEY, username TEXT NOT NULL, film_id INTEGER NOT NULL," +
                    "  rating REAL NOT NULL, review TEXT, date TEXT NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS user_feed (" +
                    "  owner TEXT NOT NULL, entry_id INTEGER NOT NULL," +
                    "  PRIMARY KEY (owner, entry_id))");
            stmt.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The film catalog is static reference data (never written by end users), so rather
     * than route it through the asynchronous ReplicationManager pipeline, the replica just
     * seeds its own copy directly at startup — the same pragmatic shortcut a real system
     * takes for rarely-changing reference/lookup tables (often denormalized onto every
     * node, or served from a cache, precisely because replicating it on every write would
     * be wasted effort for data that essentially never changes).
     */
    private void seedFilmCatalog(Connection c) {
        try {
            Statement check = c.createStatement();
            ResultSet rs = check.executeQuery("SELECT COUNT(*) FROM films");
            rs.next();
            int count = rs.getInt(1);
            rs.close();
            check.close();
            if (count > 0) return;

            Object[][] films = {
                {1,  "The Shawshank Redemption",           1994, "Frank Darabont",       "Drama"},
                {2,  "The Godfather",                       1972, "Francis Ford Coppola", "Crime"},
                {3,  "The Dark Knight",                     2008, "Christopher Nolan",    "Action"},
                {4,  "Pulp Fiction",                        1994, "Quentin Tarantino",    "Crime"},
                {5,  "Forrest Gump",                        1994, "Robert Zemeckis",      "Drama"},
                {6,  "Inception",                           2010, "Christopher Nolan",    "Sci-Fi"},
                {7,  "The Matrix",                          1999, "The Wachowskis",       "Sci-Fi"},
                {8,  "Goodfellas",                          1990, "Martin Scorsese",      "Crime"},
                {9,  "Fight Club",                          1999, "David Fincher",        "Drama"},
                {10, "Interstellar",                        2014, "Christopher Nolan",    "Sci-Fi"},
                {11, "Parasite",                            2019, "Bong Joon-ho",         "Thriller"},
                {12, "Spirited Away",                       2001, "Hayao Miyazaki",       "Animation"},
                {13, "The Grand Budapest Hotel",            2014, "Wes Anderson",         "Comedy"},
                {14, "Whiplash",                            2014, "Damien Chazelle",      "Drama"},
                {15, "Amelie",                              2001, "Jean-Pierre Jeunet",   "Romance"},
                {16, "La La Land",                          2016, "Damien Chazelle",      "Musical"},
                {17, "The Social Network",                  2010, "David Fincher",        "Drama"},
                {18, "Get Out",                             2017, "Jordan Peele",         "Horror"},
                {19, "Mad Max: Fury Road",                  2015, "George Miller",        "Action"},
                {20, "Moonlight",                           2016, "Barry Jenkins",        "Drama"},
                {21, "Everything Everywhere All at Once",   2022, "Daniels",              "Sci-Fi"},
                {22, "Dune",                                2021, "Denis Villeneuve",     "Sci-Fi"},
                {23, "Oppenheimer",                         2023, "Christopher Nolan",    "Drama"},
                {24, "Spider-Man: Into the Spider-Verse",   2018, "Bob Persichetti",      "Animation"},
                {25, "The Truman Show",                     1998, "Peter Weir",           "Drama"},
                {26, "Eternal Sunshine of the Spotless Mind",2004,"Michel Gondry",        "Romance"},
                {27, "No Country for Old Men",              2007, "Coen Brothers",        "Thriller"},
                {28, "2001: A Space Odyssey",               1968, "Stanley Kubrick",      "Sci-Fi"},
                {29, "Blade Runner 2049",                   2017, "Denis Villeneuve",     "Sci-Fi"},
                {30, "Past Lives",                          2023, "Celine Song",          "Romance"},
                {31, "Seven Samurai",                       1954, "Akira Kurosawa",       "Action"},
                {32, "How to Lose a Guy in 10 Days",         2003, "Donald Petrie",        "Romance"},
                {33, "Dead Poets Society",                   1989, "Peter Weir",           "Drama"},
            };
            PreparedStatement ps = c.prepareStatement(
                "INSERT INTO films (id, title, year, director, genre) VALUES (?, ?, ?, ?, ?)");
            for (Object[] f : films) {
                ps.setInt(1, (int) f[0]);
                ps.setString(2, (String) f[1]);
                ps.setInt(3, (int) f[2]);
                ps.setString(4, (String) f[3]);
                ps.setString(5, (String) f[4]);
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[Replica] seedFilmCatalog error: " + e.getMessage());
        }
    }

    // ─── Reads — these are what client requests actually hit most often ──

    public List<Film> searchFilms(String query) {
        Connection c = borrow();
        List<Film> results = new ArrayList<>();
        try {
            PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM films WHERE LOWER(title) LIKE LOWER(?) ORDER BY title");
            ps.setString(1, "%" + query + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new Film(rs.getInt("id"), rs.getString("title"), rs.getInt("year"),
                        rs.getString("director"), rs.getString("genre")));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[Replica] searchFilms error: " + e.getMessage());
        } finally {
            giveBack(c);
        }
        return results;
    }

    public List<Film> getAllFilms() {
        Connection c = borrow();
        List<Film> films = new ArrayList<>();
        try {
            Statement stmt = c.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM films ORDER BY title");
            while (rs.next()) {
                films.add(new Film(rs.getInt("id"), rs.getString("title"), rs.getInt("year"),
                        rs.getString("director"), rs.getString("genre")));
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            System.err.println("[Replica] getAllFilms error: " + e.getMessage());
        } finally {
            giveBack(c);
        }
        return films;
    }

    public List<DiaryEntry> getDiary(String username) {
        Connection c = borrow();
        List<DiaryEntry> entries = new ArrayList<>();
        try {
            PreparedStatement ps = c.prepareStatement(
                "SELECT d.id, d.username, d.film_id, f.title AS film_title, d.rating, d.review, d.date " +
                "FROM diary_entries d JOIN films f ON d.film_id = f.id " +
                "WHERE d.username = ? ORDER BY d.date DESC, d.id DESC");
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entries.add(new DiaryEntry(rs.getInt("id"), rs.getString("username"), rs.getInt("film_id"),
                        rs.getString("film_title"), rs.getDouble("rating"), rs.getString("review"), rs.getString("date")));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[Replica] getDiary error: " + e.getMessage());
        } finally {
            giveBack(c);
        }
        return entries;
    }

    /** O(1)-ish indexed lookup against the precomputed user_feed table — this is the payoff
     *  of the push fan-out done in PrimaryDatabase.logFilm(): no join/subquery needed here. */
    public List<DiaryEntry> getFeed(String username) {
        Connection c = borrow();
        List<DiaryEntry> entries = new ArrayList<>();
        try {
            PreparedStatement ps = c.prepareStatement(
                "SELECT d.id, d.username, d.film_id, f.title AS film_title, d.rating, d.review, d.date " +
                "FROM user_feed uf " +
                "JOIN diary_entries d ON uf.entry_id = d.id " +
                "JOIN films f ON d.film_id = f.id " +
                "WHERE uf.owner = ? ORDER BY d.date DESC, d.id DESC LIMIT 20");
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entries.add(new DiaryEntry(rs.getInt("id"), rs.getString("username"), rs.getInt("film_id"),
                        rs.getString("film_title"), rs.getDouble("rating"), rs.getString("review"), rs.getString("date")));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[Replica] getFeed error: " + e.getMessage());
        } finally {
            giveBack(c);
        }
        return entries;
    }

    public boolean userExists(String username) {
        Connection c = borrow();
        try {
            PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users WHERE username = ?");
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            rs.next();
            boolean exists = rs.getInt(1) > 0;
            rs.close();
            ps.close();
            return exists;
        } catch (SQLException e) {
            return false;
        } finally {
            giveBack(c);
        }
    }

    // ─── Used only by ReplicationManager to apply mirrored writes ───────

    /** Package-private: ReplicationManager is the only writer that is ever allowed to touch
     *  this file, and it does so through this one chokepoint method. */
    void applyMirroredWrite(String sql, Object... params) {
        Connection c = borrow();
        try {
            PreparedStatement ps = c.prepareStatement(sql);
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[Replica] mirrored write failed: " + e.getMessage());
        } finally {
            giveBack(c);
        }
    }

    public void close() {
        for (Connection c : pool) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }
}
