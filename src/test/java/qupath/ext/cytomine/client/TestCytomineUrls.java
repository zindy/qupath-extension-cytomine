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

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class TestCytomineUrls {

	@Test
	void normalizeCoreUrl() {
		assertEquals("http://127.0.0.1:8080/api/", CytomineUrls.normalizeCoreUrl("http://127.0.0.1:8080"));
		assertEquals("http://127.0.0.1:8080/api/", CytomineUrls.normalizeCoreUrl(" http://127.0.0.1:8080/api/ "));
	}

	@Test
	void imageUriRoundTrip() {
		var uri = CytomineUrls.imageUri("http://host:8080/api", 42);
		assertEquals(URI.create("http://host:8080/api/imageinstance/42"), uri);
		assertTrue(CytomineUrls.isImageUri(uri));
		assertEquals(42, CytomineUrls.imageId(uri));
		assertEquals("http://host:8080/api/", CytomineUrls.coreUrl(uri));
	}

	@Test
	void rejectsOtherUris() {
		assertFalse(CytomineUrls.isImageUri(URI.create("http://host/api/project/1.json")));
		assertFalse(CytomineUrls.isImageUri(URI.create("file:///c:/image.svs")));
	}
}
