package com.bookshelf.myBooks;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookReviewRepository extends JpaRepository<BookReview, Long> {
    Optional<BookReview> findByOwnerIdAndWorkId(Long ownerId, String workId);
    Optional<BookReview> findByIdAndOwnerId(Long id, Long ownerId);
    Page<BookReview> findByWorkIdOrderByCreatedAtDescIdDesc(String workId, Pageable pageable);
    long countByWorkId(String workId);

    @Query("select avg(review.rating) from BookReview review where review.workId = :workId")
    Double averageForWork(@Param("workId") String workId);
}
