package us.bringardner.parley.mail.admin.server;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.management.InstanceAlreadyExistsException;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * What the servers one {@link ServerLauncher} runs share in their JVM: objects by name (the mail
 * servers' user list, see {@link SharedUsers}), and the management beans the launcher registers for
 * them. A shared object lives as long as the launcher: restarting one server keeps it, so its
 * settings change only when every server using it, or the process, is restarted. (Moved here from
 * parley-net: what servers share is the application's choice.)
 *
 * @since 2.0.0
 */
public final class ServerContext {

	/** The JMX domain of Parley's management beans (parley-net's {@code IBaseServer.JMX_DOMAIN}) */
	public static final String JMX_DOMAIN = us.bringardner.parley.net.server.IBaseServer.JMX_DOMAIN;

	private final Map<String, Object> shared = new ConcurrentHashMap<>();
	private final List<ObjectName> registered = new CopyOnWriteArrayList<>();
	private final List<AutoCloseable> closers = new CopyOnWriteArrayList<>();
	private final MBeanServer mbeans;

	public ServerContext() {
		this(ManagementFactory.getPlatformMBeanServer());
	}

	public ServerContext(MBeanServer mbeans) {
		this.mbeans = mbeans;
	}

	/**
	 * The object shared under key, made by make the first time it's asked for.
	 *
	 * @throws ClassCastException if another type is shared under key
	 */
	public synchronized <T> T shared(String key, Class<T> type, Callable<? extends T> make) throws Exception {
		Object o = shared.get(key);
		if (o == null) {
			o = make.call();
			if (o == null) {
				throw new IllegalStateException("Nothing made for " + key);
			}
			shared.put(key, o);
			if (o instanceof AutoCloseable) {
				closers.add((AutoCloseable) o);
			}
		}
		return type.cast(o);
	}

	/** The object shared under key, or null */
	public Object get(String key) {
		return shared.get(key);
	}

	/** The keys of the objects shared now */
	public java.util.Set<String> sharedKeys() {
		return Collections.unmodifiableSet(new java.util.TreeSet<>(shared.keySet()));
	}

	/** Closed when the launcher closes (after its servers stopped) */
	public void onClose(AutoCloseable closer) {
		closers.add(closer);
	}

	public MBeanServer getMBeanServer() {
		return mbeans;
	}

	/**
	 * Register a management bean as {@code us.bringardner.parley:type=TYPE,name=NAME} (with
	 * {@code ,instance=N} added when that name is taken, by another launcher in the JVM); it is
	 * unregistered when the launcher closes.
	 */
	public ObjectName register(String type, String name, Object mbean) throws JMException {
		String base = JMX_DOMAIN + ":type=" + quoteIfNeeded(type) + ",name=" + quoteIfNeeded(name);
		for (int i = 1;; i++) {
			ObjectName n = new ObjectName(i == 1 ? base : base + ",instance=" + i);
			try {
				mbeans.registerMBean(compliant(mbean), n);
				registered.add(n);
				return n;
			} catch (InstanceAlreadyExistsException e) {
				if (i > 100) {
					throw e;
				}
			}
		}
	}

	/**
	 * The bean as JMX takes it: a class named X implementing XMBean is a standard MBean as it is;
	 * otherwise it is wrapped as the first ...MBean interface it implements.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static Object compliant(Object mbean) throws JMException {
		if (mbean instanceof javax.management.DynamicMBean) {
			return mbean;
		}
		for (Class<?> c = mbean.getClass(); c != null; c = c.getSuperclass()) {
			for (Class<?> i : c.getInterfaces()) {
				if (i.getName().equals(c.getName() + "MBean") || i.getName().equals(c.getName() + "MXBean")) {
					return mbean;
				}
			}
		}
		for (Class<?> c = mbean.getClass(); c != null; c = c.getSuperclass()) {
			for (Class<?> i : c.getInterfaces()) {
				if (i.getSimpleName().endsWith("MBean")) {
					return new javax.management.StandardMBean(mbean, (Class) i);
				}
			}
		}
		return mbean;
	}

	private static String quoteIfNeeded(String s) {
		return s.matches("[A-Za-z0-9_.\\-]+") ? s : ObjectName.quote(s);
	}

	public void unregister(ObjectName name) {
		try {
			if (mbeans.isRegistered(name)) {
				mbeans.unregisterMBean(name);
			}
		} catch (JMException e) {
			// gone already
		}
		registered.remove(name);
	}

	/** Unregister the beans and close the shared objects (the last made first). */
	public void close() {
		for (ObjectName n : new ArrayList<>(registered)) {
			unregister(n);
		}
		List<AutoCloseable> list = new ArrayList<>(closers);
		Collections.reverse(list);
		for (AutoCloseable c : list) {
			try {
				c.close();
			} catch (Exception e) {
				System.err.println("Can't close " + c + ": " + e);
			}
		}
		closers.clear();
		shared.clear();
	}
}
