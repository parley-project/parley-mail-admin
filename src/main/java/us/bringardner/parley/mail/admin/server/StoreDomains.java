package us.bringardner.parley.mail.admin.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.server.DomainListUnavailableException;
import us.bringardner.parley.net.server.IDomainList;

/**
 * The mail suite's local domains: the {@code domains} setting of the mail queue's
 * {@code queue.properties}, which lives in the mail store (so it can be in a database), the list
 * the SMTP servers deliver for and parley-mail-admin's Domains page changes. The file is read
 * again at most every {@value #CHECK_MILLIS} ms; if it can't be read, the list read last is kept.
 *
 * @since 2.0.0
 */
final class StoreDomains implements IDomainList {

	/** The setting in the queue's settings that lists the domains. */
	static final String KEY = "domains";
	/** The queue's settings file (parley-smtp's MailQueue.SETTINGS_FILE). */
	static final String FILE = "queue.properties";
	/** The shortest time between two reads of the file. */
	static final long CHECK_MILLIS = 5000;

	private final FileSource file;
	private volatile Set<String> domains = Collections.emptySet();
	private volatile long checked;

	/** @param queueDir the mail queue's folder in the store (.smtp-queue) */
	StoreDomains(FileSource queueDir) throws IOException {
		this.file = queueDir.getChild(FILE);
	}

	@Override
	public void initialize(Function<String, String> setting) throws IOException {
		reload();
	}

	@Override
	public boolean contains(String domain) throws DomainListUnavailableException {
		reloadIfChanged();
		return domain != null && domains.contains(normalize(domain));
	}

	@Override
	public Set<String> getDomains() throws DomainListUnavailableException {
		reloadIfChanged();
		return domains;
	}

	@Override
	public void reload() throws DomainListUnavailableException {
		read();
	}

	@Override
	public boolean reloadIfChanged() throws DomainListUnavailableException {
		if (System.currentTimeMillis() - checked < CHECK_MILLIS) {
			return false;
		}
		return read();
	}

	private synchronized boolean read() throws DomainListUnavailableException {
		Properties p = new Properties();
		try {
			if (!file.isFile()) {
				throw new IOException(file.getAbsolutePath() + " does not exist");
			}
			try (InputStream in = file.getInputStream(); InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				p.load(r);
			}
		} catch (IOException | RuntimeException e) {
			checked = System.currentTimeMillis(); // don't read a file that is not there on every login
			throw new DomainListUnavailableException("Can't read the mail domains from " + file.getAbsolutePath() + ": " + e.getMessage(), e);
		}
		Set<String> d = new TreeSet<>();
		String value = p.getProperty(KEY);
		if (value != null) {
			for (String n : value.split("[,\\s]+")) {
				if (!n.isEmpty()) {
					d.add(normalize(n));
				}
			}
		}
		checked = System.currentTimeMillis();
		boolean changed = !d.equals(domains);
		domains = Collections.unmodifiableSet(d);
		return changed;
	}

	private static String normalize(String domain) {
		String d = domain.trim().toLowerCase(Locale.ROOT);
		return d.endsWith(".") ? d.substring(0, d.length() - 1) : d;
	}
}
