package us.bringardner.parley.mail.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The whole application in an embedded Tomcat, as a browser uses it: the first administrator,
 * domains, users, the services, mail through SMTP into POP3, the queue's history, the logs and
 * the settings. The mail servers run on free ports with a scratch home.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WebAppTest {

	private static DevServer server;
	private static File home;
	private static String base;
	private static final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
	private static final ObjectMapper json = new ObjectMapper();
	private static String csrf = "none";
	private static int smtpPort;
	private static int submissionPort;
	private static int pop3Port;
	private static int imapPort;
	private static int ftpPort;

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	@BeforeAll
	public static void start() throws Exception {
		home = Files.createTempDirectory(new File(System.getProperty("java.io.tmpdir")).toPath(), "mail-admin-home").toFile();
		smtpPort = freePort();
		submissionPort = freePort();
		pop3Port = freePort();
		imapPort = freePort();
		ftpPort = freePort();
		String root = new File(home, "mail").getAbsolutePath().replace('\\', '/');
		// the store's and the queue's own settings, as parley-servers-setup makes them (the queue names
		// no aliases file: the application's own is used, and named when the queue's settings change)
		new File(home, "mail/.smtp-queue").mkdirs();
		Files.write(new File(home, "mail/.parley-store.properties").toPath(), "sharing=host\n".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(home, "mail/.smtp-queue/queue.properties").toPath(), "# the queue\nhostname=mx.a.test\ndomains=a.test\n".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(home, "parley-mail.properties").toPath(), ("# test settings\n"
				+ "parley.mail.root=" + root + "\n"
				+ "parley.mail.smtp.hostname=mx.a.test\n"
				+ "parley.mail.submission.hostname=mx.a.test\n"
				+ "parley.mail.smtp.relayNetworks=127.0.0.1/32\n"
				+ "parley.mail.smtp.port=" + smtpPort + "\n"
				+ "parley.mail.submission.port=" + submissionPort + "\n"
				+ "parley.mail.submissions.port=0\n"
				+ "parley.mail.imap.port=" + imapPort + "\n"
				+ "parley.mail.imaps.port=0\n"
				+ "parley.mail.pop3.port=" + pop3Port + "\n"
				+ "parley.mail.pop3s.port=0\n"
				+ "parley.mail.ftp.port=" + ftpPort + "\n"
				+ "parley.mail.ftps.port=0\n"
				+ "parley.mail.admin.autoStart=true\n").getBytes(StandardCharsets.UTF_8));
		System.setProperty(AdminHome.HOME_PROPERTY, home.getAbsolutePath());
		// the settings files in the home (not /etc/parley, which a test machine may have)
		System.setProperty(AdminHome.SETTINGS_PROPERTY, home.getAbsolutePath());
		// a JVM has one URL handler factory: parley-files installs its own when another test of this JVM
		// ran mail servers first (in a real Tomcat, Tomcat's is installed first and parley-files uses
		// java.protocol.handler.pkgs instead); this application doesn't need Tomcat's (war: URLs)
		org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
		server = new DevServer(0, "/admin", DevServer.webappDirectory(), new File(home, "tomcat"));
		server.start();
		base = "http://localhost:" + server.getPort() + "/admin/";
	}

	@AfterAll
	public static void stop() throws Exception {
		if (server != null) {
			server.stop();
		}
		System.clearProperty(AdminHome.HOME_PROPERTY);
		System.clearProperty(AdminHome.SETTINGS_PROPERTY);
	}

	private static HttpResponse<String> call(String method, String path, Object body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "api" + path)).header("X-CSRF-Token", csrf);
		if (body != null) {
			b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
		} else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode ok(String method, String path, Object body) throws Exception {
		HttpResponse<String> r = call(method, path, body);
		assertTrue(r.statusCode() / 100 == 2, method + " " + path + ": " + r.statusCode() + " " + r.body());
		return r.body().isEmpty() ? null : json.readTree(r.body());
	}

	private static void waitFor(BooleanSupplier test, String what) throws InterruptedException {
		long end = System.currentTimeMillis() + 20000;
		while (!test.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				throw new AssertionError("Timed out waiting for " + what);
			}
			Thread.sleep(100);
		}
	}

	@Test
	@Order(1)
	public void pagesAndSecurity() throws Exception {
		HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, page.statusCode());
		assertTrue(page.body().contains("js/app.js"));
		assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent());
		HttpResponse<String> logo = http.send(HttpRequest.newBuilder(URI.create(base + "brand/margot-smtp.svg")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, logo.statusCode());
		assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "brand/secret.txt")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());

		assertEquals(401, call("GET", "/status", null).statusCode(), "not logged in");
		JsonNode s = ok("GET", "/session", null);
		assertTrue(s.get("setupRequired").asBoolean(), "no administrator yet");
		assertFalse(s.get("authenticated").asBoolean());
	}

	@Test
	@Order(2)
	public void setupAndLogin() throws Exception {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "api/setup")).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"admin@a.test\",\"password\":\"adminpass1\"}")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(403, r.statusCode(), "no CSRF header");
		assertEquals(400, call("POST", "/setup", Map.of("user", "admin@new.test", "password", "short")).statusCode());
		assertFalse(ok("GET", "/session", null).get("domains").toString().contains("new.test"), "a failed setup adds no domain");
		JsonNode s = ok("POST", "/setup", Map.of("user", "admin@a.test", "password", "adminpass1"));
		assertTrue(s.get("authenticated").asBoolean());
		csrf = s.get("csrf").asText();
		assertEquals(409, call("POST", "/setup", Map.of("user", "x", "password", "adminpass2")).statusCode(), "only once");

		ok("POST", "/logout", null);
		csrf = "none";
		assertEquals(401, call("POST", "/login", Map.of("user", "admin@a.test", "password", "wrong-one")).statusCode());
		s = ok("POST", "/login", Map.of("user", "admin@a.test", "password", "adminpass1"));
		csrf = s.get("csrf").asText();
		String good = csrf;
		csrf = "forged";
		assertEquals(403, call("POST", "/services/all/stop", null).statusCode(), "wrong CSRF token");
		csrf = good;
	}

	@Test
	@Order(3)
	public void servicesStarted() throws Exception {
		JsonNode st = ok("GET", "/status", null);
		for (JsonNode svc : st.get("services")) {
			String id = svc.get("id").asText();
			boolean enabled = svc.get("enabled").asBoolean();
			assertEquals(enabled ? "RUNNING" : "OFF", svc.get("state").asText(), id + ": " + svc);
		}
		boolean ftp = false;
		for (JsonNode svc : st.get("services")) {
			if (svc.get("id").asText().equals("ftp")) {
				ftp = true;
				assertEquals("RUNNING", svc.get("state").asText(), "FTP is a service of the suite: " + svc);
				assertEquals(ftpPort, svc.get("port").asInt());
			}
		}
		assertTrue(ftp, "the services include ftp");
		assertTrue(st.get("queue").get("running").asBoolean());
		assertTrue(st.get("local").asBoolean(), "the servers run in the application");
		try (Socket client = new Socket("localhost", pop3Port)) {
			new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)).readLine(); // +OK
			JsonNode conns = ok("GET", "/services/pop3/connections", null);
			assertEquals(1, conns.size(), conns.toString());
			assertTrue(conns.get(0).get("user").isNull(), "not logged in");
		}
		assertEquals(home.getCanonicalPath(), st.get("settingsDirectory").asText());
		assertEquals(new File(home, "mail/.smtp-history").getAbsolutePath(), st.get("historyDirectory").asText(), "from the SMTP queue's bean");
		// the servers are parley-net's, with their JMX beans
		javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
		assertEquals("RUNNING", mbs.getAttribute(new javax.management.ObjectName("us.bringardner.parley:type=Server,name=pop3"), "State"));
		assertTrue(mbs.isRegistered(new javax.management.ObjectName("us.bringardner.parley:type=MailQueue,name=smtp")));
	}

	@Test
	@Order(4)
	public void domainsUsersAndMail() throws Exception {
		JsonNode domains = ok("POST", "/domains", Map.of("domain", "B.test"));
		assertEquals(2, domains.size());
		assertEquals(400, call("POST", "/domains", Map.of("domain", "not a domain")).statusCode());
		JsonNode b = ok("POST", "/domains/b.test/dkim", Map.of("selector", "sel1", "type", "rsa"));
		assertEquals("sel1", b.get("dkim").get("selector").asText());
		boolean dkimRecord = false;
		for (JsonNode r : b.get("dns")) {
			dkimRecord |= r.get("name").asText().equals("sel1._domainkey.b.test.") && r.get("value").asText().startsWith("v=DKIM1; k=rsa; p=");
		}
		assertTrue(dkimRecord, b.toString());
		// A4.1: the key file gets the permissions any new file of the process gets (the servers may be another user of its group)
		File key = new File(home, "dkim/b.test-sel1.pem");
		assertTrue(key.isFile());
		if (Files.getFileAttributeView(key.toPath(), java.nio.file.attribute.PosixFileAttributeView.class) != null) {
			File fresh = new File(home, "dkim/fresh.txt");
			Files.write(fresh.toPath(), new byte[0]);
			assertEquals(Files.getPosixFilePermissions(fresh.toPath()), Files.getPosixFilePermissions(key.toPath()));
			Files.delete(fresh.toPath());
		}

		ok("POST", "/users", Map.of("name", "tony@b.test", "password", "tonypass1", "permissions", java.util.List.of("READ", "WRITE")));
		assertEquals(400, call("POST", "/users", Map.of("name", "x@c.test", "password", "xxxxxxxx1")).statusCode(), "not a local domain");
		ok("PUT", "/aliases/sales@b.test", Map.of("members", java.util.List.of("tony")));

		// domains, DKIM and aliases are used at once: no service needs a restart for them
		for (JsonNode s : ok("GET", "/services", null)) {
			assertFalse(s.get("restartNeeded").asBoolean(), s.toString());
		}
		// a queue setting the reload doesn't apply still needs one
		ok("PUT", "/settings", Map.of("changes", Map.of("queue.workers", "3")));
		boolean restart = false;
		for (JsonNode s : ok("GET", "/services", null)) {
			if (s.get("id").asText().equals("smtp")) {
				restart = s.get("restartNeeded").asBoolean();
			}
			if (s.get("id").asText().equals("pop3")) {
				assertFalse(s.get("restartNeeded").asBoolean(), "POP3 isn't affected");
			}
		}
		assertTrue(restart);
		ok("POST", "/services/all/restart-changed", null);
		for (JsonNode s : ok("GET", "/services", null)) {
			assertFalse(s.get("restartNeeded").asBoolean(), s.toString());
		}
		// mail to the alias of the new domain, through SMTP, reaches tony@b.test's maildrop
		smtp(smtpPort, "x@far.test", "sales@b.test", "Subject: hello\r\n\r\nHi Tony\r\n");
		waitFor(() -> {
			try {
				JsonNode h = ok("GET", "/history?q=tony%40b.test&type=DELIVERED", null);
				return h.size() > 0;
			} catch (Exception e) {
				return false;
			}
		}, "the delivery in the history");
		JsonNode hist = ok("GET", "/history?type=DELIVERED,EXPANDED,QUEUED,COMPLETED", null);
		String id = null;
		for (JsonNode e : hist) {
			if (e.get("type").asText().equals("DELIVERED")) {
				id = e.get("id").asText();
				assertEquals("delivered to tony@b.test", e.get("result").asText());
			}
		}
		assertNotNull(id);
		JsonNode timeline = ok("GET", "/history/" + id, null);
		assertEquals("QUEUED", timeline.get(0).get("type").asText());
		assertEquals("COMPLETED", timeline.get(timeline.size() - 1).get("type").asText());
		assertTrue(new File(home, "mail/b.test/tony").isDirectory());

		assertEquals(1, pop3Count("tony@b.test", "tonypass1"));
		JsonNode usage = ok("GET", "/users/" + URLEncoder.encode("tony@b.test", StandardCharsets.UTF_8) + "/usage", null);
		assertTrue(usage.get("exists").asBoolean());
		assertTrue(usage.get("bytes").asLong() > 0);

		// and a domain added to the running servers is received at once (no restart)
		ok("POST", "/domains", Map.of("domain", "d.test"));
		ok("PUT", "/aliases/info@d.test", Map.of("members", java.util.List.of("tony@b.test")));
		smtp(smtpPort, "x@far.test", "info@d.test", "Subject: first\r\n\r\nTo d.test\r\n");
		waitFor(() -> {
			try {
				return pop3Count("tony@b.test", "tonypass1") == 2;
			} catch (Exception e) {
				return false;
			}
		}, "the mail to the new domain's alias delivered");

		// the primary domain's key is the shared one: a domain without its own signs with it
		JsonNode d = null;
		for (JsonNode x : ok("GET", "/domains", null)) {
			if (x.get("domain").asText().equals("d.test")) {
				d = x;
			}
		}
		assertNull(d.get("dkim"), "no key yet: the primary domain has none");
		ok("POST", "/domains/a.test/dkim", Map.of("selector", "prim", "type", "rsa"));
		String queue = new String(Files.readAllBytes(new File(home, "mail/.smtp-queue/queue.properties").toPath()), StandardCharsets.UTF_8);
		assertTrue(queue.contains("dkim.shared=a.test"), queue);
		for (JsonNode x : ok("GET", "/domains", null)) {
			String name = x.get("domain").asText();
			JsonNode k = x.get("dkim");
			if (name.equals("d.test")) {
				assertEquals("prim", k.get("selector").asText());
				assertFalse(k.get("own").asBoolean());
				assertEquals("a.test", k.get("domain").asText());
				boolean record = false;
				for (JsonNode r : x.get("dns")) {
					record |= r.get("name").asText().equals("prim._domainkey.d.test.") && r.get("value").asText().startsWith("v=DKIM1");
				}
				assertTrue(record, x.toString());
			} else if (name.equals("b.test")) {
				assertTrue(k.get("own").asBoolean(), "b.test keeps its own key");
				assertEquals("sel1", k.get("selector").asText());
			} else if (name.equals("a.test")) {
				assertTrue(k.get("own").asBoolean() && k.get("shared").asBoolean(), x.toString());
			}
		}
		for (JsonNode s : ok("GET", "/services", null)) {
			assertFalse(s.get("restartNeeded").asBoolean(), s.toString());
		}
		for (JsonNode s : ok("GET", "/services", null)) {
			assertFalse(s.get("restartNeeded").asBoolean(), s.toString());
		}
	}

	@Test
	@Order(5)
	public void queueActions() throws Exception {
		// to a smart host that refuses connections, with a retry schedule far away: it stays in the queue
		ok("PUT", "/settings", Map.of("changes", Map.of("queue.retry", "600", "queue.relayHost", "127.0.0.1:" + freePort(),
				"queue.relayTls", "none")));
		ok("POST", "/services/all/restart-changed", null);
		smtp(smtpPort, "admin@a.test", "someone@remote.invalid", "Subject: stuck\r\n\r\nbody\r\n");
		waitFor(() -> {
			try {
				return ok("GET", "/queue", null).get("entries").size() == 1;
			} catch (Exception e) {
				return false;
			}
		}, "the message in the queue");
		JsonNode e = ok("GET", "/queue", null).get("entries").get(0);
		String id = e.get("id").asText();
		HttpResponse<String> message = call("GET", "/queue/" + id + "/message", null);
		assertTrue(message.body().contains("Subject: stuck"), message.body());
		waitFor(() -> {
			try {
				JsonNode q = ok("GET", "/queue", null).get("entries");
				return q.size() == 1 && q.get(0).get("recipients").get(0).get("attempts").asInt() > 0;
			} catch (Exception ex) {
				return false;
			}
		}, "the first attempt");
		ok("POST", "/queue/" + id + "/retry", null);
		// the retry may be under way: then delete says 409 until it's done
		waitFor(() -> {
			try {
				return call("DELETE", "/queue/" + id, null).statusCode() == 200;
			} catch (Exception ex) {
				return false;
			}
		}, "the delete");
		assertEquals(0, ok("GET", "/queue", null).get("entries").size());
		waitFor(() -> {
			try {
				return ok("GET", "/history?type=DELETED", null).size() == 1;
			} catch (Exception ex) {
				return false;
			}
		}, "DELETED in the history");
		assertEquals(404, call("DELETE", "/queue/" + id, null).statusCode());
	}

	@Test
	@Order(6)
	public void logsAndLevels() throws Exception {
		JsonNode logs = ok("GET", "/logs?source=ADMIN", null);
		assertTrue(logs.get("entries").size() > 0, "the admin application's entries");
		boolean smtpSeen = false;
		for (JsonNode e : ok("GET", "/logs?source=SMTP", null).get("entries")) {
			smtpSeen = true;
			assertEquals("SMTP", e.get("source").asText());
		}
		assertTrue(smtpSeen, "the SMTP server's entries (Parley loggers)");
		ok("PUT", "/logs/levels", Map.of("source", "POP3", "level", "DEBUG"));
		assertEquals("DEBUG", ok("GET", "/logs/levels", null).get("POP3").asText());
		pop3Count("tony@b.test", "tonypass1");
		long debug = 0;
		for (JsonNode e : ok("GET", "/logs?source=POP3&level=DEBUG", null).get("entries")) {
			if (e.get("level").asText().equals("DEBUG")) {
				debug++;
			}
		}
		assertTrue(debug > 0, "POP3 logs at DEBUG now");
		HttpResponse<String> file = call("GET", "/logs/download", null);
		assertEquals(200, file.statusCode());
		assertTrue(file.body().contains("Parley mail admin starting"));

		// the log files: this application's, and only those
		JsonNode files = ok("GET", "/logs/files", null);
		assertTrue(files.size() > 0);
		String path = files.get(0).get("path").asText();
		assertTrue(path.endsWith("parley-mail.log"), path);
		HttpResponse<String> tail = call("GET", "/logs/files/read?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8), null);
		assertEquals(200, tail.statusCode());
		assertTrue(tail.body().contains("INFO"), tail.body());
		assertEquals(404, call("GET", "/logs/files/read?path=" + URLEncoder.encode(new File(home, "users.txt").getPath(), StandardCharsets.UTF_8), null).statusCode(),
				"no other files");
	}

	@Test
	@Order(7)
	public void storeQueueAndEachServersSettings() throws Exception {
		JsonNode s = ok("GET", "/settings", null);
		JsonNode store = null, queue = null, smtp = null;
		for (JsonNode g : s.get("groups")) {
			String id = g.get("id").asText();
			if (id.equals("store")) {
				store = g;
			} else if (id.equals("queue")) {
				queue = g;
			} else if (id.equals("smtp")) {
				smtp = g;
			}
		}
		assertNotNull(store);
		assertNotNull(queue);
		assertEquals("store", store.get("asset").asText());
		assertTrue(s.get("assets").get("store").get("exists").asBoolean());
		assertEquals(new File(home, "mail/.smtp-queue/queue.properties").getAbsolutePath().replace('\\', '/'), s.get("assets").get("queue").get("path").asText());
		assertEquals(java.util.List.of("smtp", "submission", "submissions"), smtp.get("servers").findValuesAsText("id"));
		boolean submissionTls = false;
		for (JsonNode x : smtp.get("settings")) {
			submissionTls |= x.get("key").asText().equals("parley.mail.submission.requireTls") && x.get("server").asText().equals("submission");
		}
		assertTrue(submissionTls, "each server's own settings");
		for (JsonNode x : store.get("settings")) {
			if (x.get("key").asText().equals("store.sharing")) {
				assertEquals("host", x.get("value").asText());
				assertEquals("sharing", x.get("name").asText());
			}
		}
		// queueActions put the queue's settings in its own file
		String q = Files.readString(new File(home, "mail/.smtp-queue/queue.properties").toPath());
		assertTrue(q.contains("retry=600"), q);
		assertTrue(q.startsWith("# the queue\n"), "the file's comments are kept");
		for (String f : new String[] { "parley-mail.properties", "parley-smtp.properties" }) {
			File file = new File(home, f);
			assertFalse(file.exists() && Files.readString(file.toPath()).contains("retry"), f);
		}
		// the Domains page and the Aliases page changed it too
		assertTrue(q.contains("domains=a.test,b.test"), q);
		assertTrue(q.contains("dkim.keys=b.test:sel1:"), q);
		assertTrue(q.contains("aliases="), "named when the queue's settings changed");

		// one host name: the queue's and every SMTP server's; the SMTP servers need a restart, IMAP doesn't
		JsonNode r = ok("PUT", "/settings", Map.of("changes", Map.of("queue.hostname", "mx2.a.test")));
		assertTrue(r.get("restartNeeded").toString().contains("submission"), r.toString());
		assertFalse(r.get("restartNeeded").toString().contains("imap"), r.toString());
		assertTrue(Files.readString(new File(home, "mail/.smtp-queue/queue.properties").toPath()).contains("hostname=mx2.a.test"));
		String smtpFile = Files.readString(new File(home, "parley-smtp.properties").toPath());
		assertTrue(smtpFile.contains("parley.mail.submissions.hostname=mx2.a.test"), smtpFile);
		assertEquals("mx2.a.test", ok("GET", "/status", null).get("hostname").asText());
		ok("POST", "/services/all/restart-changed", null);

		// a submission server's own setting: only it needs a restart
		r = ok("PUT", "/settings", Map.of("changes", Map.of("parley.mail.submission.maxRecipients", "50")));
		assertEquals("[\"submission\"]", r.get("restartNeeded").toString());
		ok("POST", "/services/all/restart-changed", null);
		for (JsonNode svc : ok("GET", "/services", null)) {
			assertFalse(svc.get("restartNeeded").asBoolean(), svc.toString());
			if (svc.get("enabled").asBoolean()) {
				assertEquals("RUNNING", svc.get("state").asText(), svc.toString());
			}
		}
	}

	@Test
	@Order(8)
	public void settingsPage() throws Exception {
		JsonNode s = ok("GET", "/settings", null);
		assertTrue(s.get("groups").size() > 5);
		assertEquals("[\"parley-mail.properties\",\"parley-smtp.properties\",\"parley-imap.properties\",\"parley-pop3.properties\",\"parley-mail-ftp.properties\"]", s.get("files").toString());
		HttpResponse<String> bad = call("PUT", "/settings", Map.of("changes", Map.of("parley.mail.pop3.port", "abc")));
		assertEquals(400, bad.statusCode());
		ok("PUT", "/settings", Map.of("changes", Map.of("parley.mail.tls.keyStorePassword", "s3cret")));
		String text = ok("GET", "/settings", null).toString();
		assertFalse(text.contains("s3cret"), "secrets aren't sent to the browser");
		ok("POST", "/queue/flush", null);
		ok("POST", "/services/pop3/stop", null);
		for (JsonNode svc : ok("GET", "/services", null)) {
			if (svc.get("id").asText().equals("pop3")) {
				assertEquals("STOPPED", svc.get("state").asText());
			}
		}
		ok("POST", "/services/all/stop", null);
		assertEquals(409, call("POST", "/queue/flush", null).statusCode(), "no queue without SMTP");
	}

	// ------------------------------------------------------------------ tiny clients

	private static String line(BufferedReader in) throws IOException {
		String l = in.readLine();
		if (l == null) {
			throw new IOException("closed");
		}
		return l;
	}

	/** Read a reply (multi-line ones too); fail unless it starts with the code's first digit. */
	private static String reply(BufferedReader in, char ok) throws IOException {
		String l;
		do {
			l = line(in);
		} while (l.length() > 3 && l.charAt(3) == '-');
		if (l.charAt(0) != ok) {
			throw new IOException("Unexpected reply: " + l);
		}
		return l;
	}

	private static void smtp(int port, String from, String to, String message) throws IOException {
		try (Socket s = new Socket("localhost", port)) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			OutputStream out = s.getOutputStream();
			reply(in, '2');
			send(out, "EHLO client.test");
			reply(in, '2');
			send(out, "MAIL FROM:<" + from + ">");
			reply(in, '2');
			send(out, "RCPT TO:<" + to + ">");
			reply(in, '2');
			send(out, "DATA");
			reply(in, '3');
			out.write((message + ".\r\n").getBytes(StandardCharsets.UTF_8));
			reply(in, '2');
			send(out, "QUIT");
		}
	}

	private static void send(OutputStream out, String cmd) throws IOException {
		out.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	/** Log in with POP3 and return the number of messages (STAT). */
	private static int pop3Count(String user, String password) throws IOException {
		try (Socket s = new Socket("localhost", pop3Port)) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			OutputStream out = s.getOutputStream();
			expectOk(line(in));
			send(out, "USER " + user);
			expectOk(line(in));
			send(out, "PASS " + password);
			expectOk(line(in));
			send(out, "STAT");
			String stat = line(in);
			expectOk(stat);
			send(out, "QUIT");
			return Integer.parseInt(stat.split(" ")[1]);
		}
	}

	private static void expectOk(String l) throws IOException {
		if (!l.startsWith("+OK")) {
			throw new IOException("POP3: " + l);
		}
	}

	/** A call as another browser: its own cookies (session) and CSRF token. */
	private static HttpResponse<String> callAs(HttpClient client, String token, String method, String path, Object body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "api" + path)).header("X-CSRF-Token", token);
		if (body != null) {
			b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
		} else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@Order(8)
	public void everyServerAndTheModules() throws Exception {
		// the generic list: every server the launcher runs, here the mail suite's listeners
		JsonNode servers = ok("GET", "/servers", null);
		java.util.Set<String> ids = new java.util.TreeSet<>();
		for (JsonNode s : servers) {
			ids.add(s.get("id").asText());
			assertTrue(s.get("mail").asBoolean(), s.toString());
			assertEquals("this application", s.get("where").asText());
		}
		assertTrue(ids.containsAll(List.of("smtp", "imap", "pop3")), ids.toString());
		JsonNode smtp = null;
		for (JsonNode s : servers) {
			if (s.get("id").asText().equals("smtp")) {
				smtp = s;
			}
		}
		assertEquals("RUNNING", smtp.get("state").asText());
		assertEquals("[\"MailQueue\"]", smtp.get("types").toString(), "the queue's bean, for the Queue page");
		JsonNode modules = ok("GET", "/modules", null);
		assertEquals("[\"queue\"]", modules.get("modules").toString(), "no DNS server runs here");
		assertEquals(400, call("POST", "/servers/smtp/dance", Map.of()).statusCode());
		assertEquals(409, call("POST", "/servers/nothing/start", Map.of()).statusCode());
		assertEquals(200, call("GET", "/servers/pop3/connections", null).statusCode());
	}

	@Test
	@Order(9)
	public void domainAdministrators() throws Exception {
		// the first administrator's domain is the primary one, recorded in the settings
		JsonNode s = ok("GET", "/session", null);
		assertTrue(s.get("primary").asBoolean(), s.toString());
		assertEquals("a.test", s.get("adminDomain").asText());
		boolean recorded = false;
		for (JsonNode g : ok("GET", "/settings", null).get("groups")) {
			for (JsonNode d : g.get("settings")) {
				if (d.get("key").asText().equals("parley.mail.admin.primaryDomain")) {
					assertEquals("a.test", d.get("value").asText());
					recorded = true;
				}
			}
		}
		assertTrue(recorded, "the primary domain is a setting");

		ok("POST", "/domains", Map.of("domain", "c.test"));
		ok("POST", "/users", Map.of("name", "boss@c.test", "password", "bosspass12", "permissions", List.of("READ", "WRITE", "ADMIN")));
		ok("POST", "/users", Map.of("name", "nodomain", "password", "nodomain12", "permissions", List.of("READ", "WRITE", "ADMIN")));
		ok("PUT", "/aliases/" + "sales@c.test", Map.of("members", List.of("boss@c.test")));

		HttpClient other = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
		// an administrator with no domain administers nothing
		assertEquals(401, callAs(other, "none", "POST", "/login", Map.of("user", "nodomain", "password", "nodomain12")).statusCode());

		HttpResponse<String> r = callAs(other, "none", "POST", "/login", Map.of("user", "boss@c.test", "password", "bosspass12"));
		assertEquals(200, r.statusCode(), r.body());
		s = json.readTree(r.body());
		String token = s.get("csrf").asText();
		assertFalse(s.get("primary").asBoolean());
		assertEquals("c.test", s.get("adminDomain").asText());
		assertEquals("[\"c.test\"]", s.get("domains").toString(), "only its domain");

		// its domain's users, aliases and domain only
		JsonNode users = json.readTree(callAs(other, token, "GET", "/users", null).body());
		for (JsonNode u : users) {
			assertEquals("c.test", u.get("domain").asText(), users.toString());
		}
		JsonNode aliases = json.readTree(callAs(other, token, "GET", "/aliases", null).body()).get("aliases");
		assertEquals(1, aliases.size(), aliases.toString());
		JsonNode domains = json.readTree(callAs(other, token, "GET", "/domains", null).body());
		assertEquals(1, domains.size(), domains.toString());

		// it manages users of its domain, and may make them administrators of it
		assertEquals(200, callAs(other, token, "POST", "/users", Map.of("name", "clerk@c.test", "password", "clerkpass1", "permissions", List.of("READ", "WRITE", "ADMIN"))).statusCode());
		assertEquals(200, callAs(other, token, "PUT", "/users/clerk@c.test", Map.of("permissions", List.of("READ", "WRITE"))).statusCode());
		assertEquals(200, callAs(other, token, "DELETE", "/users/clerk@c.test", null).statusCode());
		// but not another domain's, nor users of every domain, nor a maildrop outside its folder
		assertEquals(403, callAs(other, token, "POST", "/users", Map.of("name", "spy@a.test", "password", "spypass123")).statusCode());
		assertEquals(403, callAs(other, token, "POST", "/users", Map.of("name", "spy", "password", "spypass123")).statusCode());
		assertEquals(403, callAs(other, token, "PUT", "/users/admin@a.test", Map.of("password", "takeover12")).statusCode());
		assertEquals(403, callAs(other, token, "DELETE", "/users/admin@a.test", null).statusCode());
		assertEquals(403, callAs(other, token, "GET", "/users/admin@a.test/usage", null).statusCode());
		assertEquals(403, callAs(other, token, "POST", "/users", Map.of("name", "peek@c.test", "password", "peekpass12", "maildrop", "a.test/admin")).statusCode());
		assertEquals(403, callAs(other, token, "PUT", "/aliases/admin@a.test", Map.of("members", List.of("boss@c.test"))).statusCode());
		assertEquals(403, callAs(other, token, "POST", "/domains/a.test/dkim", Map.of("selector", "x")).statusCode());

		// what spans every domain is the primary domain's administrators' only
		for (String[] c : new String[][] { { "GET", "/status" }, { "GET", "/services" }, { "GET", "/settings" }, { "PUT", "/settings" }, { "GET", "/logs" },
				{ "POST", "/domains" }, { "DELETE", "/domains/c.test" }, { "POST", "/queue/flush" }, { "POST", "/services/all/stop" } }) {
			assertEquals(403, callAs(other, token, c[0], c[1], c[0].equals("GET") ? null : Map.of()).statusCode(), c[0] + " " + c[1]);
		}

		// the queue and history only of its domain's mail (the mail so far was a.test's and b.test's)
		assertEquals(200, callAs(other, token, "GET", "/queue", null).statusCode());
		assertTrue(ok("GET", "/history?days=1", null).size() > 0, "the primary sees the history");
		JsonNode history = json.readTree(callAs(other, token, "GET", "/history", null).body());
		assertEquals(0, history.size(), history.toString());

		// the primary domain's administrators may change it: to a local domain with an administrator
		String key = "parley.mail.admin.primaryDomain";
		assertEquals(403, callAs(other, token, "PUT", "/settings", Map.of("changes", Map.of(key, "c.test"))).statusCode(), "not by another domain's");
		assertEquals(400, call("PUT", "/settings", Map.of("changes", Map.of(key, "nowhere.test"))).statusCode(), "not a local domain");
		assertEquals(400, call("PUT", "/settings", Map.of("changes", Map.of(key, "b.test"))).statusCode(), "b.test has no administrator");
		assertEquals(400, call("PUT", "/settings", Map.of("changes", Map.of(key, ""))).statusCode(), "never empty");
		ok("PUT", "/settings", Map.of("changes", Map.of(key, "C.Test")));
		assertFalse(ok("GET", "/session", null).get("primary").asBoolean(), "a.test's administrator manages only a.test now");
		assertEquals(403, call("GET", "/settings", null).statusCode());
		assertTrue(json.readTree(callAs(other, token, "GET", "/session", null).body()).get("primary").asBoolean(), "c.test's manages every domain");
		assertEquals(200, callAs(other, token, "PUT", "/settings", Map.of("changes", Map.of(key, "a.test"))).statusCode(), "and may give it back");
		assertTrue(ok("GET", "/session", null).get("primary").asBoolean());

		// DNS: its domain's zone only (no DNS server runs here: allowed, then 409 for that reason);
		// the DNS server itself and the list of every server are the primary domain's administrators'
		assertEquals(200, callAs(other, token, "GET", "/modules", null).statusCode());
		assertEquals(409, callAs(other, token, "GET", "/dns/zones/c.test", null).statusCode());
		assertEquals(409, callAs(other, token, "POST", "/dns/zones/c.test/publish", Map.of()).statusCode());
		assertEquals(403, callAs(other, token, "GET", "/dns/zones/a.test", null).statusCode());
		assertEquals(409, callAs(other, token, "POST", "/dns/zones/c.test/records/add", Map.of("name", "www", "type", "A", "values", List.of("192.0.2.1"))).statusCode());
		assertEquals(409, callAs(other, token, "POST", "/dns/zones/c.test/records/change", Map.of("name", "www", "type", "A", "data", "192.0.2.1", "newType", "A", "newData", "192.0.2.2")).statusCode());
		assertEquals(403, callAs(other, token, "POST", "/dns/zones/a.test/records/add", Map.of("name", "www", "type", "A", "values", List.of("192.0.2.1"))).statusCode());
		assertEquals(403, callAs(other, token, "POST", "/dns/zones/a.test/records/change", Map.of("name", "www", "type", "A", "data", "192.0.2.1", "newType", "A", "newData", "192.0.2.2")).statusCode());
		assertEquals(403, callAs(other, token, "POST", "/dns/zones/a.test", Map.of()).statusCode());
		assertEquals(403, callAs(other, token, "POST", "/dns/zones/a.test/delete", Map.of("type", "A", "data", "192.0.2.1")).statusCode());
		for (String[] c : new String[][] { { "GET", "/dns" }, { "POST", "/dns/reload" }, { "GET", "/dns/shared" }, { "POST", "/dns/shared/add" }, { "POST", "/dns/shared/change" }, { "POST", "/dns/shared/delete" }, { "GET", "/servers" }, { "POST", "/servers/smtp/stop" }, { "GET", "/servers/smtp/connections" } }) {
			assertEquals(403, callAs(other, token, c[0], c[1], c[0].equals("GET") ? null : Map.of()).statusCode(), c[0] + " " + c[1]);
		}
		assertEquals(false, ok("GET", "/dns", null).get("available").asBoolean());

		// taking the permission away ends its session
		ok("PUT", "/users/boss@c.test", Map.of("permissions", List.of("READ", "WRITE")));
		assertEquals(401, callAs(other, token, "GET", "/users", null).statusCode());
	}
}
