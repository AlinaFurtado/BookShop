package com.bookshelf.myBooks;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** A reader's saved copy of a catalog work. Its numeric id belongs to this DB. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_shelf_owner_work", columnNames = {"ownerId", "workId"}))
public class ShelfEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long ownerId;
    @Column(length = 100)
    private String workId;
    @Column(length = 500)
    private String title;
    @Column(length = 500)
    private String author;
    @Column(length = 100)
    private String genre;
    private Integer coverId;
    private Double rating;
    private Integer ratingCount;
    @Column(name = "publish_year")
    private Integer year;
    @Column(length = 2000)
    private String description;
    @Column(length = 20)
    private String status;
    private LocalDate finishDate;
    @Column(length = 120)
    private String passportDestination;
    @Column(length = 80)
    private String passportMood;
    @Column(length = 120)
    private String passportFavoriteCharacter;
    @Column(length = 600)
    private String passportQuote;
    @Column(length = 2000)
    private String passportMemory;

    protected ShelfEntry() {}

    public ShelfEntry(Long ownerId, BookPreview book) {
        this.ownerId = ownerId;
        this.workId = book.id();
        this.title = book.title();
        this.author = book.author();
        this.genre = book.genre();
        this.coverId = book.coverId();
        this.rating = book.rating();
        this.ratingCount = book.ratingCount();
        this.year = book.year();
        this.description = book.description();
        this.status = "WANT_TO_READ";
    }

    public Long getId() { return id; }
    @JsonIgnore public Long getOwnerId() { return ownerId; }
    public String getWorkId() { return workId; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getGenre() { return genre; }
    public Integer getCoverId() { return coverId; }
    public Double getRating() { return rating; }
    public Integer getRatingCount() { return ratingCount; }
    public Integer getYear() { return year; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public LocalDate getFinishDate() { return finishDate; }
    public String getPassportDestination() { return passportDestination; }
    public String getPassportMood() { return passportMood; }
    public String getPassportFavoriteCharacter() { return passportFavoriteCharacter; }
    public String getPassportQuote() { return passportQuote; }
    public String getPassportMemory() { return passportMemory; }
    public void setStatus(String status) { this.status = status; }
    public void setFinishDate(LocalDate finishDate) { this.finishDate = finishDate; }

    public void updatePassport(PassportUpdate notes) {
        if (notes.destination() != null) passportDestination = nullableNote(notes.destination());
        if (notes.mood() != null) passportMood = nullableNote(notes.mood());
        if (notes.favoriteCharacter() != null) passportFavoriteCharacter = nullableNote(notes.favoriteCharacter());
        if (notes.quote() != null) passportQuote = nullableNote(notes.quote());
        if (notes.memory() != null) passportMemory = nullableNote(notes.memory());
    }

    private String nullableNote(String text) { return text.isBlank() ? null : text; }

    void claimFor(Long ownerId) { this.ownerId = ownerId; }
}
