package us.bringardner.parley.mail.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The page's script must parse: a syntax error in it (an unescaped quote in a text) leaves the page on "Loading…"
 * with nothing in the server's logs. Checked with Node when it is installed.
 */
public class AppScriptTest {

	@Test
	public void appJsParses() throws Exception {
		Path script = Path.of("src/main/webapp/js/app.js");
		Assumptions.assumeTrue(Files.isRegularFile(script), "run from the module's folder");
		Process p;
		try {
			p = new ProcessBuilder("node", "--check", script.toString()).redirectErrorStream(true).start();
		} catch (IOException e) {
			Assumptions.assumeTrue(false, "Node is not installed");
			return;
		}
		try {
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(60, TimeUnit.SECONDS)) {
				throw new AssertionError("node did not finish: " + out);
			}
			assertEquals(0, p.exitValue(), out);
		} finally {
			p.destroyForcibly();
			p.getInputStream().close();
		}
	}
}
