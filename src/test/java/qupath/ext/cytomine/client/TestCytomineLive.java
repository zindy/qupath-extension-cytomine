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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import qupath.ext.cytomine.server.CytomineImageServer;
import qupath.lib.regions.RegionRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test against a running Cytomine instance. Skipped unless the environment variables
 * CYTOMINE_USER and CYTOMINE_PASSWORD are set (and optionally CYTOMINE_URL, default http://127.0.0.1).
 * Reads an overview and a full-resolution region from every image of every project and prints the mean of each band.
 */
class TestCytomineLive {

	@Test
	void browseAndReadTiles() throws Exception {
		var user = System.getenv("CYTOMINE_USER");
		var password = System.getenv("CYTOMINE_PASSWORD");
		Assumptions.assumeTrue(user != null && password != null, "No Cytomine credentials");

		var web = System.getenv().getOrDefault("CYTOMINE_URL", "http://127.0.0.1");
		var endpoints = CytomineUrls.discover(web).orElseThrow();
		var client = CytomineClient.login(endpoints.coreUrl(), endpoints.iamUrl(), user, password.toCharArray());

		var projects = CytomineObjects.listProjects(client);
		System.out.println("Projects: " + projects);
		var only = System.getenv("CYTOMINE_IMAGE_ID");
		for (var project : projects) {
			for (var image : CytomineObjects.listImages(client, project.id())) {
				if (only != null && Long.parseLong(only) != image.id())
					continue;
				var uri = CytomineUrls.imageUri(endpoints.coreUrl(), image.id());
				try (var server = new CytomineImageServer(uri, client)) {
					System.out.println("== " + image.id() + " " + server.getMetadata().getName() + " rgb=" + server.isRGB() +
							" channels=" + server.nChannels() + " z=" + server.nZSlices() + " t=" + server.nTimepoints());
					System.out.println("  ranges " + java.util.Arrays.toString(server.getIntensityRanges()));
					double downsample = server.getDownsampleForResolution(server.nResolutions() - 1);
					var overview = server.readRegion(RegionRequest.createInstance(
							server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight()));
					assertNotNull(overview);
					System.out.println("  overview " + overview.getWidth() + "x" + overview.getHeight() +
							" means " + means(overview));
					var full = server.readRegion(RegionRequest.createInstance(
							server.getPath(), 1.0, server.getWidth() / 2, server.getHeight() / 2, 512, 512));
					assertNotNull(full);
					assertEquals(512, full.getWidth());
					System.out.println("  full-res means " + means(full));
				}
			}
		}
	}

	private static String means(java.awt.image.BufferedImage img) {
		var raster = img.getRaster();
		var sb = new StringBuilder();
		int bands = raster.getNumBands();
		if (bands == 4 && img.getType() == java.awt.image.BufferedImage.TYPE_INT_ARGB || img.getType() == java.awt.image.BufferedImage.TYPE_INT_RGB) {
			long r = 0, g = 0, b = 0;
			for (int y = 0; y < img.getHeight(); y++)
				for (int x = 0; x < img.getWidth(); x++) {
					int rgb = img.getRGB(x, y);
					r += (rgb >> 16) & 0xff;
					g += (rgb >> 8) & 0xff;
					b += rgb & 0xff;
				}
			double n = (double) img.getWidth() * img.getHeight();
			return String.format("R=%.1f G=%.1f B=%.1f", r / n, g / n, b / n);
		}
		for (int c = 0; c < bands; c++) {
			long sum = 0;
			for (int y = 0; y < img.getHeight(); y++)
				for (int x = 0; x < img.getWidth(); x++)
					sum += raster.getSample(x, y, c);
			sb.append(String.format("c%d=%.1f ", c, sum / ((double) img.getWidth() * img.getHeight())));
		}
		return sb.toString().trim();
	}
}
