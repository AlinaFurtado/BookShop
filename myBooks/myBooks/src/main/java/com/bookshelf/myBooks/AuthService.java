package com.bookshelf.myBooks;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final AppUserRepository users;
    private final ShelfEntryRepository shelf;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;

    public AuthService(AppUserRepository users, ShelfEntryRepository shelf,
            PasswordEncoder passwords, TransactionTemplate transactions) {
        this.users = users;
        this.shelf = shelf;
        this.passwords = passwords;
        this.transactions = transactions;
    }

    // Commit inside the lock so only the first registered account inherits the
    // old, pre-login bookshelf. This local app runs in one JVM.
    public synchronized AppUser register(String name, String email, String password) {
        String cleanName = name == null ? "" : name.strip();
        String cleanEmail = normalizeEmail(email);
        if (cleanName.length() < 1 || cleanName.length() > 60) badRequest("Use a name from 1 to 60 characters.");
        validatePassword(password);
        return transactions.execute(transaction -> {
            if (users.existsByEmail(cleanEmail)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with that email already exists.");
            }
            boolean firstAccount = users.count() == 0;
            AppUser saved = users.saveAndFlush(new AppUser(cleanName, cleanEmail, passwords.encode(password)));
            if (firstAccount) claimLegacyEntries(saved.getId());
            return saved;
        });
    }

    public AppUser login(String email, String password) {
        String cleanEmail = normalizeEmail(email);
        AppUser user = users.findByEmail(cleanEmail).orElse(null);
        // Run BCrypt even for unknown accounts so failure does not return early.
        String hash = user == null ? "$2a$10$7EqJtq98hPqEX7fNZaFWoO5S7sEjG7EE3NG.7u6UFeo7s5hJlKnae" : user.getPasswordHash();
        boolean matches = password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, hash);
        if (user == null || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }
        return user;
    }

    private String normalizeEmail(String email) {
        String value = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) badRequest("Enter a valid email address.");
        return value;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            badRequest("Use a password of 8 to 64 characters (at most 72 UTF-8 bytes).");
        }
    }

    private void badRequest(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private void claimLegacyEntries(Long ownerId) {
        Set<String> claimedWorks = new HashSet<>();
        // The earlier learning app allowed duplicate saves. Claim one row per
        // work, preferring the most advanced status, then the earliest row.
        // Other legacy duplicates remain in H2 with NULL owner as backup rows.
        var entries = shelf.findByOwnerIdIsNullOrderByIdAsc().stream()
                .sorted(Comparator.comparingInt((ShelfEntry entry) -> statusRank(entry.getStatus()))
                        .reversed().thenComparing(ShelfEntry::getId))
                .filter(entry -> claimedWorks.add(entry.getWorkId()))
                .toList();
        entries.forEach(entry -> entry.claimFor(ownerId));
        shelf.saveAll(entries);
    }

    private int statusRank(String status) {
        if ("READ".equals(status)) return 2;
        if ("READING".equals(status)) return 1;
        return 0;
    }
}
