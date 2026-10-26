package com.bookshelf.myBooks;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private static final int PAGE_SIZE = 5;
    private final BookReviewRepository reviews;
    private final AppUserRepository users;

    public ReviewController(BookReviewRepository reviews, AppUserRepository users) {
        this.reviews = reviews;
        this.users = users;
    }

    public record ReviewRequest(String workId, Double rating, String text, Boolean spoiler) {}
    public record ReviewView(Long id, String workId, Long userId, String userName,
            int rating, String text, boolean spoiler, Instant createdAt, Instant updatedAt) {}
    public record ReviewPage(Double average, long count, List<ReviewView> reviews, int page,
            int pageSize, boolean hasMore, ReviewView currentUserReview) {}

    @GetMapping
    public ResponseEntity<ReviewPage> list(@RequestParam String workId,
            @RequestParam(defaultValue = "1") int page, Authentication authentication) {
        validateWorkId(workId);
        if (page < 1 || page > 10000) badRequest("Review page must be between 1 and 10000.");
        var results = reviews.findByWorkIdOrderByCreatedAtDescIdDesc(workId, PageRequest.of(page - 1, PAGE_SIZE));
        Long reader = readerId(authentication);
        ReviewView own = reader == null ? null : reviews.findByOwnerIdAndWorkId(reader, workId).map(this::view).orElse(null);
        ReviewPage response = new ReviewPage(reviews.averageForWork(workId), results.getTotalElements(),
                results.getContent().stream().map(this::view).toList(), page, PAGE_SIZE, results.hasNext(), own);
        // currentUserReview depends on the session; never share this response via caches.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized ReviewView create(@RequestBody ReviewRequest request, Authentication authentication) {
        validateWorkId(request.workId());
        validateReview(request);
        Long reader = requireReader(authentication);
        if (reviews.findByOwnerIdAndWorkId(reader, request.workId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already reviewed this book. Edit your existing review.");
        }
        return view(reviews.save(new BookReview(reader, request.workId(), request.rating().intValue(),
                request.text().strip(), Boolean.TRUE.equals(request.spoiler()))));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ReviewView edit(@PathVariable Long id, @RequestBody ReviewRequest request, Authentication authentication) {
        BookReview review = ownedReview(id, authentication);
        validateReview(request);
        // A review stays attached to its original work, including when editing.
        if (request.workId() != null && !request.workId().equals(review.getWorkId())) {
            badRequest("A review cannot be moved to another book.");
        }
        review.edit(request.rating().intValue(), request.text().strip(), Boolean.TRUE.equals(request.spoiler()));
        return view(reviews.save(review));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        reviews.delete(ownedReview(id, authentication));
    }

    private ReviewView view(BookReview review) {
        String name = users.findById(review.getOwnerId()).map(AppUser::getName).orElse("Reader");
        return new ReviewView(review.getId(), review.getWorkId(), review.getOwnerId(), name,
                review.getRating(), review.getText(), review.isSpoiler(), review.getCreatedAt(), review.getUpdatedAt());
    }

    private BookReview ownedReview(Long id, Authentication authentication) {
        return reviews.findByIdAndOwnerId(id, requireReader(authentication)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found."));
    }

    private Long readerId(Authentication authentication) {
        return authentication == null || authentication instanceof AnonymousAuthenticationToken
                ? null : Long.valueOf(authentication.getName());
    }

    private Long requireReader(Authentication authentication) {
        Long id = readerId(authentication);
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in to write a review.");
        return id;
    }

    private void validateWorkId(String id) {
        if (id == null || !id.matches("(?:/works/OL\\d+W|curated:[a-z0-9-]{1,80})")) badRequest("Invalid book identifier.");
    }

    private void validateReview(ReviewRequest request) {
        Double rating = request.rating();
        if (rating == null || !Double.isFinite(rating) || rating < 1 || rating > 5 || rating != Math.rint(rating)) {
            badRequest("Choose a whole-star rating between 1 and 5.");
        }
        if (request.text() == null || request.text().isBlank() || request.text().length() > 2000) {
            badRequest("Write a review from 1 to 2000 characters.");
        }
    }

    private void badRequest(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
