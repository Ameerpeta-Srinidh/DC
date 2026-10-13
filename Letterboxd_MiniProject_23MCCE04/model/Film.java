package model;

/**
 * Film — represents a movie in the film catalog.
 *
 * The exact source of film metadata and caching infrastructure used by
 * Letterboxd is not described in the public references for this project.
 * This prototype uses a pre-seeded SQLite table instead.
 *
 * The catalog is fixed at 30 films (seeded on first server startup).
 * A larger service could use a dedicated search index for a larger catalog.
 */
public class Film {

    private int id;
    private String title;
    private int year;
    private String director;
    private String genre;

    public Film(int id, String title, int year, String director, String genre) {
        this.id = id;
        this.title = title;
        this.year = year;
        this.director = director;
        this.genre = genre;
    }

    // --- Getters ---

    public int getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public int getYear() {
        return year;
    }

    public String getDirector() {
        return director;
    }

    public String getGenre() {
        return genre;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s (%d) — Dir: %s | Genre: %s",
                id, title, year, director, genre);
    }
}
