package com.bookshelf.myBooks;

import java.time.LocalDate;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Adopting migrations must preserve records previously created by Hibernate. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:migration-existing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "bookbuddy.catalog.online=false"
})
class DatabaseMigrationTests {
    @Autowired DataSource dataSource;
    @Autowired AppUserRepository users;
    @Autowired ShelfEntryRepository shelf;
    @Autowired BookReviewRepository reviews;

    @Test
    void adoptingVersionOnePreservesUsersShelvesPassportsAndReviews() {
        var user = users.save(new AppUser("Existing Reader", "existing@example.test", "existing-hash"));
        var book = new BookPreview("/works/OL27482W", "The Hobbit", "J.R.R. Tolkien", "Fantasy",
                null, null, null, 1937, "", "curated", null, null);
        var entry = new ShelfEntry(user.getId(), book);
        entry.setStatus("READ");
        entry.setFinishDate(LocalDate.of(2026, 10, 15));
        entry.updatePassport(new PassportUpdate("Middle-earth", "Cosy", "Bilbo", "A remembered line", "A happy reading memory"));
        long entryId = shelf.save(entry).getId();
        long reviewId = reviews.save(new BookReview(user.getId(), book.id(), 5, "A remembered review", false)).getId();

        var flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/postgresql")
                .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals("existing-hash", users.findById(user.getId()).orElseThrow().getPasswordHash());
        var preserved = shelf.findById(entryId).orElseThrow();
        assertEquals("READ", preserved.getStatus());
        assertEquals(LocalDate.of(2026, 10, 15), preserved.getFinishDate());
        assertEquals("Middle-earth", preserved.getPassportDestination());
        assertEquals("A happy reading memory", preserved.getPassportMemory());
        assertEquals("A remembered review", reviews.findById(reviewId).orElseThrow().getText());
        assertEquals(0, flyway.migrate().migrationsExecuted, "A second launch must not replay the migration");
        var jdbc = new JdbcTemplate(dataSource);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '0'", Integer.class));
    }
}
