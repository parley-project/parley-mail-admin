package us.bringardner.parley.mail.admin.web;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Security for every request: response headers that keep the pages from being framed or
 * mixed with other sites' content, and for the API a logged-in administrator (except the few
 * calls that log in) and, for every call that changes something, the session's CSRF token in
 * the {@value #CSRF_HEADER} header.
 */
public class AdminFilter extends HttpFilter {

	private static final long serialVersionUID = 1L;

	static final String USER = "parley.admin.user";
	static final String CSRF = "parley.admin.csrf";
	static final String CSRF_HEADER = "X-CSRF-Token";
	private static final SecureRandom RANDOM = new SecureRandom();

	@Override
	protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
		res.setHeader("X-Content-Type-Options", "nosniff");
		res.setHeader("X-Frame-Options", "DENY");
		res.setHeader("Referrer-Policy", "same-origin");
		res.setHeader("Content-Security-Policy",
				"default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'");

		String path = req.getRequestURI().substring(req.getContextPath().length());
		if (!path.startsWith("/api/") && !path.startsWith("/brand/")) {
			// the pages, styles and scripts: the browser asks each time whether they changed, so a new
			// version is used at once (unchanged files still come from its cache: 304 Not Modified)
			res.setHeader("Cache-Control", "no-cache");
		}
		if (path.startsWith("/api/")) {
			res.setHeader("Cache-Control", "no-store");
			String call = path.substring(4);
			boolean open = call.equals("/session") || call.equals("/login") || call.equals("/setup") || call.equals("/logout");
			if (!open && user(req) == null) {
				ApiServlet.error(res, 401, "Please log in");
				return;
			}
			if (!isSafe(req.getMethod())) {
				if (open) {
					// no session token yet: a header a form or another site can't send without CORS
					if (req.getHeader(CSRF_HEADER) == null) {
						ApiServlet.error(res, 403, "Missing " + CSRF_HEADER);
						return;
					}
				} else if (!csrfMatches(req)) {
					ApiServlet.error(res, 403, "The page is out of date; reload it");
					return;
				}
			}
		}
		chain.doFilter(req, res);
	}

	private static boolean isSafe(String method) {
		return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
	}

	private static boolean csrfMatches(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		String expected = s == null ? null : (String) s.getAttribute(CSRF);
		String got = req.getHeader(CSRF_HEADER);
		return expected != null && got != null
				&& MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), got.getBytes(StandardCharsets.US_ASCII));
	}

	/** The logged-in administrator, or null. */
	static String user(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		return s == null ? null : (String) s.getAttribute(USER);
	}

	/** Log a user in: a new session id (no fixation) and a new CSRF token. */
	static String login(HttpServletRequest req, String user) {
		HttpSession s = req.getSession(true);
		req.changeSessionId();
		byte[] b = new byte[24];
		RANDOM.nextBytes(b);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
		s.setAttribute(USER, user);
		s.setAttribute(CSRF, token);
		return token;
	}

	static String csrf(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		return s == null ? null : (String) s.getAttribute(CSRF);
	}

	static void logout(HttpServletRequest req) {
		HttpSession s = req.getSession(false);
		if (s != null) {
			s.invalidate();
		}
	}
}
