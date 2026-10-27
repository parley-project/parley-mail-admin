package us.bringardner.parley.mail.admin.settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.FileRewrite;

/**
 * A properties file that keeps its comments, blank lines and order when settings are changed: a
 * changed setting is rewritten in place, a new one is added at the end and a removed one is
 * deleted. Read and written as UTF-8. Values are parsed by {@link Properties}, so escapes and
 * continuation lines mean what they mean to Java.
 * <p>
 * The file is a local file, or a {@link FileSource} (the mail store's and queue's own settings files,
 * where the mail root is on another file system).
 */
public final class PropertiesFile {

	/** One logical line: a comment or blank line (key null) or a setting. */
	private static final class Line {
		String text;
		String key;

		Line(String text, String key) {
			this.text = text;
			this.key = key;
		}
	}

	private final File file;
	private final FileSource source;
	private final List<Line> lines = new ArrayList<>();
	private final Map<String, String> values = new LinkedHashMap<>();

	public PropertiesFile(File file) {
		this.file = file;
		this.source = null;
	}

	/** A file on a FileSource: read and written through its streams. */
	public PropertiesFile(FileSource source) {
		this.file = null;
		this.source = source;
	}

	/** The local file, or null when it is on a FileSource. */
	public File getFile() {
		return file;
	}

	/** The file's name. */
	public String getName() {
		return file != null ? file.getName() : source.getName();
	}

	/** The file's path, for messages and the pages. */
	public String getPath() {
		return file != null ? file.getAbsolutePath().replace('\\', '/') : source.getAbsolutePath();
	}

	public boolean exists() throws IOException {
		return file != null ? file.isFile() : source.isFile();
	}

	/** When the file last changed (0 if it doesn't exist). */
	public long lastModified() {
		try {
			return file != null ? file.lastModified() : source.exists() ? source.lastModified() : 0;
		} catch (IOException e) {
			return 0;
		}
	}

	/** The file's size (0 if it doesn't exist). */
	public long length() {
		try {
			return file != null ? file.length() : source.exists() ? source.length() : 0;
		} catch (IOException e) {
			return 0;
		}
	}

	/** The file's bytes, or null if it doesn't exist. */
	public byte[] readBytes() throws IOException {
		if (file != null) {
			return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
		}
		if (!source.isFile()) {
			return null;
		}
		try (InputStream in = source.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo(out);
			return out.toByteArray();
		}
	}

	public synchronized void load() throws IOException {
		lines.clear();
		values.clear();
		byte[] bytes = readBytes();
		if (bytes == null) {
			return;
		}
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (text.startsWith("﻿")) {
			text = text.substring(1);
		}
		String[] physical = text.split("\r\n|\r|\n", -1);
		int n = physical.length;
		if (n > 0 && physical[n - 1].isEmpty()) {
			n--; // the file's last line break
		}
		StringBuilder logical = null;
		for (int i = 0; i < n; i++) {
			String p = physical[i];
			String trimmed = p.trim();
			if (logical == null && (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!"))) {
				lines.add(new Line(p, null));
				continue;
			}
			if (logical == null) {
				logical = new StringBuilder(p);
			} else {
				logical.append('\n').append(p);
			}
			if (!endsWithContinuation(p) || i == n - 1) {
				addSetting(logical.toString());
				logical = null;
			}
		}
	}

	private void addSetting(String text) throws IOException {
		Properties p = new Properties();
		p.load(new StringReader(text));
		if (p.isEmpty()) {
			lines.add(new Line(text, null));
			return;
		}
		String key = p.stringPropertyNames().iterator().next();
		String value = p.getProperty(key);
		if (values.containsKey(key)) {
			// a later line wins, as with Properties: drop the earlier one
			lines.removeIf(l -> key.equals(l.key));
		}
		values.put(key, value);
		lines.add(new Line(text, key));
	}

	private static boolean endsWithContinuation(String line) {
		int backslashes = 0;
		for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
			backslashes++;
		}
		return backslashes % 2 == 1;
	}

	/** The settings, in file order. */
	public synchronized Map<String, String> getValues() {
		return new LinkedHashMap<>(values);
	}

	public synchronized String get(String key) {
		return values.get(key);
	}

	/** Set a value, or remove the setting when value is null. */
	public synchronized void set(String key, String value) {
		if (value == null) {
			if (values.remove(key) != null) {
				lines.removeIf(l -> key.equals(l.key));
			}
			return;
		}
		String text = escape(key, true) + "=" + escape(value, false);
		values.put(key, value);
		for (Line l : lines) {
			if (key.equals(l.key)) {
				l.text = text;
				return;
			}
		}
		lines.add(new Line(text, key));
	}

	/**
	 * Write the file: a local one through a temporary file, keeping its permissions and group
	 * ({@link FileRewrite}); one on a FileSource through its output stream.
	 */
	public synchronized void save() throws IOException {
		StringBuilder out = new StringBuilder();
		for (Line l : lines) {
			out.append(l.text).append('\n');
		}
		byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
		if (file != null) {
			FileRewrite.write(file, bytes);
			return;
		}
		FileSource dir = source.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs()) {
			throw new IOException("Can't make " + dir.getAbsolutePath());
		}
		try (OutputStream o = source.getOutputStream()) {
			o.write(bytes);
		}
	}

	/** Escape a key or value as Properties.store does (but UTF-8 characters are kept). */
	static String escape(String s, boolean isKey) {
		StringBuilder out = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '\\':
				out.append("\\\\");
				break;
			case '\t':
				out.append("\\t");
				break;
			case '\n':
				out.append("\\n");
				break;
			case '\r':
				out.append("\\r");
				break;
			case '\f':
				out.append("\\f");
				break;
			case ' ':
				out.append(i == 0 || isKey ? "\\ " : " ");
				break;
			case '=':
			case ':':
			case '#':
			case '!':
				if (isKey || i == 0) {
					out.append('\\');
				}
				out.append(c);
				break;
			default:
				out.append(c);
			}
		}
		return out.toString();
	}
}
