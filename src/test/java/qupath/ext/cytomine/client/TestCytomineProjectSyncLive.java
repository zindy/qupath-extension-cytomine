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
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.cytomine.project.CytomineProjectSync;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Creates a QuPath project from a live Cytomine project, then synchronizes it again.
 * Skipped unless CYTOMINE_USER and CYTOMINE_PASSWORD are set.
 */
class TestCytomineProjectSyncLive {

	@Test
	void createAndSync(@TempDir Path dir) throws Exception {
		var user = System.getenv("CYTOMINE_USER");
		var password = System.getenv("CYTOMINE_PASSWORD");
		Assumptions.assumeTrue(user != null && password != null, "No Cytomine credentials");

		var web = System.getenv().getOrDefault("CYTOMINE_URL", "http://127.0.0.1");
		var endpoints = CytomineUrls.discover(web).orElseThrow();
		var client = CytomineClient.login(endpoints.coreUrl(), endpoints.iamUrl(), user, password.toCharArray());
		var cytomineProject = CytomineObjects.listProjects(client).stream().findFirst().orElse(null);
		Assumptions.assumeTrue(cytomineProject != null, "No Cytomine project");
		int nImages = CytomineObjects.listImages(client, cytomineProject.id()).size();

		var link = new CytomineProjectSync.Link(endpoints.coreUrl(), cytomineProject.id(), cytomineProject.name());
		var project = CytomineProjectSync.createProject(dir.resolve("qp").toFile());
		var first = CytomineProjectSync.sync(project, client, link, false);
		assertEquals(nImages, first.added().size());
		assertEquals(nImages, project.getImageList().size());
		assertEquals(link, CytomineProjectSync.getLink(project).orElseThrow());

		var second = CytomineProjectSync.sync(project, client, link, false);
		assertTrue(second.added().isEmpty());
		assertEquals(nImages, project.getImageList().size());
		assertTrue(CytomineProjectSync.findMissing(project, client, link).isEmpty());
		System.out.println("Synced " + project.getImageList().stream().map(e -> e.getImageName()).toList());
	}
}
