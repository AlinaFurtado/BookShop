package com.bookshelf.myBooks;

import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/shelf")
public class ShelfController {
    private final ShelfEntryRepository repository;
    public ShelfController(ShelfEntryRepository repository) { this.repository = repository; }

    @GetMapping
    public List<ShelfEntry> list(Authentication reader) {
        return repository.findByOwnerIdOrderByIdDesc(ownerId(reader));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized ShelfEntry add(@RequestBody ShelfAddRequest request, Authentication reader) {
        BookPreview book = request.book();
        validateBook(book);
        String status = request.status() == null ? "WANT_TO_READ" : request.status();
        boolean clearDate = Boolean.TRUE.equals(request.clearFinishDate());
        validateStatusAndDate(status, request.finishDate(), clearDate);
        Long ownerId = ownerId(reader);
        // Repeated clicks return the same saved entry instead of creating duplicates.
        return repository.findByOwnerIdAndWorkId(ownerId, book.id())
                .orElseGet(() -> {
                    ShelfEntry entry = new ShelfEntry(ownerId, book);
                    applyStatus(entry, status, request.finishDate(), clearDate);
                    return repository.save(entry);
                });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id, Authentication reader) {
        repository.delete(ownedEntry(id, reader));
    }

    @PatchMapping("/{id}/status")
    public ShelfEntry updateStatus(@PathVariable Long id, @RequestBody StatusUpdate update,
            Authentication reader) {
        boolean clearDate = Boolean.TRUE.equals(update.clearFinishDate());
        validateStatusAndDate(update.status(), update.finishDate(), clearDate);
        ShelfEntry entry = ownedEntry(id, reader);
        applyStatus(entry, update.status(), update.finishDate(), clearDate);
        return repository.save(entry);
    }

    @PatchMapping("/{id}/passport")
    @Transactional
    public ShelfEntry updatePassport(@PathVariable Long id, @RequestBody JsonNode body,
            Authentication reader) {
        ShelfEntry entry = ownedEntry(id, reader);
        if (!"READ".equals(entry.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mark this book as Read before adding passport reflections.");
        }
        entry.updatePassport(PassportUpdate.from(body));
        return repository.save(entry);
    }

    private void validateStatusAndDate(String status, LocalDate date, boolean clearDate) {
        if (status == null || !Set.of("WANT_TO_READ", "READING", "READ").contains(status)) {
            badRequest("Status must be WANT_TO_READ, READING, or READ.");
        }
        if (date != null && date.isAfter(LocalDate.now())) badRequest("A finish date cannot be in the future.");
        if (date != null && clearDate) badRequest("Choose a finish date or clear it, rather than both.");
    }

    private void applyStatus(ShelfEntry entry, String status, LocalDate date, boolean clearDate) {
        if (!"READ".equals(status) || clearDate) entry.setFinishDate(null);
        else if (date != null) entry.setFinishDate(date);
        // An unknown historical date stays unknown. No date is invented on save.
        entry.setStatus(status);
    }

    private Long ownerId(Authentication reader) { return Long.valueOf(reader.getName()); }

    private ShelfEntry ownedEntry(Long id, Authentication reader) {
        return repository.findByIdAndOwnerId(id, ownerId(reader)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found on your shelf."));
    }

    private void validateBook(BookPreview book) {
        if (book.id() == null || !book.id().matches("(?:/works/OL\\d+W|curated:[a-z0-9-]{1,80})")) badRequest("Invalid book identifier.");
        text(book.title(), "title", 500, true);
        text(book.author(), "author", 500, true);
        text(book.genre(), "genre", 100, false);
        text(book.description(), "description", 2000, false);
        if (book.coverId() != null && book.coverId() <= 0) badRequest("Invalid cover identifier.");
        if (book.rating() != null && (!Double.isFinite(book.rating()) || book.rating() < 0 || book.rating() > 5)) badRequest("Rating must be between 0 and 5.");
        if (book.ratingCount() != null && book.ratingCount() < 0) badRequest("Invalid rating count.");
        if (book.year() != null && (book.year() < -4000 || book.year() > 2200)) badRequest("Invalid publication year.");
    }

    private void text(String value, String field, int max, boolean required) {
        if ((required && (value == null || value.isBlank())) || (value != null && value.length() > max)) {
            badRequest("Invalid " + field + ". Maximum length is " + max + ".");
        }
    }

    private void badRequest(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
