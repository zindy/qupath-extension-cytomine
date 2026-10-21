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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.cytomine.client.CytomineObjects.ImageInstance;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the tags and properties of an image, against a fake Cytomine server.
 */
class TestCytomineMetadata {

	private static final String CLASS = "be.cytomine.domain.image.ImageInstance";

	private HttpServer server;
	private CytomineClient client;
	// Path (after /api/) -> HTTP status and body
	private final ConcurrentHashMap<String, Object[]> answers = new ConcurrentHashMap<>();

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/iam/realms/cytomine/protocol/openid-connect/token", ex -> {
			ex.getRequestBody().readAllBytes();
			respond(ex, 200, "{\"access_token\":\"a\",\"expires_in\":300,\"refresh_token\":\"r\",\"refresh_expires_in\":300}");
		});
		server.createContext("/api/", ex -> {
			ex.getRequestBody().readAllBytes();
			var path = ex.getRequestURI().getPath().substring("/api/".length());
			var answer = answers.get(path);
			if (answer == null)
				respond(ex, 404, "{\"status\":404}");
			else
				respond(ex, (Integer) answer[0], (String) answer[1]);
		});
		server.setExecutor(Executors.newFixedThreadPool(4));
		server.start();
		var base = "http://127.0.0.1:" + server.getAddress().getPort();
		try {
			client = CytomineClient.login(base + "/api", base + "/iam/realms/cytomine", "user", "pw".toCharArray());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	@AfterEach
	void stop() {
		server.stop(0);
		((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
	}

	private static ImageInstance image(String className) {
		return new ImageInstance(7, 1, "img", 100, 100, null, null, null, 3, 1, 1, 1, 8, className);
	}

	private void answer(String path, int status, String body) {
		answers.put(path, new Object[]{status, body});
	}

	private static String collection(String... items) {
		return "{\"collection\":[" + String.join(",", items) + "],\"size\":" + items.length + "}";
	}

	@Test
	void propertiesAreReadFromTheDomainEndpoint() throws Exception {
		answer("domain/" + CLASS + "/7/property.json", 200, collection(
				"{\"id\":1,\"key\":\"Stain\",\"value\":\"H&E\"}",
				"{\"id\":2,\"key\":\"Block\",\"value\":null}",
				"{\"id\":3,\"value\":\"no key\"}"));
		assertEquals(List.of(new CytomineMetadata.Property("Stain", "H&E"), new CytomineMetadata.Property("Block", "")),
				CytomineMetadata.listProperties(client, image(CLASS)));
	}

	@Test
	void propertiesFallBackToTheImageInstanceEndpoint() throws Exception {
		// Not found under the domain form, found under the short form
		answer("imageinstance/7/property.json", 200, collection("{\"key\":\"Stain\",\"value\":\"IHC\"}"));
		assertEquals(List.of(new CytomineMetadata.Property("Stain", "IHC")),
				CytomineMetadata.listProperties(client, image(CLASS)));
	}

	@Test
	void propertiesFailWhenNoEndpointExists() {
		var e = assertThrows(CytomineHttpException.class, () -> CytomineMetadata.listProperties(client, image(CLASS)));
		assertEquals(404, e.getStatusCode());
	}

	@Test
	void usualClassNameIsUsedWhenTheServerGaveNone() throws Exception {
		answer("domain/" + CLASS + "/7/property.json", 200, collection("{\"key\":\"k\",\"value\":\"v\"}"));
		assertEquals(1, CytomineMetadata.listProperties(client, image(null)).size());
	}

	@Test
	void tagNamesAreFoundWhicheverWayTheServerGivesThem() throws Exception {
		answer("domain/" + CLASS + "/7/tag_domain_association.json", 200, collection(
				"{\"id\":1,\"tag\":1,\"tagName\":\"tumor\"}",
				"{\"id\":2,\"tag\":{\"id\":2,\"name\":\"control\"}}",
				"{\"id\":3,\"tag\":3}",
				"{\"id\":4,\"tag\":1,\"tagName\":\"tumor\"}"));
		answer("tag.json", 200, collection("{\"id\":3,\"name\":\"batch 2\"}", "{\"id\":5,\"name\":\"unused\"}"));
		assertEquals(List.of("tumor", "control", "batch 2"), CytomineMetadata.listTags(client, image(CLASS)));
	}

	@Test
	void noTagIsNotAnError() throws Exception {
		answer("domain/" + CLASS + "/7/tag_domain_association.json", 200, collection());
		assertTrue(CytomineMetadata.listTags(client, image(CLASS)).isEmpty());
	}

	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		var bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (var out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}
}
