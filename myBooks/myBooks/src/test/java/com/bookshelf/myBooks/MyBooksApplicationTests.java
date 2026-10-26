package com.bookshelf.myBooks;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "bookbuddy.catalog.online=false"
})
class MyBooksApplicationTests {

	@Test
	void contextLoads() {
	}

}
