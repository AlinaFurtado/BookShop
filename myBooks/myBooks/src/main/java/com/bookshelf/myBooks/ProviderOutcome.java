package com.bookshelf.myBooks;

/** Safe per-request diagnostics without URLs, keys, prompts or provider error bodies. */
public record ProviderOutcome(String provider, String state, String message, int received, int accepted) {
    public ProviderOutcome { received = Math.max(0, received); accepted = Math.max(0, accepted); }
}
