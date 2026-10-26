package com.bookshelf.myBooks;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ExternalReviewController {
    private final ExternalReviewService reviews;
    public ExternalReviewController(ExternalReviewService reviews) { this.reviews = reviews; }

    @GetMapping("/api/reviews/external")
    public ExternalReviewService.ExternalReviews external(@RequestParam String workId) {
        if (!workId.matches("/works/OL[0-9]{1,18}W")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a book with an Open Library work identifier.");
        }
        return reviews.forWork(workId);
    }
}
