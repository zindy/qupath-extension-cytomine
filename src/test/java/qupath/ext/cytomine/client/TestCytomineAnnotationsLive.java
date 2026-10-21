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
import qupath.ext.cytomine.annotations.CytomineAnnotationSync;
import qupath.ext.cytomine.server.CytomineImageServer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pull, push, update and delete annotations against a live Cytomine. Skipped unless CYTOMINE_USER and
 * CYTOMINE_PASSWORD are set. Uses the first image of the first project, and deletes what it creates.
 */
class TestCytomineAnnotationsLive {

	@Test
	void roundTrip() throws Exception {
		var user = System.getenv("CYTOMINE_USER");
		var password = System.getenv("CYTOMINE_PASSWORD");
		Assumptions.assumeTrue(user != null && password != null, "No Cytomine credentials");

		var web = System.getenv().getOrDefault("CYTOMINE_URL", "http://127.0.0.1");
		var endpoints = CytomineUrls.discover(web).orElseThrow();
		var client = CytomineClient.login(endpoints.coreUrl(), endpoints.iamUrl(), user, password.toCharArray());
		var project = CytomineObjects.listProjects(client).stream().findFirst().orElse(null);
		Assumptions.assumeTrue(project != null, "No project");
		var image = CytomineObjects.listImages(client, project.id()).stream().filter(i -> i.isRGB()).findFirst().orElse(null);
		Assumptions.assumeTrue(image != null, "No RGB image");

		var uri = CytomineUrls.imageUri(endpoints.coreUrl(), image.id());
		try (var server = new CytomineImageServer(uri, client)) {
			var imageData = new ImageData<BufferedImage>(server);
			var sync = new CytomineAnnotationSync(imageData, client);
			int before = CytomineAnnotations.list(client, image.id()).size();

			var first = sync.pull();
			System.out.println("pull 1: " + first.summary());
			assertEquals(before, first.added);
			assertEquals(before, imageData.getHierarchy().getAnnotationObjects().size());

			// Create locally, with a class that exists in the ontology and one that does not
			var roi = ROIs.createRectangleROI(2000, 3000, 400, 300, ImagePlane.getDefaultPlane());
			var obj = PathObjects.createAnnotationObject(roi, PathClass.fromString("Tumor"));
			var obj2 = PathObjects.createAnnotationObject(
					ROIs.createEllipseROI(3000, 3000, 200, 200, ImagePlane.getDefaultPlane()), PathClass.fromString("QuPath only"));
			imageData.getHierarchy().addObjects(java.util.List.of(obj, obj2));
			var pushed = sync.push(false);
			System.out.println("push 1: " + pushed.summary());
			assertEquals(2, pushed.created);
			assertEquals(before + 2, CytomineAnnotations.list(client, image.id()).size());

			// Nothing changed: nothing to do
			var again = sync.push(false);
			assertEquals(0, again.created + again.pushed + again.deleted);

			// Measurements are sent as properties, only when they change
			try (var list = obj2.getMeasurementList()) {
				list.put("Test measurement", 42.5);
			}
			var withMeasurements = sync.push(false);
			assertEquals(1, withMeasurements.measurementsPushed, withMeasurements.summary());
			var obj2Id = Long.parseLong(obj2.getMetadata().get("Cytomine ID"));
			var properties = CytomineObjects.listAll(client,
					"domain/be.cytomine.domain.ontology.UserAnnotation/" + obj2Id + "/property.json");
			assertEquals(1, properties.size());
			assertEquals("QuPath: Test measurement", properties.get(0).get("key").getAsString());
			assertEquals(0, sync.push(false).measurementsPushed);

			// Deleted in Cytomine, still in QuPath: created again on push, with its measurements
			CytomineAnnotations.delete(client, obj2Id);
			var recreated = sync.push(false);
			assertEquals(1, recreated.created, recreated.summary());
			assertEquals(1, recreated.measurementsPushed);
			assertNotEquals(Long.toString(obj2Id), obj2.getMetadata().get("Cytomine ID"));
			assertEquals(before + 2, CytomineAnnotations.list(client, image.id()).size());

			// Geometry and class change
			var moved = PathObjects.createAnnotationObject(
					ROIs.createRectangleROI(2100, 3100, 400, 300, ImagePlane.getDefaultPlane()), PathClass.fromString("Stroma"));
			moved.getMetadata().putAll(obj.getMetadata());
			imageData.getHierarchy().removeObject(obj, true);
			imageData.getHierarchy().addObject(moved);
			var updated = sync.push(false);
			System.out.println("push 2: " + updated.summary());
			assertEquals(1, updated.pushed);
			var remote = CytomineAnnotations.get(client, Long.parseLong(moved.getMetadata().get("Cytomine ID")));
			assertTrue(remote.wkt().contains("2100"), remote.wkt());
			assertEquals(1, remote.termIds().size());

			// Pull on a second "session": sees the same annotations, nothing to change
			var imageData2 = new ImageData<BufferedImage>(server);
			var sync2 = new CytomineAnnotationSync(imageData2, client);
			var second = sync2.pull();
			assertEquals(before + 2, second.added);
			assertEquals(before + 2, imageData2.getHierarchy().getAnnotationObjects().size());

			// Change in session 1, pull in session 2
			var edited = PathObjects.createAnnotationObject(
					ROIs.createRectangleROI(2200, 3200, 400, 300, ImagePlane.getDefaultPlane()), moved.getPathClass());
			edited.getMetadata().putAll(moved.getMetadata());
			imageData.getHierarchy().removeObject(moved, true);
			imageData.getHierarchy().addObject(edited);
			assertEquals(1, sync.push(false).pushed);
			var refreshed = sync2.pull();
			assertEquals(1, refreshed.updated, refreshed.summary());

			// Conflict: change on both sides
			var edited2 = PathObjects.createAnnotationObject(
					ROIs.createRectangleROI(2300, 3300, 400, 300, ImagePlane.getDefaultPlane()), edited.getPathClass());
			edited2.getMetadata().putAll(edited.getMetadata());
			imageData.getHierarchy().removeObject(edited, true);
			imageData.getHierarchy().addObject(edited2);
			assertEquals(1, sync.push(false).pushed);
			var local2 = imageData2.getHierarchy().getAnnotationObjects().stream()
					.filter(o -> o.getMetadata().get("Cytomine ID").equals(edited2.getMetadata().get("Cytomine ID"))).findFirst().orElseThrow();
			var local2Edited = PathObjects.createAnnotationObject(
					ROIs.createRectangleROI(2400, 3400, 400, 300, ImagePlane.getDefaultPlane()), local2.getPathClass());
			local2Edited.getMetadata().putAll(local2.getMetadata());
			imageData2.getHierarchy().removeObject(local2, true);
			imageData2.getHierarchy().addObject(local2Edited);
			var conflict = sync2.push(false);
			System.out.println("conflict: " + conflict.summary());
			assertEquals(1, conflict.conflicts.size());

			// Clean up: delete everything we created
			imageData.getHierarchy().removeObject(edited2, true);
			imageData.getHierarchy().removeObject(obj2, true);
			assertEquals(2, sync.countDeletedLocally());
			var cleanup = sync.push(true);
			assertEquals(2, cleanup.deleted);
			assertEquals(before, CytomineAnnotations.list(client, image.id()).size());

			// The class that did not exist in the ontology became a term: remove it again
			var ontology = CytomineAnnotations.getOntologyId(client, project.id());
			for (var term : CytomineAnnotations.listTerms(client, ontology)) {
				if (term.name().equals("QuPath only"))
					client.sendJson("DELETE", "term/" + term.id() + ".json", null);
			}
		}
	}
}
