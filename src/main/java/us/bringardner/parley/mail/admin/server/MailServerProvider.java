package us.bringardner.parley.mail.admin.server;

/**
 * A mail server's {@link ServerProvider}: it constructs the server on its protocol's standard port
 * (only a default: the server's own {@code port} setting wins when it starts), tells it whether it
 * is secure (implicit TLS) and gives it the users ({@link MailServices#make}). The server reads
 * the suite's {@code parley-mail.properties}, then its own file, when it starts, and refuses to start,
 * listing every problem, when its settings are unusable (a server with implicit TLS and no key store, say).
 *
 * @since 2.0.0
 */
public abstract class MailServerProvider implements ServerProvider {

	private final String id;
	private final String description;
	private final int standardPort;
	private final boolean implicitTls;

	/**
	 * @param standardPort the port the server is made with, e.g. 995
	 * @param implicitTls  true for TLS from the first byte
	 */
	protected MailServerProvider(String id, String description, int standardPort, boolean implicitTls) {
		this.id = id;
		this.description = description;
		this.standardPort = standardPort;
		this.implicitTls = implicitTls;
	}

	/** The protocol's standard port: the port the server is made with */
	public int standardPort() {
		return standardPort;
	}

	/** True for implicit TLS: TLS from the first byte */
	public boolean implicitTls() {
		return implicitTls;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String description() {
		return description;
	}
}
