package hu.zforgo.testng.tools.context;

import hu.zforgo.common.util.CollectionUtil;
import hu.zforgo.common.util.StringUtil;
import hu.zforgo.testing.context.ConfigurationPathProvider;
import hu.zforgo.testing.context.ContextInitializationException;
import hu.zforgo.testing.context.ContextInitializationFailure;
import hu.zforgo.testing.context.Defaults;
import hu.zforgo.testing.context.InvalidConfigurationException;
import hu.zforgo.testing.tools.configuration.Configuration;
import hu.zforgo.testing.tools.configuration.ConfigurationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.locks.ReentrantLock;


public class TestNGToolsContext {

	private static final Logger LOG = LoggerFactory.getLogger(TestNGToolsContext.class);
	private static volatile boolean inited;
	private static volatile TestNGToolsContext instance;
	private static volatile ClassLoader contextClassLoader;
	private static volatile Configuration contextConfig;
	private static volatile List<Path> configLookupFolders;

	private static volatile transient HashMap<String, Configuration> configCache = new HashMap<>();
	private final transient ReentrantLock lock = new ReentrantLock();


	private TestNGToolsContext() {
	}

	protected static synchronized void createContext() throws ContextInitializationException {
		if (instance != null) {
			LOG.warn("TestNGTools context class ({}) has already created.", instance.getClass().getName());
			return;
		}
		LOG.info("Creating TestNGToolsContext...");
		TestNGToolsContext currentContext = new TestNGToolsContext();

		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if (cl == null) {
			cl = TestNGToolsContext.class.getClassLoader();
			if (cl == null) {
				cl = ClassLoader.getSystemClassLoader();
			}
		}
		if (contextClassLoader == null) {
			contextClassLoader = cl;
		}

		try {
			{
				final String configFilename = StringUtil.isEmpty(System.getProperty(Defaults.CONFIG_FILENAME_VARIABLE)) ? Defaults.CONFIG_FILENAME : System.getProperty(Defaults.CONFIG_FILENAME_VARIABLE);
				Configuration tmp = ConfigurationFactory.load(configFilename);
				if (contextConfig == null) {
					if (tmp.isEmpty()) {
						throw new ContextInitializationFailure(String.format("Context configuration shouldn't be empty [%s]", configFilename));
					}
					contextConfig = tmp;
				}
			}
			{
				List<Path> tmp = prepareConfigPaths();
				if (configLookupFolders == null) {
					configLookupFolders = Collections.unmodifiableList(tmp);
				}
			}
			currentContext.init();
		} catch (ContextInitializationException e) {
			LOG.error("An error occurred during initialize context", e);
		} catch (Exception e) {
			LOG.error("A fatal error occurred during initialize context", e);
			throw new ContextInitializationFailure(e);
		}
		LOG.info("TestNGToolsContext was successfully initialized.");

		if (instance == null) {
			instance = currentContext;
		}
	}

	private static List<Path> prepareConfigPaths() {
		final Path configRoot = Paths.get(System.getProperty(Defaults.LOOKUP_ROOT, System.getProperty(Defaults.BASEDIR, System.getProperty(Defaults.USER_DIR))));
		Iterator<ConfigurationPathProvider> providerIterator = ServiceLoader.load(ConfigurationPathProvider.class).iterator();
		return providerIterator.hasNext() ? providerIterator.next().getPaths(configRoot, contextConfig) : Collections.singletonList(configRoot);
	}

	public static TestNGToolsContext getInstance() {
		return instance;
	}

	protected void init() throws ContextInitializationException {
		inited = true;
	}

	public Configuration getConfig(String name, Configuration defaultConfig) {
		try {
			return getConfig(name);
		} catch (InvalidConfigurationException e) {
			if (defaultConfig == null) {
				throw new IllegalArgumentException("Default config cannot be null if given config wasn't found");
			}
			return defaultConfig;
		}
	}

	public Configuration getConfig(String name) {
		if (StringUtil.isWhite(name)) {
			throw new IllegalArgumentException("Config name cannot be null");
		}
		Configuration c = configCache.get(name);
		if (c != null) {
			return c;
		}
		try {
			c = CollectionUtil.isEmpty(configLookupFolders) ? ConfigurationFactory.load(name, contextClassLoader) : ConfigurationFactory.load(name, configLookupFolders);
			if (c != null) {
				cacheConfig(name, c);
			}
			return c;
		} catch (IOException e) {
			throw new InvalidConfigurationException("Failed to load configuration: " + name, e);
		}
	}

	private void cacheConfig(String name, Configuration config) {
		if (StringUtil.isWhite(name) || Objects.isNull(config)) {
			return;
		}

		final ReentrantLock lock = this.lock;
		lock.lock();
		try {
			int size = configCache.size();
			HashMap<String, Configuration> newCache = new HashMap<>((size * 4 / 3) + 1);
			newCache.putAll(configCache);
			newCache.put(name, config);
			configCache = newCache;
		} finally {
			lock.unlock();
		}
	}

	protected synchronized void finalize() {
		inited = false;
	}
}
