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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Minimal client for the Cytomine core REST API.
 * <p>
 * Authentication is performed against the Keycloak (IAM) server of the Cytomine instance using the OpenID Connect
 * password grant. The short-lived tokens are kept in memory, and so is the password, so that the session can be renewed
 * without bothering the user when it times out or is closed by the server. The password is never written to disk or
 * logged. It is wiped by {@link #logout()}, and as soon as the server refuses it.
 * <p>
 * When a request needs a new session, the refresh token is tried first, then the password. Transient failures (server
 * restarting, network problem, HTTP 5xx) are retried a few times, a refused password is not (to avoid locking the
 * account). If the session cannot be renewed, a {@link CytomineAuthException} describes why.
 */
public class CytomineClient {

	private static final Logger logger = LoggerFactory.getLogger(CytomineClient.class);

	/** OpenID client used by the Cytomine web interface. Public client, direct access grants enabled. */
	public static final String DEFAULT_CLIENT_ID = "core";

	private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(30);

	// Server errors are often transient when many tiles are requested at once
	private static final int MAX_ATTEMPTS = 3;
	private static final long RETRY_DELAY_MS = 250;
	private static final int MAX_CONCURRENT_REQUESTS = 12;

	// Renewing the session with the password: transient failures are retried this many times in total...
	private static final int MAX_LOGIN_ATTEMPTS = 3;
	private static final long LOGIN_RETRY_DELAY_MS = 1000;
	// ...then requests fail immediately for a while, instead of each pending tile request waiting for the same retries
	private static final Duration LOGIN_FAILURE_COOLDOWN = Duration.ofSeconds(15);

	private final java.util.concurrent.Semaphore requestPermits = new java.util.concurrent.Semaphore(MAX_CONCURRENT_REQUESTS);

	private final URI coreUri;
	private final URI iamUri;
	private final String clientId;
	private final HttpClient http;

	private String username;
	private long userId = -1;
	private String accessToken;
	private String refreshToken;
	private Instant accessExpiry = Instant.MIN;
	private Instant refreshExpiry = Instant.MIN;

	// Kept in memory only, to renew the session. Null once the user logged out or the server refused it.
	private char[] password;
	private CytomineAuthException lastLoginFailure;
	private Instant lastLoginFailureTime = Instant.MIN;
	private final AtomicBoolean authFailureReported = new AtomicBoolean();

	private CytomineClient(URI coreUri, URI iamUri, String clientId) {
		this.coreUri = coreUri;
		this.iamUri = iamUri;
		this.clientId = clientId;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * Create a client and authenticate. The password array is cleared before returning, but the client keeps a copy in
	 * memory to renew the session when it expires (see {@link #logout()}).
	 * @param coreUrl URL of the core API, e.g. {@code http://127.0.0.1:8080/api}
	 * @param iamUrl URL of the Keycloak realm, e.g. {@code http://127.0.0.1:8100/iam/realms/cytomine}
	 * @param username the Cytomine username
	 * @param password the password; cleared after use
	 * @return an authenticated client
	 * @throws IOException if authentication fails
	 */
	public static CytomineClient login(String coreUrl, String iamUrl, String username, char[] password) throws IOException {
		var client = new CytomineClient(
				URI.create(CytomineUrls.normalizeCoreUrl(coreUrl)),
				URI.create(CytomineUrls.normalizeIamUrl(iamUrl)),
				DEFAULT_CLIENT_ID);
		try {
			client.username = username;
			client.password = password.clone();
			client.requestToken(client.passwordGrant());
		} catch (IOException | RuntimeException e) {
			client.logout();
			throw e;
		} finally {
			Arrays.fill(password, '\0');
		}
		return client;
	}

	/**
	 * @return the URL of the core API, ending with a slash
	 */
	public URI getCoreUri() {
		return coreUri;
	}

	/**
	 * @return the URL of the IAM realm, without a trailing slash
	 */
	public URI getIamUri() {
		return iamUri;
	}

	/**
	 * @return the name of the authenticated user
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * Forget the tokens and the password. Requests made afterwards fail with a {@link CytomineAuthException}.
	 */
	public synchronized void logout() {
		wipePassword();
		accessToken = null;
		refreshToken = null;
		accessExpiry = Instant.MIN;
		refreshExpiry = Instant.MIN;
	}

	/**
	 * @return true if the session can no longer be renewed automatically (the user logged out, or the server refused
	 * the saved password) so that the user has to log in again
	 */
	public synchronized boolean needsLogin() {
		return password == null;
	}

	/**
	 * Used to tell the user about a failure to renew the session only once, not once per failed request.
	 * @return true the first time it is called after a successful authentication
	 */
	public boolean claimAuthFailureReport() {
		return authFailureReported.compareAndSet(false, true);
	}

	/**
	 * Request a JSON resource.
	 * @param path path relative to the core API, e.g. {@code project.json?max=50}
	 * @return the parsed response
	 * @throws IOException if the request fails
	 */
	public JsonElement getJson(String path) throws IOException {
		return JsonParser.parseString(new String(getBytes(path, "application/json"), StandardCharsets.UTF_8));
	}

	/**
	 * Request a JSON object.
	 * @param path path relative to the core API
	 * @return the response as a JSON object
	 * @throws IOException if the request fails or the response is not an object
	 */
	public JsonObject getJsonObject(String path) throws IOException {
		var element = getJson(path);
		if (!element.isJsonObject())
			throw new IOException("Unexpected response for " + path);
		return element.getAsJsonObject();
	}

	/**
	 * Send a request with a JSON body (or no body) and read the JSON response.
	 * Requests are not retried, since they may modify data. The only exception is when the server answers HTTP 401:
	 * the request was not processed, so it is sent again after renewing the session.
	 * @param method HTTP method, e.g. POST, PUT or DELETE
	 * @param path path relative to the core API
	 * @param body the body, or null
	 * @return the response as a JSON object, empty if the response has no body
	 * @throws IOException if the request fails
	 */
	public JsonObject sendJson(String method, String path, JsonElement body) throws IOException {
		var publisher = body == null ? HttpRequest.BodyPublishers.noBody() :
				HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
		var uri = coreUri.resolve(path);
		var response = sendAuthorized(token -> HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(60))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + token)
				.method(method, publisher)
				.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2)
			throw new IOException("HTTP " + response.statusCode() + " for " + method + " " + uri);
		var text = response.body();
		if (text == null || text.isBlank())
			return new JsonObject();
		var element = JsonParser.parseString(text);
		return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	/**
	 * @return the id of the authenticated user
	 * @throws IOException if the request fails
	 */
	public synchronized long getUserId() throws IOException {
		if (userId < 0)
			userId = getJsonObject("user/current.json").get("id").getAsLong();
		return userId;
	}

	/**
	 * Request binary content, e.g. a tile.
	 * @param path path relative to the core API
	 * @param accept value of the accept header
	 * @return the response body
	 * @throws IOException if the request fails
	 */
	public byte[] getBytes(String path, String accept) throws IOException {
		IOException lastError = null;
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			if (attempt > 0) {
				try {
					Thread.sleep(RETRY_DELAY_MS * (1L << (attempt - 1)));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Request interrupted", e);
				}
			}
			var uri = coreUri.resolve(path);
			try {
				requestPermits.acquire();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Request interrupted", e);
			}
			try {
				var response = sendAuthorized(token -> HttpRequest.newBuilder(uri)
						.timeout(Duration.ofSeconds(60))
						.header("Accept", accept)
						.header("Authorization", "Bearer " + token)
						.GET()
						.build(), HttpResponse.BodyHandlers.ofByteArray());
				int status = response.statusCode();
				if (status / 100 == 2)
					return response.body();
				lastError = new IOException("HTTP " + status + " for " + uri);
				// Client errors will not go away by retrying
				if (status / 100 == 4 && status != 408 && status != 429)
					throw lastError;
			} catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
				lastError = e;
			} finally {
				requestPermits.release();
			}
			logger.debug("Request failed (attempt {} of {}): {}", attempt + 1, MAX_ATTEMPTS, lastError.getMessage());
		}
		throw lastError;
	}

	/**
	 * Send a request that needs an access token, and send it once more if the server refuses the token although it
	 * has not expired (session closed on the server, server restarted, clock skew...). HTTP 401 means that the request
	 * was not processed, so it is safe to replay it even if it modifies data.
	 */
	private <T> HttpResponse<T> sendAuthorized(Function<String, HttpRequest> requestForToken,
											   HttpResponse.BodyHandler<T> handler) throws IOException {
		var token = getAccessToken();
		var response = send(requestForToken.apply(token), handler);
		if (response.statusCode() != 401)
			return response;
		logger.debug("HTTP 401 for {}, renewing the session", response.uri());
		invalidate(token);
		token = getAccessToken();
		response = send(requestForToken.apply(token), handler);
		if (response.statusCode() == 401)
			throw new CytomineAuthException("Cytomine refused the new session (HTTP 401 for " + response.uri() +
					"). Check that the server is configured properly.", false);
		return response;
	}

	private synchronized void invalidate(String rejectedToken) {
		// Another thread may have renewed the session in the meantime
		if (rejectedToken.equals(accessToken)) {
			accessToken = null;
			accessExpiry = Instant.MIN;
		}
	}

	private synchronized String getAccessToken() throws IOException {
		var now = Instant.now().plus(TOKEN_EXPIRY_MARGIN);
		if (accessToken != null && now.isBefore(accessExpiry))
			return accessToken;
		renewSession();
		return accessToken;
	}

	/**
	 * Get new tokens, with the refresh token if it is still good and with the password otherwise.
	 */
	private synchronized void renewSession() throws IOException {
		if (lastLoginFailure != null && Instant.now().isBefore(lastLoginFailureTime.plus(LOGIN_FAILURE_COOLDOWN)))
			throw new CytomineAuthException(lastLoginFailure.getMessage(), false, lastLoginFailure);

		var now = Instant.now().plus(TOKEN_EXPIRY_MARGIN);
		if (refreshToken != null && now.isBefore(refreshExpiry)) {
			try {
				requestToken(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
				return;
			} catch (CytomineAuthException e) {
				// The refresh token is not valid any more (SSO session ended, server restarted...), or the server
				// is in trouble: the password will tell
				logger.debug("Unable to refresh the Cytomine session ({}), logging in again", e.getMessage());
			}
		}
		if (password == null)
			throw new CytomineAuthException("Cytomine session expired - please log in again", true);
		loginWithPassword();
	}

	private void loginWithPassword() throws IOException {
		CytomineAuthException lastError = null;
		for (int attempt = 0; attempt < MAX_LOGIN_ATTEMPTS; attempt++) {
			if (attempt > 0) {
				try {
					Thread.sleep(LOGIN_RETRY_DELAY_MS * (1L << (attempt - 1)));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Request interrupted", e);
				}
			}
			try {
				requestToken(passwordGrant());
				return;
			} catch (CytomineAuthException e) {
				lastError = e;
				if (e.isCredentialsRejected()) {
					// Trying again cannot work, and could lock the account: forget the password, the user has to
					// log in again
					wipePassword();
					throw new CytomineAuthException("Cytomine session expired and the password was rejected when " +
							"logging in again (" + e.getMessage() + "). Please log in again.", true, e);
				}
				logger.debug("Login attempt {} of {} failed: {}", attempt + 1, MAX_LOGIN_ATTEMPTS, e.getMessage());
			}
		}
		lastLoginFailure = new CytomineAuthException("Unable to log in again to Cytomine after " + MAX_LOGIN_ATTEMPTS +
				" attempts: " + lastError.getMessage() + ". The server may be unavailable, try again later.",
				false, lastError);
		lastLoginFailureTime = Instant.now();
		throw lastLoginFailure;
	}

	private Map<String, String> passwordGrant() {
		return Map.of(
				"grant_type", "password",
				"username", username,
				"password", new String(password));
	}

	private void wipePassword() {
		if (password != null) {
			Arrays.fill(password, '\0');
			password = null;
		}
	}

	private synchronized void requestToken(Map<String, String> params) throws IOException {
		var form = new LinkedHashMap<String, String>(params);
		form.put("client_id", clientId);
		var body = new StringBuilder();
		form.forEach((k, v) -> {
			if (!body.isEmpty())
				body.append('&');
			body.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
					.append('=')
					.append(URLEncoder.encode(v, StandardCharsets.UTF_8));
		});
		var request = HttpRequest.newBuilder(URI.create(iamUri + "/protocol/openid-connect/token"))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HttpResponse<String> response;
		try {
			response = send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			if (e.getCause() instanceof InterruptedException)
				throw e;
			throw new CytomineAuthException("Unable to reach the authentication server " + iamUri + ": " +
					(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), false, e);
		}
		int status = response.statusCode();
		if (status / 100 != 2) {
			var detail = errorDetail(response.body());
			logger.debug("Token request failed with HTTP {} {}", status, detail);
			// Keycloak answers 400 or 401 when it refuses the credentials or the refresh token
			boolean rejected = status == 400 || status == 401;
			String message;
			if (rejected) {
				accessToken = null;
				refreshToken = null;
				accessExpiry = Instant.MIN;
				refreshExpiry = Instant.MIN;
				if ("refresh_token".equals(params.get("grant_type")))
					message = "Session refresh refused" + (detail == null ? "" : " (" + detail + ")");
				else if (detail == null || detail.equalsIgnoreCase("Invalid user credentials"))
					message = "Invalid username or password";
				else
					message = "Login refused by the server: " + detail;
			} else {
				message = "Authentication server error (HTTP " + status + ")" + (detail == null ? "" : ": " + detail);
			}
			throw new CytomineAuthException(message, rejected);
		}
		try {
			var json = JsonParser.parseString(response.body()).getAsJsonObject();
			var now = Instant.now();
			var newAccessToken = json.get("access_token").getAsString();
			var newAccessExpiry = now.plusSeconds(json.get("expires_in").getAsLong());
			accessToken = newAccessToken;
			accessExpiry = newAccessExpiry;
			refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
			refreshExpiry = json.has("refresh_expires_in") ?
					now.plusSeconds(json.get("refresh_expires_in").getAsLong()) : accessExpiry;
		} catch (RuntimeException e) {
			// e.g. a proxy answering with an HTML page while the server is down
			throw new CytomineAuthException("Unexpected response from the authentication server " + iamUri, false, e);
		}
		lastLoginFailure = null;
		authFailureReported.set(false);
	}

	// Keycloak reports errors as {"error": "invalid_grant", "error_description": "Invalid user credentials"}
	private static String errorDetail(String body) {
		try {
			var json = JsonParser.parseString(body).getAsJsonObject();
			if (json.has("error_description"))
				return json.get("error_description").getAsString();
			if (json.has("error"))
				return json.get("error").getAsString();
		} catch (RuntimeException e) {
			// Not JSON
		}
		return null;
	}

	private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return http.send(request, handler);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Request interrupted", e);
		}
	}
}