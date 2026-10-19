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

/**
 * Minimal client for the Cytomine core REST API.
 * <p>
 * Authentication is performed against the Keycloak (IAM) server of the Cytomine instance using the OpenID Connect
 * password grant. Only the short-lived tokens are kept in memory; the password is never stored.
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
	 * Create a client and authenticate. The password array is cleared before returning.
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
			client.requestToken(Map.of(
					"grant_type", "password",
					"username", username,
					"password", new String(password)));
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
	 * Requests are not retried, since they may modify data.
	 * @param method HTTP method, e.g. POST, PUT or DELETE
	 * @param path path relative to the core API
	 * @param body the body, or null
	 * @return the response as a JSON object, empty if the response has no body
	 * @throws IOException if the request fails
	 */
	public JsonObject sendJson(String method, String path, JsonElement body) throws IOException {
		var publisher = body == null ? HttpRequest.BodyPublishers.noBody() :
				HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
		var request = HttpRequest.newBuilder(coreUri.resolve(path))
				.timeout(Duration.ofSeconds(60))
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + getAccessToken())
				.method(method, publisher)
				.build();
		var response = send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2)
			throw new IOException("HTTP " + response.statusCode() + " for " + method + " " + request.uri());
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
			var request = HttpRequest.newBuilder(coreUri.resolve(path))
					.timeout(Duration.ofSeconds(60))
					.header("Accept", accept)
					.header("Authorization", "Bearer " + getAccessToken())
					.GET()
					.build();
			try {
				requestPermits.acquire();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Request interrupted", e);
			}
			try {
				var response = send(request, HttpResponse.BodyHandlers.ofByteArray());
				int status = response.statusCode();
				if (status / 100 == 2)
					return response.body();
				lastError = new IOException("HTTP " + status + " for " + request.uri());
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

	private synchronized String getAccessToken() throws IOException {
		var now = Instant.now().plus(TOKEN_EXPIRY_MARGIN);
		if (accessToken != null && now.isBefore(accessExpiry))
			return accessToken;
		if (refreshToken != null && now.isBefore(refreshExpiry)) {
			requestToken(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
			return accessToken;
		}
		throw new IOException("Cytomine session expired - please log in again");
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
		var response = send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			logger.debug("Token request failed with HTTP {}", response.statusCode());
			accessToken = null;
			refreshToken = null;
			throw new IOException(response.statusCode() == 400 || response.statusCode() == 401 ?
					"Invalid username or password" :
					"Authentication failed (HTTP " + response.statusCode() + ")");
		}
		var json = JsonParser.parseString(response.body()).getAsJsonObject();
		var now = Instant.now();
		accessToken = json.get("access_token").getAsString();
		accessExpiry = now.plusSeconds(json.get("expires_in").getAsLong());
		refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
		refreshExpiry = json.has("refresh_expires_in") ?
				now.plusSeconds(json.get("refresh_expires_in").getAsLong()) : accessExpiry;
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
