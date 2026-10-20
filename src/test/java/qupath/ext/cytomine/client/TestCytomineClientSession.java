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

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks how the client renews its session, against a fake Cytomine core API and Keycloak server.
 */
class TestCytomineClientSession {

	private static final String USER = "alice";

	private HttpServer server;
	private String coreUrl;
	private String iamUrl;

	private final Set<String> validAccessTokens = ConcurrentHashMap.newKeySet();
	private final Set<String> validRefreshTokens = ConcurrentHashMap.newKeySet();
	private final AtomicInteger tokenCounter = new AtomicInteger();
	private final AtomicInteger passwordGrants = new AtomicInteger();
	private final AtomicInteger refreshGrants = new AtomicInteger();
	private final AtomicInteger processedPosts = new AtomicInteger();

	private volatile String serverPassword = "secret";
	private volatile int tokenEndpointStatus = 200;
	private volatile int expiresIn = 300;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/iam/realms/cytomine/protocol/openid-connect/token", this::handleToken);
		server.createContext("/api/", this::handleApi);
		server.setExecutor(Executors.newFixedThreadPool(8));
		server.start();
		var base = "http://127.0.0.1:" + server.getAddress().getPort();
		coreUrl = base + "/api";
		iamUrl = base + "/iam/realms/cytomine";
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
		((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
	}

	/** What happens when the Keycloak session is closed by an administrator, or Keycloak is restarted. */
	private void revokeAllTokens() {
		validAccessTokens.clear();
		validRefreshTokens.clear();
	}

	private CytomineClient login() throws IOException {
		return CytomineClient.login(coreUrl, iamUrl, USER, serverPassword.toCharArray());
	}

	@Test
	void passwordIsKeptInMemoryButClearedFromCallerArray() throws Exception {
		var password = serverPassword.toCharArray();
		var client = CytomineClient.login(coreUrl, iamUrl, USER, password);
		assertArrayEquals(new char[password.length], password);
		assertFalse(client.needsLogin());
	}

	@Test
	void sessionIsRenewedWhenServerClosedIt() throws Exception {
		var client = login();
		assertTrue(client.getJsonObject("ping.json").get("ok").getAsBoolean());
		revokeAllTokens();
		// Refresh token refused, so the password is used: no error for the caller
		assertTrue(client.getJsonObject("ping.json").get("ok").getAsBoolean());
		assertEquals(2, passwordGrants.get());
		assertEquals(1, refreshGrants.get());
	}

	@Test
	void sessionIsRenewedWhenTokensExpire() throws Exception {
		// Shorter than the safety margin of the client: always considered expired, and so is the refresh token
		expiresIn = 10;
		var client = login();
		assertNotNull(client.getBytes("ping.json", "application/json"));
		assertEquals(2, passwordGrants.get());
		assertEquals(0, refreshGrants.get());
	}

	@Test
	void refreshTokenIsPreferredToPassword() throws Exception {
		var client = login();
		// Only the access token is gone
		validAccessTokens.clear();
		assertNotNull(client.getBytes("ping.json", "application/json"));
		assertEquals(1, passwordGrants.get());
		assertEquals(1, refreshGrants.get());
	}

	@Test
	void modifyingRequestIsReplayedOnlyOnceAfterRenewal() throws Exception {
		var client = login();
		revokeAllTokens();
		client.sendJson("POST", "items.json", new JsonObject());
		// The first attempt was refused before being processed
		assertEquals(1, processedPosts.get());
	}

	@Test
	void concurrentRequestsShareOneRenewal() throws Exception {
		var client = login();
		revokeAllTokens();
		var errors = new ArrayList<Throwable>();
		var threads = new ArrayList<Thread>();
		for (int i = 0; i < 8; i++) {
			var t = new Thread(() -> {
				try {
					client.getBytes("ping.json", "application/json");
				} catch (Throwable e) {
					synchronized (errors) {
						errors.add(e);
					}
				}
			});
			threads.add(t);
			t.start();
		}
		for (var t : threads)
			t.join();
		assertTrue(errors.isEmpty(), errors.toString());
		assertEquals(2, passwordGrants.get());
	}

	@Test
	void refusedPasswordIsReportedAndNotRetried() throws Exception {
		var client = login();
		serverPassword = "changed";
		revokeAllTokens();

		var e = assertThrows(CytomineAuthException.class, () -> client.getBytes("ping.json", "application/json"));
		assertTrue(e.isCredentialsRejected());
		assertTrue(e.getMessage().contains("Invalid username or password"), e.getMessage());
		assertTrue(e.getMessage().contains("log in again"), e.getMessage());
		// The login, and a single attempt to renew it: a wrong password must not be tried repeatedly
		assertEquals(2, passwordGrants.get());
		assertTrue(client.needsLogin());

		// Nothing more is sent to the server
		var again = assertThrows(CytomineAuthException.class, () -> client.getJsonObject("ping.json"));
		assertTrue(again.isCredentialsRejected());
		assertEquals(2, passwordGrants.get());
	}

	@Test
	void serverErrorIsRetriedThenReportedWithItsStatus() throws Exception {
		var client = login();
		revokeAllTokens();
		tokenEndpointStatus = 503;

		var e = assertThrows(CytomineAuthException.class, () -> client.getBytes("ping.json", "application/json"));
		assertFalse(e.isCredentialsRejected());
		assertTrue(e.getMessage().contains("503"), e.getMessage());
		assertTrue(e.getMessage().contains("3 attempts"), e.getMessage());
		assertEquals(1 + 3, passwordGrants.get());
		assertFalse(client.needsLogin(), "The password is still good, only the server is not");

		// Pending requests fail immediately for a while rather than each retrying
		var again = assertThrows(CytomineAuthException.class, () -> client.getBytes("ping.json", "application/json"));
		assertTrue(again.getMessage().contains("503"), again.getMessage());
		assertEquals(1 + 3, passwordGrants.get());
	}

	@Test
	void initialLoginReportsRefusalAndServerErrors() {
		var wrong = assertThrows(CytomineAuthException.class,
				() -> CytomineClient.login(coreUrl, iamUrl, USER, "nope".toCharArray()));
		assertEquals("Invalid username or password", wrong.getMessage());
		assertTrue(wrong.isCredentialsRejected());

		tokenEndpointStatus = 502;
		var down = assertThrows(CytomineAuthException.class,
				() -> CytomineClient.login(coreUrl, iamUrl, USER, serverPassword.toCharArray()));
		assertTrue(down.getMessage().contains("502"), down.getMessage());
		assertFalse(down.isCredentialsRejected());
	}

	@Test
	void unreachableAuthenticationServerIsReported() {
		server.stop(0);
		var e = assertThrows(CytomineAuthException.class,
				() -> CytomineClient.login(coreUrl, iamUrl, USER, serverPassword.toCharArray()));
		assertTrue(e.getMessage().contains("Unable to reach the authentication server"), e.getMessage());
	}

	@Test
	void logoutForgetsThePassword() throws Exception {
		var client = login();
		client.logout();
		assertTrue(client.needsLogin());
		assertThrows(CytomineAuthException.class, () -> client.getJsonObject("ping.json"));
		assertEquals(1, passwordGrants.get());
	}

	// ---- Fake servers

	private void handleToken(HttpExchange ex) throws IOException {
		var form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		var grant = form.get("grant_type");
		if ("password".equals(grant))
			passwordGrants.incrementAndGet();
		else if ("refresh_token".equals(grant))
			refreshGrants.incrementAndGet();

		if (tokenEndpointStatus != 200) {
			respond(ex, tokenEndpointStatus, "text/plain", "Service unavailable");
			return;
		}
		if ("password".equals(grant)) {
			if (!USER.equals(form.get("username")) || !serverPassword.equals(form.get("password"))) {
				respond(ex, 401, "application/json",
						"{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");
				return;
			}
		} else if (!validRefreshTokens.remove(form.get("refresh_token"))) {
			respond(ex, 400, "application/json",
					"{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}");
			return;
		}
		int n = tokenCounter.incrementAndGet();
		var access = "access-" + n;
		var refresh = "refresh-" + n;
		validAccessTokens.add(access);
		validRefreshTokens.add(refresh);
		respond(ex, 200, "application/json", "{\"access_token\":\"" + access + "\",\"expires_in\":" + expiresIn +
				",\"refresh_token\":\"" + refresh + "\",\"refresh_expires_in\":" + expiresIn + "}");
	}

	private void handleApi(HttpExchange ex) throws IOException {
		var auth = ex.getRequestHeaders().getFirst("Authorization");
		ex.getRequestBody().readAllBytes();
		if (auth == null || !auth.startsWith("Bearer ") || !validAccessTokens.contains(auth.substring(7))) {
			respond(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
			return;
		}
		if ("POST".equals(ex.getRequestMethod()))
			processedPosts.incrementAndGet();
		respond(ex, 200, "application/json", "{\"ok\":true}");
	}

	private static void respond(HttpExchange ex, int status, String type, String body) throws IOException {
		var bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", type);
		ex.sendResponseHeaders(status, bytes.length);
		try (var out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static Map<String, String> parseForm(String body) {
		var map = new HashMap<String, String>();
		for (var pair : body.split("&")) {
			var kv = pair.split("=", 2);
			map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
					kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
		}
		return map;
	}
}
