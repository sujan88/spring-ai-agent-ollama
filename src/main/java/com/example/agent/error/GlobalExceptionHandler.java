package com.example.agent.error;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Errors as RFC 9457 problem JSON. Unexpected errors get an id to find in the logs, never a stack trace.
 *
 * Streaming (SSE) responses are special: once the stream has started, the status and content type are
 * already sent, so no JSON body can follow. For those, the error is logged and nothing is written.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The client went away mid-stream (tab closed, page reloaded, curl stopped). Nothing to answer. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientDisconnected(AsyncRequestNotUsableException e) {
        log.debug("Client disconnected before the response finished: {}", e.getMessage());
    }

    /** The answer took longer than spring.mvc.async.request-timeout. */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ProblemDetail timeout(AsyncRequestTimeoutException e, HttpServletResponse response) {
        log.warn("Request timed out (spring.mvc.async.request-timeout); the model may be too slow for this limit");
        return streaming(response) ? null
                : ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "The answer took too long. Try again.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail status(ResponseStatusException e, HttpServletResponse response) {
        if (streaming(response)) {
            log.warn("Error during stream: {} {}", e.getStatusCode(), e.getReason());
            return null;
        }
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason() == null ? "" : e.getReason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e, HttpServletResponse response) {
        if (streaming(response)) {
            log.warn("Error during stream: {}", e.getMessage());
            return null;
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e, HttpServletResponse response) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled error {}", errorId, e);
        if (streaming(response)) return null;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Error id: " + errorId);
        problem.setProperty("errorId", errorId);
        return problem;
    }

    /** True when an SSE stream already started: its headers are sent, so a JSON error body cannot be written. */
    private static boolean streaming(HttpServletResponse response) {
        String type = response.getContentType();
        return response.isCommitted() || (type != null && type.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }
}
