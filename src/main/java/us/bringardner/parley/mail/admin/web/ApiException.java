package us.bringardner.parley.mail.admin.web;

/** An API error with its HTTP status; the message is shown to the user. */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;

	public ApiException(int status, String message) {
		super(message);
		this.status = status;
	}

	public int getStatus() {
		return status;
	}

	public static ApiException notFound(String what) {
		return new ApiException(404, what + " not found");
	}

	public static ApiException badRequest(String message) {
		return new ApiException(400, message);
	}
}
