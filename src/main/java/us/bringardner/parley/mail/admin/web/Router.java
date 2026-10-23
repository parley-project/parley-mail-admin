package us.bringardner.parley.mail.admin.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Maps API requests to handlers: a method and a path pattern like {@code /users/{name}}, whose
 * parts are given to the handler. What a handler returns is written as JSON (null: 204 No
 * Content), unless it is a {@link Text} or {@link Download}.
 */
final class Router {

	/** A handler of one route. */
	@FunctionalInterface
	interface Handler {
		Object handle(Request request) throws Exception;
	}

	/** Plain text to send as it is. */
	static final class Text {
		final String text;

		Text(String text) {
			this.text = text;
		}
	}

	/** A file to download. */
	static final class Download {
		final java.io.File file;
		final String name;
		final String contentType;

		Download(java.io.File file, String name, String contentType) {
			this.file = file;
			this.name = name;
			this.contentType = contentType;
		}
	}

	/** A request, with the path's parts and the JSON body. */
	static final class Request {
		final HttpServletRequest http;
		final HttpServletResponse response;
		private final List<String> params;
		private final ObjectMapper json;
		private JsonNode body;

		Request(HttpServletRequest http, HttpServletResponse response, List<String> params, ObjectMapper json) {
			this.http = http;
			this.response = response;
			this.params = params;
			this.json = json;
		}

		/** The n-th {...} part of the path, decoded. */
		String param(int n) {
			return params.get(n);
		}

		/** A query parameter, or the default if it's missing or empty. */
		String query(String name, String defaultValue) {
			String v = http.getParameter(name);
			return v == null || v.trim().isEmpty() ? defaultValue : v.trim();
		}

		long queryLong(String name, long defaultValue) {
			String v = query(name, null);
			if (v == null) {
				return defaultValue;
			}
			try {
				return Long.parseLong(v);
			} catch (NumberFormatException e) {
				throw ApiException.badRequest(name + " must be a number");
			}
		}

		JsonNode body() throws IOException {
			if (body == null) {
				try (InputStream in = http.getInputStream()) {
					byte[] b = in.readNBytes(1024 * 1024);
					body = b.length == 0 ? NullNode.getInstance() : json.readTree(b);
				} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
					throw ApiException.badRequest("The request isn't valid JSON");
				}
			}
			return body;
		}

		/** A text field of the body, or null. */
		String text(String field) throws IOException {
			JsonNode n = body().get(field);
			return n == null || n.isNull() ? null : n.asText();
		}

		/** A required text field of the body. */
		String requiredText(String field) throws IOException {
			String v = text(field);
			if (v == null || v.trim().isEmpty()) {
				throw ApiException.badRequest(field + " is required");
			}
			return v;
		}

		/** A list of strings field of the body, or null. */
		List<String> list(String field) throws IOException {
			JsonNode n = body().get(field);
			if (n == null || n.isNull()) {
				return null;
			}
			if (!n.isArray()) {
				throw ApiException.badRequest(field + " must be a list");
			}
			List<String> ret = new ArrayList<>();
			for (JsonNode e : n) {
				ret.add(e.asText());
			}
			return ret;
		}

		/** A password field as characters (null if missing). */
		char[] password(String field) throws IOException {
			String v = text(field);
			return v == null || v.isEmpty() ? null : v.toCharArray();
		}

		String user() {
			return AdminFilter.user(http);
		}
	}

	private static final class Route {
		final String method;
		final Pattern pattern;
		final Handler handler;

		Route(String method, Pattern pattern, Handler handler) {
			this.method = method;
			this.pattern = pattern;
			this.handler = handler;
		}
	}

	private final List<Route> routes = new ArrayList<>();

	void get(String path, Handler h) {
		add("GET", path, h);
	}

	void post(String path, Handler h) {
		add("POST", path, h);
	}

	void put(String path, Handler h) {
		add("PUT", path, h);
	}

	void delete(String path, Handler h) {
		add("DELETE", path, h);
	}

	private void add(String method, String path, Handler h) {
		String regex = "\\Q" + path.replaceAll("\\{[a-zA-Z]+\\}", "\\\\E([^/]+)\\\\Q") + "\\E";
		routes.add(new Route(method, Pattern.compile(regex), h));
	}

	/** The handler and path parts for a request, or null; throws 405 if only the method is wrong. */
	Object[] find(String method, String path) {
		boolean pathMatched = false;
		for (Route r : routes) {
			Matcher m = r.pattern.matcher(path);
			if (m.matches()) {
				pathMatched = true;
				if (r.method.equals(method)) {
					List<String> params = new ArrayList<>();
					for (int i = 1; i <= m.groupCount(); i++) {
						params.add(URLDecoder.decode(m.group(i).replace("+", "%2B"), StandardCharsets.UTF_8));
					}
					return new Object[] { r.handler, Collections.unmodifiableList(params) };
				}
			}
		}
		if (pathMatched) {
			throw new ApiException(405, method + " is not allowed here");
		}
		return null;
	}

	static Map<String, Object> message(String text) {
		return Collections.singletonMap("message", text);
	}
}
