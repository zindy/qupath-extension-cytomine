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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Access to the user annotations, terms and ontologies of Cytomine.
 * <p>
 * Geometries are exchanged as WKT, in image pixels with the origin at the <b>bottom</b> left of the image.
 */
public final class CytomineAnnotations {

	private CytomineAnnotations() {}

	/**
	 * A user annotation.
	 * @param id annotation id
	 * @param userId id of the user who owns the annotation
	 * @param wkt geometry, in Cytomine coordinates
	 * @param termIds ids of the terms of the annotation (for the owner)
	 * @param updated last update, in milliseconds, or the creation date if never updated
	 * @param json the full JSON of the annotation as returned by the server
	 */
	public record RemoteAnnotation(long id, long userId, String wkt, List<Long> termIds, long updated, JsonObject json) {

		static RemoteAnnotation fromJson(JsonObject json) {
			var terms = new ArrayList<Long>();
			var term = json.get("term");
			if (term != null && term.isJsonArray())
				term.getAsJsonArray().forEach(e -> terms.add(e.getAsLong()));
			else if (term != null && !term.isJsonNull())
				terms.add(term.getAsLong());
			var updated = json.get("updated");
			var created = json.get("created");
			long time = updated != null && !updated.isJsonNull() ? updated.getAsLong() :
					created != null && !created.isJsonNull() ? created.getAsLong() : 0L;
			return new RemoteAnnotation(
					json.get("id").getAsLong(),
					json.get("user").getAsLong(),
					json.get("location").getAsString(),
					terms, time, json);
		}
	}

	/**
	 * A term of an ontology.
	 * @param id term id
	 * @param name term name
	 * @param color color as a packed RGB integer, or null
	 */
	public record Term(long id, String name, Integer color) {}

	/**
	 * List the user annotations of an image, for all users.
	 * @param client the client
	 * @param imageId image instance id
	 * @return the annotations
	 * @throws IOException if a request fails
	 */
	public static List<RemoteAnnotation> list(CytomineClient client, long imageId) throws IOException {
		var annotations = new ArrayList<RemoteAnnotation>();
		var path = "annotation.json?image=" + imageId + "&showBasic=true&showMeta=true&showWKT=true&showTerm=true";
		for (var json : CytomineObjects.listAll(client, path)) {
			var cls = json.get("class");
			// Other kinds of annotations (from analyses, or reviewed) are not editable
			if (cls != null && !cls.getAsString().endsWith("UserAnnotation"))
				continue;
			if (!json.has("location") || !json.has("user"))
				continue;
			annotations.add(RemoteAnnotation.fromJson(json));
		}
		return annotations;
	}

	/**
	 * Get the current version of an annotation.
	 * @param client the client
	 * @param id annotation id
	 * @return the annotation
	 * @throws IOException if the request fails (e.g. it was deleted)
	 */
	public static RemoteAnnotation get(CytomineClient client, long id) throws IOException {
		return RemoteAnnotation.fromJson(client.getJsonObject("userannotation/" + id + ".json"));
	}

	/**
	 * Create an annotation.
	 * @param client the client
	 * @param imageId image instance id
	 * @param projectId project id
	 * @param wkt geometry in Cytomine coordinates
	 * @param termId term id, or null
	 * @return the new annotation
	 * @throws IOException if the request fails
	 */
	public static RemoteAnnotation create(CytomineClient client, long imageId, long projectId, String wkt, Long termId)
			throws IOException {
		var body = new JsonObject();
		body.addProperty("location", wkt);
		body.addProperty("image", imageId);
		body.addProperty("project", projectId);
		if (termId != null) {
			var terms = new JsonArray();
			terms.add(termId);
			body.add("term", terms);
		}
		var response = client.sendJson("POST", "userannotation.json", body);
		return RemoteAnnotation.fromJson(response.getAsJsonObject("annotation"));
	}

	/**
	 * Change the geometry of an annotation. The server expects the complete object, so the given (up to date) annotation
	 * is sent back with its location replaced.
	 * @param client the client
	 * @param current the current version of the annotation
	 * @param wkt the new geometry
	 * @return the updated annotation
	 * @throws IOException if the request fails
	 */
	public static RemoteAnnotation updateLocation(CytomineClient client, RemoteAnnotation current, String wkt) throws IOException {
		var body = current.json().deepCopy();
		body.addProperty("location", wkt);
		var response = client.sendJson("PUT", "userannotation/" + current.id() + ".json", body);
		return RemoteAnnotation.fromJson(response.getAsJsonObject("annotation"));
	}

	/**
	 * Set the only term of an annotation.
	 * @param client the client
	 * @param annotation the annotation
	 * @param termId the term id, or null to remove all terms
	 * @throws IOException if a request fails
	 */
	public static void setTerm(CytomineClient client, RemoteAnnotation annotation, Long termId) throws IOException {
		if (termId == null) {
			for (var existing : annotation.termIds())
				client.sendJson("DELETE", "annotation/" + annotation.id() + "/term/" + existing + ".json", null);
			return;
		}
		var body = new JsonObject();
		body.addProperty("userannotation", annotation.id());
		body.addProperty("term", termId);
		client.sendJson("POST", "annotation/" + annotation.id() + "/term/" + termId + "/clearBefore.json", body);
	}

	/**
	 * Delete an annotation.
	 * @param client the client
	 * @param id annotation id
	 * @throws IOException if the request fails
	 */
	public static void delete(CytomineClient client, long id) throws IOException {
		client.sendJson("DELETE", "userannotation/" + id + ".json", null);
	}

	/**
	 * Get the id of the ontology of a project.
	 * @param client the client
	 * @param projectId project id
	 * @return the ontology id, or null if the project has none
	 * @throws IOException if the request fails
	 */
	public static Long getOntologyId(CytomineClient client, long projectId) throws IOException {
		JsonElement ontology = client.getJsonObject("project/" + projectId + ".json").get("ontology");
		return ontology == null || ontology.isJsonNull() ? null : ontology.getAsLong();
	}

	/**
	 * List the terms of an ontology.
	 * @param client the client
	 * @param ontologyId ontology id
	 * @return the terms
	 * @throws IOException if a request fails
	 */
	public static List<Term> listTerms(CytomineClient client, long ontologyId) throws IOException {
		var terms = new ArrayList<Term>();
		for (var json : CytomineObjects.listAll(client, "ontology/" + ontologyId + "/term.json")) {
			var color = json.get("color");
			terms.add(new Term(json.get("id").getAsLong(), json.get("name").getAsString(),
					color == null || color.isJsonNull() ? null : CytomineObjects.parseColor(color.getAsString())));
		}
		return terms;
	}

	/**
	 * Create a term.
	 * @param client the client
	 * @param ontologyId ontology id
	 * @param name term name
	 * @param color color as a packed RGB integer, or null
	 * @return the new term
	 * @throws IOException if the request fails
	 */
	public static Term createTerm(CytomineClient client, long ontologyId, String name, Integer color) throws IOException {
		var body = new JsonObject();
		body.addProperty("name", name);
		body.addProperty("ontology", ontologyId);
		var hex = String.format("#%06x", color == null ? 0x808080 : color & 0xffffff);
		body.addProperty("color", hex);
		var data = client.sendJson("POST", "term.json", body).getAsJsonObject("data");
		return new Term(data.get("id").getAsLong(), name, CytomineObjects.parseColor(hex));
	}

	/**
	 * Get the names of the users of a project.
	 * @param client the client
	 * @param projectId project id
	 * @return the user names by id
	 * @throws IOException if a request fails
	 */
	public static Map<Long, String> listUsers(CytomineClient client, long projectId) throws IOException {
		var users = new HashMap<Long, String>();
		for (var json : CytomineObjects.listAll(client, "project/" + projectId + "/user.json"))
			users.put(json.get("id").getAsLong(), json.get("username").getAsString());
		return users;
	}
}
