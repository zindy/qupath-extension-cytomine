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

import java.nio.charset.StandardCharsets;

/**
 * Prints the tags and properties of the images of a project of a running Cytomine instance, with the raw answers of
 * the server for the first image, to check what the server really returns. Skipped unless the environment variables
 * CYTOMINE_USER and CYTOMINE_PASSWORD are set (and optionally CYTOMINE_URL, default http://127.0.0.1, and
 * CYTOMINE_PROJECT, the name of the project, default qupath-test).
 */
class TestCytomineMetadataLive {

	@Test
	void dumpTagsAndProperties() throws Exception {
		var user = System.getenv("CYTOMINE_USER");
		var password = System.getenv("CYTOMINE_PASSWORD");
		Assumptions.assumeTrue(user != null && password != null, "No Cytomine credentials");

		var web = System.getenv().getOrDefault("CYTOMINE_URL", "http://127.0.0.1");
		var projectName = System.getenv().getOrDefault("CYTOMINE_PROJECT", "qupath-test");
		var endpoints = CytomineUrls.discover(web).orElseThrow();
		var client = CytomineClient.login(endpoints.coreUrl(), endpoints.iamUrl(), user, password.toCharArray());

		var project = CytomineObjects.listProjects(client).stream()
				.filter(p -> p.name().equals(projectName)).findFirst().orElseThrow();
		System.out.println("Project " + project);
		boolean first = true;
		for (var image : CytomineObjects.listImages(client, project.id())) {
			System.out.println("== " + image.id() + " " + image.name() + " class=" + image.className());
			try {
				System.out.println("  tags: " + CytomineMetadata.listTags(client, image));
			} catch (Exception e) {
				System.out.println("  tags FAILED: " + e);
			}
			try {
				System.out.println("  properties: " + CytomineMetadata.listProperties(client, image));
			} catch (Exception e) {
				System.out.println("  properties FAILED: " + e);
			}
			if (first) {
				first = false;
				var cls = image.className() == null ? "be.cytomine.domain.image.ImageInstance" : image.className();
				for (var path : new String[]{
						"domain/" + cls + "/" + image.id() + "/property.json",
						"imageinstance/" + image.id() + "/property.json",
						"domain/" + cls + "/" + image.id() + "/tag_domain_association.json",
						"tag.json"}) {
					try {
						var raw = new String(client.getBytes(path, "application/json"), StandardCharsets.UTF_8);
						System.out.println("  RAW " + path + " -> " + raw);
					} catch (Exception e) {
						System.out.println("  RAW " + path + " -> " + e);
					}
				}
			}
		}
		client.logout();
	}
}
