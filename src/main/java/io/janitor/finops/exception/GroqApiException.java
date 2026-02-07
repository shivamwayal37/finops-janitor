package io.janitor.finops.exception;

/**
 * Thrown when the Groq AI API call fails (timeout, bad response, parse error).
 * The AIService catches this internally and returns a safe fallback AIDecision,
 * so this exception should never escape to the caller — but it is here for
 * explicit logging and future retry logic.
 */
public class GroqApiException extends RuntimeException {

    private final int httpStatus;   // 0 if we never got a response

    public GroqApiException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public GroqApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 0;
    }

    public int getHttpStatus() { return httpStatus; }
}
