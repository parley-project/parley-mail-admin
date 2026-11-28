package us.bringardner.parley.mail.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.mail.admin.log.LogBuffer;

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

	/** The page lists the sources itself (its filter and its levels); the list must be the application's */
	@Test
	public void theScriptsSourcesAreTheApplications() throws Exception {
		Path script = Path.of("src/main/webapp/js/app.js");
		Assumptions.assumeTrue(Files.isRegularFile(script), "run from the module's folder");
		Matcher m = Pattern.compile("const SOURCES = \\[([^\\]]*)\\]").matcher(Files.readString(script, StandardCharsets.UTF_8));
		Assumptions.assumeTrue(m.find(), "the script has no list of sources");
		List<String> inScript = new ArrayList<>();
		for (String s : m.group(1).split(",")) {
			inScript.add(s.trim().replace("'", ""));
		}
		List<String> expected = new ArrayList<>();
		for (LogBuffer.Source s : LogBuffer.Source.values()) {
			expected.add(s.name());
		}
		assertEquals(expected, inScript);
	}
}
