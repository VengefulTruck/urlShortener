package com.schwab.urlShortener.api;

import com.schwab.urlShortener.service.InvalidUrlException;
import com.schwab.urlShortener.service.ShortCodeGenerationException;
import com.schwab.urlShortener.service.ShortLinkNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Translates domain exceptions into HTTP responses.
 *
 * Uses RFC 9457 ProblemDetail so every error has the same machine-readable
 * shape. No stack traces, class names, or framework internals reach the client:
 * those are reconnaissance for an attacker and meaningless to a legitimate one.
 * Full detail goes to the logs, where it belongs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ShortLinkNotFoundException.class)
    public ProblemDetail handleNotFound(ShortLinkNotFoundException e) {
        // Client error, not server error. Deliberately NOT logged at ERROR:
        // 404s are normal traffic and would drown real incidents.
        log.debug("Link not found: {}", e.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Link not found",
                "No active link exists for that code.", "link-not-found");
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ProblemDetail handleInvalidUrl(InvalidUrlException e) {
        log.debug("Rejected URL: {}", e.getMessage());
        // Safe to echo: the message describes the caller's own input.
        return problem(HttpStatus.BAD_REQUEST, "Invalid URL", e.getMessage(), "invalid-url");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", detail, "validation-failed");
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    public ProblemDetail handleGenerationFailure(ShortCodeGenerationException e) {
        // Genuinely our fault: generator broken or keyspace exhausted. Page someone.
        log.error("Short code generation failed", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "Could not create a link. Please retry.", "generation-failed");
    }

    /**
     * Catch-all. Anything reaching here is unanticipated, so we log it in full
     * and tell the client nothing beyond "we failed".
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred.", "internal-error");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://api.schwab.com/errors/" + type));
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}