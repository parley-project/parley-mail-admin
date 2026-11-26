package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.Credential;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.permission.BasicParleyPermission;

/**
 * Who may log in to the suite's FTP servers: with the server's {@code loginPermission} setting, only
 * users the application's registry gives that permission (for example {@code admin}); without it,
 * every user the registry knows. The application decides this, so the FTP framework needs no rule of its own.
 */
class FtpLogin {

	private FtpLogin() {
	}

	/**
	 * The answer to a password check, after the permission is asked.
	 *
	 * @param registry the registry that checked the password
	 * @param permission the {@code loginPermission} setting, or null for none
	 * @param credential what the client offered
	 * @param result what the registry answered
	 * @return the result when it isn't a login or nothing is required or the user has the permission;
	 *         UNAVAILABLE when the registry can't say; otherwise a refusal
	 */
	static AuthenticationResult admit(UserRegistry registry, String permission, Credential credential, AuthenticationResult result) {
		if (permission == null || permission.trim().isEmpty() || result.getStatus() != AuthenticationResult.Status.AUTHENTICATED) {
			return result;
		}
		ParleyPermission needed = new BasicParleyPermission(permission.trim(), null);
		Requester requester = credential.getRequester();
		if (requester == null) {
			requester = Requester.withoutAddress("ftp");
		}
		AuthorizationResult admitted = registry.checkPermission(requester.loggedIn(result), needed, null);
		switch (admitted.getStatus()) {
			case ALLOWED:
				return result;
			case UNAVAILABLE:
				// never "denied": the registry couldn't answer
				return AuthenticationResult.unavailable(admitted.getReason());
			default:
				return AuthenticationResult.refused(result.getPrincipal().getName() + " may not log in to FTP: " + admitted.getReason());
		}
	}
}
