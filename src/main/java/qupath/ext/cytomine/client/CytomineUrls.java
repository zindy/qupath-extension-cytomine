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
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Helpers to build and parse the URLs used by the extension.
 * <p>
 * Images are identified by the URL of their API resource, e.g. {@code http://127.0.0.1:8080/api/imageinstance/42}.
 */
public final class CytomineUrls {

	private static final Pattern IMAGE_PATH = Pattern.compile("^(.*/api)/imageinstance/(\\d+)(\\.json)?/?$");

	private CytomineUrls() {}

	/**
	 * Normalize a core API URL so that it ends with {@code /api/}.
	 * @param url the URL provided by the user
	 * @return the normalized URL
	 */
	public static String normalizeCoreUrl(String url) {
		var s = url.strip().replaceAll("/+$", "");
		if (!s.endsWith("/api"))
			s += "/api";
		return s + "/";
	}

	/**
	 * Normalize an IAM realm URL by removing any trailing slash.
	 * @param url the URL provided by the user
	 * @return the normalized URL
	 */
	public static String normalizeIamUrl(String url) {
		return url.strip().replaceAll("/+$", "");
	}

	/**
	 * Create the URI representing an image instance.
	 * @param coreUrl URL of the core API
	 * @param imageId id of the image instance
	 * @return the image URI
	 */
	public static URI imageUri(String coreUrl, long imageId) {
		return URI.create(normalizeCoreUrl(coreUrl) + "imageinstance/" + imageId);
	}

	/**
	 * Check whether a URI points to a Cytomine image instance.
	 * @param uri the URI to check
	 * @return true if the URI has the expected structure
	 */
	public static boolean isImageUri(URI uri) {
		return uri != null && uri.getPath() != null && IMAGE_PATH.matcher(uri.getPath()).matches();
	}

	/**
	 * Get the core API URL from an image URI.
	 * @param uri an image URI
	 * @return the core API URL, ending with a slash
	 */
	public static String coreUrl(URI uri) {
		var m = IMAGE_PATH.matcher(uri.getPath());
		if (!m.matches())
			throw new IllegalArgumentException("Not a Cytomine image URI: " + uri);
		return uri.getScheme() + "://" + uri.getRawAuthority() + m.group(1) + "/";
	}

	/**
	 * Get the image instance id from an image URI.
	 * @param uri an image URI
	 * @return the image instance id
	 */
	public static long imageId(URI uri) {
		var m = IMAGE_PATH.matcher(uri.getPath());
		if (!m.matches())
			throw new IllegalArgumentException("Not a Cytomine image URI: " + uri);
		return Long.parseLong(m.group(2));
	}

	/**
	 * Try to discover the core and IAM URLs from the {@code configuration.json} served by the Cytomine web interface.
	 * @param webUrl the URL of the web interface, e.g. {@code http://127.0.0.1}
	 * @return the discovered URLs, or empty if the URL does not point to a Cytomine web interface
	 */
	public static Optional<Endpoints> discover(String webUrl) {
		try {
			var base = webUrl.strip().replaceAll("/+$", "");
			var request = HttpRequest.newBuilder(URI.create(base + "/configuration.json"))
					.timeout(Duration.ofSeconds(10))
					.GET().build();
			var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2)
				return Optional.empty();
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			if (!json.has("CORE_URL") || !json.has("IAM_URL"))
				return Optional.empty();
			return Optional.of(new Endpoints(
					normalizeCoreUrl(json.get("CORE_URL").getAsString()),
					normalizeIamUrl(json.get("IAM_URL").getAsString())));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	/**
	 * The endpoints required to talk to a Cytomine instance.
	 * @param coreUrl URL of the core API
	 * @param iamUrl URL of the IAM realm
	 */
	public record Endpoints(String coreUrl, String iamUrl) {}
}
