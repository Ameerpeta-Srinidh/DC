package data;

import model.DiaryEntry;
import model.Film;
import model.User;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PrimaryDatabase — the ONE writer in the system.
 *
 * Only the DatabaseServer process ever constructs this class, and only one
 * instance of it ever exists for the whole distributed system. This is what
 * makes concurrent WRITES safe even though we're now running multiple
 * app-server processes (see LetterboxdAppServer) in front of it: no matter
 * how many app servers accept client connections, they all funnel writes
 * through DatabaseClient -> DatabaseServer -> this one PrimaryDatabase.
 *
 * This is the direct analogue of a single-primary PostgreSQL setup: many
 * stateless app servers, one primary database that is the source of truth.
 *
 * PUSH FAN-OUT:
 *   logFilm() does not just insert a diary_entries row — it also fans the
 *   entry out into a precomputed `user_feed` row for every follower, right
 *   here, synchronously, inside the write. This is the "push on write"
 *   feed-generation model (as opposed to v1's "pull on read" GET_FEED
 *   subquery). GET_FEED on the replica now becomes a trivial indexed
 *   SELECT instead of a join+subquery.
 */
public class PrimaryDatabase {

    private final Connection connection;

    public PrimaryDatabase(String dbFile) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            connection.setAutoCommit(true);
            initializeSchema();
            seedFilmCatalog();
            System.out.println("[Primary] Ready — " + dbFile);
        } catch (SQLException e) {
            throw new RuntimeException("[Primary] Could not initialize: " + e.getMessage(), e);
        }
    }

    private void initializeSchema() throws SQLException {
        Statement stmt = connection.createStatement();
        stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                "  username TEXT PRIMARY KEY," +
                "  password_hash TEXT NOT NULL)");
        stmt.execute("CREATE TABLE IF NOT EXISTS films (" +
                "  id INTEGER PRIMARY KEY, title TEXT NOT NULL, year INTEGER NOT NULL," +
                "  director TEXT NOT NULL, genre TEXT NOT NULL)");
        stmt.execute("CREATE TABLE IF NOT EXISTS diary_entries (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL," +
                "  film_id INTEGER NOT NULL, rating REAL NOT NULL, review TEXT, date TEXT NOT NULL," +
                "  FOREIGN KEY (username) REFERENCES users(username)," +
                "  FOREIGN KEY (film_id) REFERENCES films(id))");
        stmt.execute("CREATE TABLE IF NOT EXISTS follows (" +
                "  follower TEXT NOT NULL, followee TEXT NOT NULL," +
                "  PRIMARY KEY (follower, followee))");
        // NEW — precomputed per-user feed (the "push" fan-out target table)
        stmt.execute("CREATE TABLE IF NOT EXISTS user_feed (" +
                "  owner TEXT NOT NULL, entry_id INTEGER NOT NULL," +
                "  PRIMARY KEY (owner, entry_id))");
        stmt.close();
    }

    private void seedFilmCatalog() throws SQLException {
        Statement check = connection.createStatement();
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
        try {
            PreparedStatement ps = connection.prepareStatement(
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
            // Another startup race already seeded it — safe to ignore (unique PK conflict)
        }
    }

    // ─── Writes (and the validation reads a write needs) ───────────────
    // A single `synchronized` is still correct and sufficient here: this
    // class only ever runs inside ONE process (DatabaseServer), so there is
    // no cross-process race to worry about — only cross-THREAD races from
    // DatabaseServer's own thread pool, which `synchronized` handles exactly
    // as it did in the v1 Database class.

    public synchronized boolean registerUser(String username, String passwordHash) {
        try {
            if (userExists(username)) return false;
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (username, password_hash) VALUES (?, ?)");
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.executeUpdate();
            ps.close();
            return true;
        } catch (SQLException e) {
            System.err.println("[Primary] registerUser error: " + e.getMessage());
            return false;
        }
    }

    public synchronized User authenticateUser(String username, String passwordHash) {
        try {
            PreparedStatement ps = connection.prepareStatement(
                "SELECT username, password_hash FROM users WHERE username = ? AND password_hash = ?");
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ResultSet rs = ps.executeQuery();
            User user = rs.next() ? new User(rs.getString(1), rs.getString(2)) : null;
            rs.close();
            ps.close();
            return user;
        } catch (SQLException e) {
            return null;
        }
    }

    public synchronized boolean userExists(String username) {
        try {
            PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE username = ?");
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            rs.next();
            boolean exists = rs.getInt(1) > 0;
            rs.close();
            ps.close();
            return exists;
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized Film getFilmById(int filmId) {
        try {
            PreparedStatement ps = connection.prepareStatement("SELECT * FROM films WHERE id = ?");
            ps.setInt(1, filmId);
            ResultSet rs = ps.executeQuery();
            Film film = rs.next() ? new Film(rs.getInt("id"), rs.getString("title"),
                    rs.getInt("year"), rs.getString("director"), rs.getString("genre")) : null;
            rs.close();
            ps.close();
            return film;
        } catch (SQLException e) {
            return null;
        }
    }

    /** Carries back exactly what happened in a write, so ReplicationManager can mirror
     *  the SAME effects onto the replica instead of re-deriving them later (which could race
     *  if, e.g., a follow/unfollow happens in between). */
    public static class LogFilmResult {
        public final DiaryEntry entry;
        public final List<String> fannedOutTo;
        LogFilmResult(DiaryEntry entry, List<String> fannedOutTo) {
            this.entry = entry;
            this.fannedOutTo = fannedOutTo;
        }
    }

    public static class FollowResult {
        public final boolean success;
        public final List<Integer> backfilledEntryIds;
        FollowResult(boolean success, List<Integer> backfilledEntryIds) {
            this.success = success;
            this.backfilledEntryIds = backfilledEntryIds;
        }
    }

    /**
     * Log a film AND fan it out to every follower's precomputed feed —
     * all inside one write, so the replica (once it catches up) always has
     * a consistent {diary_entries row, user_feed rows} pair, never one
     * without the other.
     *
     * Returns a LogFilmResult (the new entry + exactly who it fanned out to)
     * so ReplicationManager can mirror the identical effect, or null on failure.
     */
    public synchronized LogFilmResult logFilm(String username, int filmId, double rating,
                                               String review, String date) {
        try {
            Film film = getFilmById(filmId);
            if (film == null) return null;

            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO diary_entries (username, film_id, rating, review, date) VALUES (?, ?, ?, ?, ?)");
            ps.setString(1, username);
            ps.setInt(2, filmId);
            ps.setDouble(3, rating);
            ps.setString(4, review);
            ps.setString(5, date);
            ps.executeUpdate();
            ps.close();

            // SQLite JDBC's getGeneratedKeys() support is unreliable across driver versions,
            // so we use the portable, SQLite-specific way to get the row we just inserted:
            // last_insert_rowid() is scoped to THIS connection's most recent INSERT, which is
            // safe here because the whole method is `synchronized` — no other thread can sneak
            // an INSERT in on this same connection between our insert and this read.
            Statement idStmt = connection.createStatement();
            ResultSet idRs = idStmt.executeQuery("SELECT last_insert_rowid()");
            idRs.next();
            int newId = idRs.getInt(1);
            idRs.close();
            idStmt.close();

            // ─── PUSH FAN-OUT ───────────────────────────────────────────
            PreparedStatement followers = connection.prepareStatement(
                "SELECT follower FROM follows WHERE followee = ?");
            followers.setString(1, username);
            ResultSet frs = followers.executeQuery();
            PreparedStatement feedInsert = connection.prepareStatement(
                "INSERT INTO user_feed (owner, entry_id) VALUES (?, ?)");
            List<String> fannedOutTo = new ArrayList<>();
            while (frs.next()) {
                String follower = frs.getString(1);
                feedInsert.setString(1, follower);
                feedInsert.setInt(2, newId);
                feedInsert.addBatch();
                fannedOutTo.add(follower);
            }
            frs.close();
            followers.close();
            if (!fannedOutTo.isEmpty()) feedInsert.executeBatch();
            feedInsert.close();

            System.out.println("[Primary] " + username + " logged '" + film.getTitle() +
                    "' (id=" + newId + ") -> fanned out to " + fannedOutTo.size() + " follower(s)");

            DiaryEntry entry = new DiaryEntry(newId, username, filmId, film.getTitle(), rating, review, date);
            return new LogFilmResult(entry, fannedOutTo);

        } catch (SQLException e) {
            System.err.println("[Primary] logFilm error: " + e.getMessage());
            return null;
        }
    }

    public synchronized FollowResult followUser(String follower, String followee) {
        try {
            if (follower.equals(followee) || !userExists(followee))
                return new FollowResult(false, new ArrayList<>());
            PreparedStatement check = connection.prepareStatement(
                "SELECT COUNT(*) FROM follows WHERE follower = ? AND followee = ?");
            check.setString(1, follower);
            check.setString(2, followee);
            ResultSet rs = check.executeQuery();
            rs.next();
            boolean already = rs.getInt(1) > 0;
            rs.close();
            check.close();
            if (already) return new FollowResult(false, new ArrayList<>());

            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO follows (follower, followee) VALUES (?, ?)");
            ps.setString(1, follower);
            ps.setString(2, followee);
            ps.executeUpdate();
            ps.close();

            // Backfill: the new follower should see the followee's recent
            // history in their feed too, not just future posts.
            PreparedStatement backfill = connection.prepareStatement(
                "SELECT id FROM diary_entries WHERE username = ? ORDER BY date DESC, id DESC LIMIT 20");
            backfill.setString(1, followee);
            ResultSet brs = backfill.executeQuery();
            List<Integer> backfilledIds = new ArrayList<>();
            PreparedStatement feedInsert = connection.prepareStatement(
                "INSERT OR IGNORE INTO user_feed (owner, entry_id) VALUES (?, ?)");
            while (brs.next()) {
                int entryId = brs.getInt(1);
                feedInsert.setString(1, follower);
                feedInsert.setInt(2, entryId);
                feedInsert.addBatch();
                backfilledIds.add(entryId);
            }
            brs.close();
            backfill.close();
            if (!backfilledIds.isEmpty()) feedInsert.executeBatch();
            feedInsert.close();

            System.out.println("[Primary] " + follower + " now follows " + followee +
                    " -> backfilled " + backfilledIds.size() + " feed entr(ies)");
            return new FollowResult(true, backfilledIds);
        } catch (SQLException e) {
            System.err.println("[Primary] followUser error: " + e.getMessage());
            return new FollowResult(false, new ArrayList<>());
        }
    }

    public synchronized boolean unfollowUser(String follower, String followee) {
        try {
            PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM follows WHERE follower = ? AND followee = ?");
            ps.setString(1, follower);
            ps.setString(2, followee);
            int rows = ps.executeUpdate();
            ps.close();

            // Also strip followee's entries out of follower's precomputed feed
            PreparedStatement clean = connection.prepareStatement(
                "DELETE FROM user_feed WHERE owner = ? AND entry_id IN " +
                "(SELECT id FROM diary_entries WHERE username = ?)");
            clean.setString(1, follower);
            clean.setString(2, followee);
            clean.executeUpdate();
            clean.close();

            return rows > 0;
        } catch (SQLException e) {
            System.err.println("[Primary] unfollowUser error: " + e.getMessage());
            return false;
        }
    }

    /** Snapshot of ALL rows currently in a table — used once by ReplicationManager at startup
     *  to bring a freshly-created replica up to the primary's current state. */
    public synchronized List<String[]> snapshotTable(String table, String[] columns) {
        List<String[]> rows = new ArrayList<>();
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT " + String.join(",", columns) + " FROM " + table);
            while (rs.next()) {
                String[] row = new String[columns.length];
                for (int i = 0; i < columns.length; i++) row[i] = rs.getString(i + 1);
                rows.add(row);
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            System.err.println("[Primary] snapshotTable error: " + e.getMessage());
        }
        return rows;
    }

    public synchronized void close() {
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException ignored) {}
    }
}
