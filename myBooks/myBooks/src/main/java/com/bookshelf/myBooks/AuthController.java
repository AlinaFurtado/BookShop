package com.bookshelf.myBooks;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final AppUserRepository users;
    private final SecurityContextRepository contexts;

    public AuthController(AuthService auth, AppUserRepository users, SecurityContextRepository contexts) {
        this.auth = auth;
        this.users = users;
        this.contexts = contexts;
    }

    public record RegisterRequest(String name, String email, String password) {}
    public record LoginRequest(String email, String password) {}
    public record UserView(Long id, String name, String email) {
        static UserView of(AppUser user) { return new UserView(user.getId(), user.getName(), user.getEmail()); }
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@RequestBody RegisterRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        AppUser user = auth.register(body.name(), body.email(), body.password());
        startSession(user, request, response);
        return UserView.of(user);
    }

    @PostMapping("/login")
    public UserView login(@RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        AppUser user = auth.login(body.email(), body.password());
        startSession(user, request, response);
        return UserView.of(user);
    }

    @GetMapping("/me")
    public UserView me(Authentication authentication) {
        return users.findById(Long.valueOf(authentication.getName()))
                .map(UserView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in again."));
    }

    private void startSession(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                user.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_READER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        // Authentication changes invalidate the old CSRF token. Fetch /csrf again.
        new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
    }
}
