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

package qupath.ext.cytomine.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cytomine.client.CytomineAuthException;
import qupath.ext.cytomine.client.CytomineClients;
import qupath.ext.cytomine.client.CytomineUrls;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;

/**
 * Builds {@link CytomineImageServer} instances for URIs such as
 * {@code http://127.0.0.1:8080/api/imageinstance/42.json}.
 * The user is prompted to log in if there is no session yet for the server.
 */
public class CytomineImageServerBuilder implements ImageServerBuilder<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(CytomineImageServerBuilder.class);

	@Override
	public ImageServer<BufferedImage> buildServer(URI uri, String... args) throws Exception {
		if (!CytomineUrls.isImageUri(uri))
			return null;
		var client = CytomineClients.getOrLogin(CytomineUrls.coreUrl(uri))
				.orElseThrow(() -> new IOException("Not logged in to " + uri.getAuthority()));
		return new CytomineImageServer(uri, client, args);
	}

	@Override
	public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) throws IOException {
		if (!CytomineUrls.isImageUri(uri))
			return UriImageSupport.createInstance(this.getClass(), 0f, java.util.List.of());
		try {
			var server = buildServer(uri, args);
			if (server != null)
				return UriImageSupport.createInstance(this.getClass(), 4f, server.getBuilder());
		} catch (Exception e) {
			logger.warn("Unable to open Cytomine image {}: {}", uri, e.getMessage());
			// Otherwise the image would simply be reported as unsupported
			if (e instanceof CytomineAuthException authError)
				CytomineClients.get(CytomineUrls.coreUrl(uri)).ifPresent(c -> CytomineClients.reportAuthFailure(c, authError));
		}
		return UriImageSupport.createInstance(this.getClass(), 0f, java.util.List.of());
	}

	@Override
	public String getName() {
		return "Cytomine";
	}

	@Override
	public String getDescription() {
		return "Image server for Cytomine";
	}

	@Override
	public Class<BufferedImage> getImageType() {
		return BufferedImage.class;
	}
}