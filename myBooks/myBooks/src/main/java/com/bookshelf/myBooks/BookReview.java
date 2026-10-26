package com.bookshelf.myBooks;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Reviews belong to Book Buddy readers; these are not Open Library reviews. */
@Entity
@Table(name = "book_review", uniqueConstraints = @UniqueConstraint(
        name = "uk_review_owner_work", columnNames = {"ownerId", "workId"}))
public class BookReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long ownerId;
    @Column(nullable = false, length = 100)
    private String workId;
    @Column(nullable = false)
    private int rating;
    @Column(name = "review_text", nullable = false, length = 2000)
    private String text;
    @Column(nullable = false)
    private boolean spoiler;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected BookReview() {}

    public BookReview(Long ownerId, String workId, int rating, String text, boolean spoiler) {
        this.ownerId = ownerId;
        this.workId = workId;
        this.createdAt = Instant.now();
        edit(rating, text, spoiler);
    }

    public void edit(int rating, String text, boolean spoiler) {
        this.rating = rating;
        this.text = text;
        this.spoiler = spoiler;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getWorkId() { return workId; }
    public int getRating() { return rating; }
    public String getText() { return text; }
    public boolean isSpoiler() { return spoiler; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
