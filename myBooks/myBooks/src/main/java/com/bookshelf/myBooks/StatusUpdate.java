package com.bookshelf.myBooks;

import java.time.LocalDate;

public record StatusUpdate(String status, LocalDate finishDate, Boolean clearFinishDate) {}

