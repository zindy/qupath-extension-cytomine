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
import qupath.fx.dialogs.Dialogs;
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
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.prefs.Preferences;

/**
 * Registry of the authenticated clients, keyed by core API URL.
 * <p>
 * Only non-sensitive settings (server URLs and last username) are persisted. Passwords and tokens are not: they only
 * live in memory, in the {@link CytomineClient}, until the user logs out.
 */
public final class CytomineClients {

	private static final Logger logger = LoggerFactory.getLogger(CytomineClients.class);

	private static final Preferences PREFS = Preferences.userNodeForPackage(CytomineClients.class);
	private static final String KEY_LAST_SERVER = "lastServer";
	private static final String KEY_LAST_USERNAME = "lastUsername";
	private static final String KEY_IAM_PREFIX = "iam.";

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.cytomine.ui.strings");

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
	 * Get a client that is already logged in, without prompting: the one for the server of the last login if there is
	 * one, otherwise any other. Clients that can no longer renew their session are ignored.
	 * @return the client, if any
	 */
	public static Optional<CytomineClient> getActive() {
		var last = getLastServer();
		if (!last.isBlank()) {
			var client = get(last).filter(c -> !c.needsLogin());
			if (client.isPresent())
				return client;
		}
		return clients.values().stream().filter(c -> !c.needsLogin()).findFirst();
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
	 * Forget the client for a server, and the tokens and password it holds.
	 * @param coreUrl URL of the core API
	 */
	public static void remove(String coreUrl) {
		var removed = clients.remove(CytomineUrls.normalizeCoreUrl(coreUrl));
		if (removed != null)
			removed.logout();
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
			// A client that can no longer renew its session (password refused) is replaced by a new login
			var existing = get(coreUrl).filter(c -> !c.needsLogin());
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

	/**
	 * Tell the user that the session could not be renewed, e.g. from a background thread that failed to read a tile.
	 * The message is only shown once for a client, until its next successful authentication.
	 * May be called from any thread.
	 * @param client the client whose session could not be renewed
	 * @param e the failure, whose message is shown to the user
	 */
	public static void reportAuthFailure(CytomineClient client, CytomineAuthException e) {
		if (!client.claimAuthFailureReport())
			return;
		logger.error("Unable to renew the Cytomine session", e);
		var title = resources.getString("session.error.title");
		var message = e.getLocalizedMessage();
		Platform.runLater(() -> Dialogs.showErrorMessage(title, message));
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
