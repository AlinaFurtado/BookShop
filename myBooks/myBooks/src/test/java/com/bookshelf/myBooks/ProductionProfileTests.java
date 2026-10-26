package com.bookshelf.myBooks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the hosting profile with a disposable DB, never the real Neon DB. */
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:production-profile;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "server.address=127.0.0.1",
        "bookbuddy.catalog.online=false"
})
class ProductionProfileTests {
    @LocalServerPort int port;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final HttpClient client = HttpClient.newHttpClient();

    @Test
    void hostingProfileMigratesAnEmptyDatabaseAndValidatesTheEntities() throws Exception {
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE", Integer.class));
        for (String table : new String[] {"app_user", "shelf_entry", "book_review"}) {
            assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        assertEquals(200, send("GET", "/api/health", null, null, null, null).statusCode());
    }

    @Test
    void httpsProxySessionsKeepSecureCookiesAndRequireCsrf() throws Exception {
        var csrfResponse = send("GET", "/api/auth/csrf", null, null, null, null);
        assertEquals(200, csrfResponse.statusCode());
        String setCookie = csrfResponse.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("Secure"), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
        String cookie = setCookie.split(";", 2)[0];
        String body = json.writeValueAsString(Map.of("name", "Hosted Reader", "email", "hosted@example.test", "password", "bookworm123"));
        assertEquals(403, send("POST", "/api/auth/register", body, cookie, null, null).statusCode());
        var csrf = json.readTree(csrfResponse.body());
        var registration = send("POST", "/api/auth/register", body, cookie,
                csrf.path("headerName").asText(), csrf.path("token").asText());
        assertEquals(201, registration.statusCode(), registration.body());
        String loginCookie = registration.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertEquals(200, send("GET", "/api/auth/me", null, loginCookie, null, null).statusCode());
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie,
            String csrfHeader, String csrfToken) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("X-Forwarded-Proto", "https")
                .header("Content-Type", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        if (csrfHeader != null) request.header(csrfHeader, csrfToken);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
