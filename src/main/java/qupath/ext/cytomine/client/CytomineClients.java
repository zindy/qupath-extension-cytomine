/*
 * QuPath extension for Cytomine
 * Copyright (C) 2026 Egor Zindy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package qupath.ext.cytomine.client;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cytomine.ui.CytomineLoginDialog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.prefs.Preferences;

/**
 * Registry of the authenticated clients, keyed by core API URL.
 * <p>
 * Only non-sensitive settings (server URLs and last username) are persisted. Passwords and tokens are not.
 */
public final class CytomineClients {

	private static final Logger logger = LoggerFactory.getLogger(CytomineClients.class);

	private static final Preferences PREFS = Preferences.userNodeForPackage(CytomineClients.class);
	private static final String KEY_LAST_SERVER = "lastServer";
	private static final String KEY_LAST_USERNAME = "lastUsername";
	private static final String KEY_IAM_PREFIX = "iam.";

	private static final Map<String, CytomineClient> clients = new ConcurrentHashMap<>();

	private CytomineClients() {}

	/**
	 * Get the client already logged in to a server.
	 * @param coreUrl URL of the core API
	 * @return the client, if any
	 */
	public static Optional<CytomineClient> get(String coreUrl) {
		return Optional.ofNullable(clients.get(CytomineUrls.normalizeCoreUrl(coreUrl)));
	}

	/**
	 * @return all logged in clients
	 */
	public static Collection<CytomineClient> getAll() {
		return clients.values();
	}

	/**
	 * Register a client and remember its (non-sensitive) settings.
	 * @param client the client
	 */
	public static void add(CytomineClient client) {
		var core = client.getCoreUri().toString();
		clients.put(core, client);
		PREFS.put(KEY_LAST_SERVER, core);
		PREFS.put(KEY_LAST_USERNAME, client.getUsername());
		PREFS.put(KEY_IAM_PREFIX + key(core), client.getIamUri().toString());
	}

	/**
	 * Forget the client for a server. Tokens are discarded with the client.
	 * @param coreUrl URL of the core API
	 */
	public static void remove(String coreUrl) {
		clients.remove(CytomineUrls.normalizeCoreUrl(coreUrl));
	}

	/**
	 * @return the server URL used for the last successful login, or an empty string
	 */
	public static String getLastServer() {
		return PREFS.get(KEY_LAST_SERVER, "");
	}

	/**
	 * @return the username used for the last successful login, or an empty string
	 */
	public static String getLastUsername() {
		return PREFS.get(KEY_LAST_USERNAME, "");
	}

	/**
	 * Get the IAM URL previously used with a server.
	 * @param coreUrl URL of the core API
	 * @return the IAM URL, or an empty string
	 */
	public static String getSavedIamUrl(String coreUrl) {
		if (coreUrl == null || coreUrl.isBlank())
			return "";
		return PREFS.get(KEY_IAM_PREFIX + key(CytomineUrls.normalizeCoreUrl(coreUrl)), "");
	}

	/**
	 * Get a client for a server, prompting the user to log in if necessary.
	 * May be called from any thread.
	 * @param coreUrl URL of the core API, or null to let the user choose the server
	 * @return the client, or empty if the user cancelled
	 */
	public static Optional<CytomineClient> getOrLogin(String coreUrl) {
		if (coreUrl != null) {
			var existing = get(coreUrl);
			if (existing.isPresent())
				return existing;
		}
		if (Platform.isFxApplicationThread())
			return CytomineLoginDialog.prompt(coreUrl);
		var task = new FutureTask<>(() -> CytomineLoginDialog.prompt(coreUrl));
		Platform.runLater(task);
		try {
			return task.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			logger.error("Login dialog failed", e);
		}
		return Optional.empty();
	}

	private static String key(String s) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
