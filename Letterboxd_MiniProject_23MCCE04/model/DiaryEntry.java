package model;

/**
 * DiaryEntry — represents a single "I watched this film" log entry.
 *
 * This is the CORE data entity in Letterboxd. When a user logs a film,
 * it creates a diary entry with their rating, review, and the date.
 *
 * DISTRIBUTED SYSTEMS CONCEPT:
 *   This prototype writes diary entries to a primary SQLite database and
 *   asynchronously mirrors them to a separate SQLite replica. A feed read
 *   can therefore be briefly stale. This is a prototype design choice and
 *   does not describe Letterboxd's private production storage.
 */
public class DiaryEntry {

    private int id;
    private String username;       // who logged this
    private int filmId;            // which film
    private String filmTitle;      // denormalized for display convenience
    private double rating;         // 1.0 to 5.0 in this simplified application
    private String review;         // optional text review
    private String date;           // ISO date string, e.g. "2025-09-20"

    public DiaryEntry(int id, String username, int filmId, String filmTitle,
                      double rating, String review, String date) {
        this.id = id;
        this.username = username;
        this.filmId = filmId;
        this.filmTitle = filmTitle;
        this.rating = rating;
        this.review = review;
        this.date = date;
    }

    // --- Getters ---

    public int getId() { return id; }
    public String getUsername() { return username; }
    public int getFilmId() { return filmId; }
    public String getFilmTitle() { return filmTitle; }
    public double getRating() { return rating; }
    public String getReview() { return review; }
    public String getDate() { return date; }

    @Override
    public String toString() {
        String stars = ratingToStars(rating);
        String reviewSnippet = (review != null && !review.isEmpty())
                ? " — \"" + review + "\""
                : "";
        return String.format("  %s | %s %s%s  [by %s on %s]",
                filmTitle, stars, String.format("(%.1f)", rating), reviewSnippet, username, date);
    }

    /**
     * Converts a numeric rating to a visual star representation.
     * 4.0 → "★★★★☆", 3.5 → "★★★½☆"
     */
    private static String ratingToStars(double rating) {
        StringBuilder sb = new StringBuilder();
        int fullStars = (int) rating;
        boolean halfStar = (rating - fullStars) >= 0.5;

        for (int i = 0; i < fullStars; i++) sb.append("★");
        if (halfStar) sb.append("½");
        int remaining = 5 - fullStars - (halfStar ? 1 : 0);
        for (int i = 0; i < remaining; i++) sb.append("☆");

        return sb.toString();
    }
}
