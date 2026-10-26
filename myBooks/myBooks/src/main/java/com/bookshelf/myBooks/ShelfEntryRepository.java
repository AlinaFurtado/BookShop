package com.bookshelf.myBooks;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShelfEntryRepository extends JpaRepository<ShelfEntry, Long> {
    List<ShelfEntry> findByOwnerIdOrderByIdDesc(Long ownerId);
    Optional<ShelfEntry> findByIdAndOwnerId(Long id, Long ownerId);
    Optional<ShelfEntry> findByOwnerIdAndWorkId(Long ownerId, String workId);

    List<ShelfEntry> findByOwnerIdIsNullOrderByIdAsc();
}
