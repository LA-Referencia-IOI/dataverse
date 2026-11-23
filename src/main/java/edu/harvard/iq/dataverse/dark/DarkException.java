package edu.harvard.iq.dataverse.dark;

/**
 * Error talking to the dARK Minter. Carries the HTTP status when there was a
 * response ({@code 0} for transport failures such as timeouts or refused connections).
 */
public class DarkException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;

    public DarkException(String message) {
        this(message, 0, null);
    }

    public DarkException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public DarkException(String message, int httpStatus) {
        this(message, httpStatus, null);
    }

    public DarkException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isNotFound() {
        return httpStatus == 404;
    }

    /**
     * @return true if the Minter refused the signature of the request (401): the key is not
     *         registered, the clock is off or the request was altered. It affects every
     *         request alike, never one row in particular.
     */
    public boolean isAuthenticationFailure() {
        return httpStatus == 401;
    }

    /**
     * @return true if the Minter rejected this particular request (4xx other than 401): it
     *         will answer the same to the same request, unlike a transport failure or a
     *         server error.
     */
    public boolean isRejection() {
        return httpStatus >= 400 && httpStatus < 500 && !isAuthenticationFailure();
    }

    /** Short form stored in {@code dark.last_error}, e.g. {@code HTTP 422: title is required}. */
    public String toLastError() {
        return httpStatus > 0 ? "HTTP " + httpStatus + ": " + getMessage() : getMessage();
    }
}
