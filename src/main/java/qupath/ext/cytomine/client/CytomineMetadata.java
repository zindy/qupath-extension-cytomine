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
import qupath.ext.cytomine.client.CytomineObjects.ImageInstance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read the tags and the properties (key/value pairs) that users attach to the images of a Cytomine project.
 * <p>
 * These are addressed through the class name of the image, as the official Cytomine clients do. The class name is
 * read from the image itself; the usual name is used if the server did not give one. For properties, the shorter
 * {@code imageinstance/<id>/property.json} is tried as well if the first form is not found.
 */
public final class CytomineMetadata {

	private static final String DEFAULT_IMAGE_CLASS = "be.cytomine.domain.image.ImageInstance";

	// "Not found" style answers, as opposed to errors of the server
	private static final Set<Integer> NOT_AVAILABLE = Set.of(400, 404, 405);

	private CytomineMetadata() {}

	/**
	 * A property of a Cytomine object.
	 * @param key the key
	 * @param value the value, never null
	 */
	public record Property(String key, String value) {}

	@FunctionalInterface
	interface TagNames {
		Map<Long, String> get() throws IOException;
	}

	/**
	 * List the properties of an image.
	 * @param client the client
	 * @param image the image
	 * @return the properties, in the order of the server
	 * @throws IOException if the request fails
	 */
	public static List<Property> listProperties(CytomineClient client, ImageInstance image) throws IOException {
		return parseProperties(listFirstAvailable(client,
				"domain/" + domainClass(image) + "/" + image.id() + "/property.json",
				"imageinstance/" + image.id() + "/property.json"));
	}

	/**
	 * List the names of the tags of an image.
	 * @param client the client
	 * @param image the image
	 * @return the tag names, without duplicates
	 * @throws IOException if the request fails
	 */
	public static List<String> listTags(CytomineClient client, ImageInstance image) throws IOException {
		var associations = CytomineObjects.listAll(client,
				"domain/" + domainClass(image) + "/" + image.id() + "/tag_domain_association.json");
		return parseTags(associations, () -> allTagNames(client));
	}

	static List<Property> parseProperties(List<JsonObject> items) {
		var properties = new ArrayList<Property>();
		for (var item : items) {
			var key = string(item, "key");
			if (key == null || key.isBlank())
				continue;
			var value = string(item, "value");
			properties.add(new Property(key, value == null ? "" : value));
		}
		return properties;
	}

	// The name is given with the association or inside the tag; failing that it is looked up from the id of the tag
	static List<String> parseTags(List<JsonObject> associations, TagNames allTagNames) throws IOException {
		var names = new LinkedHashSet<String>();
		Map<Long, String> lookup = null;
		for (var association : associations) {
			var name = string(association, "tagName");
			var tag = association.get("tag");
			if (name == null && tag != null && tag.isJsonObject()) {
				name = string(tag.getAsJsonObject(), "name");
			} else if (name == null && tag != null && tag.isJsonPrimitive()) {
				try {
					var id = tag.getAsLong();
					if (lookup == null)
						lookup = allTagNames.get();
					name = lookup.get(id);
				} catch (NumberFormatException e) {
					// Not an id
				}
			}
			if (name != null && !name.isBlank())
				names.add(name.strip());
		}
		return new ArrayList<>(names);
	}

	private static Map<Long, String> allTagNames(CytomineClient client) throws IOException {
		var names = new TreeMap<Long, String>();
		for (var tag : CytomineObjects.listAll(client, "tag.json")) {
			var name = string(tag, "name");
			if (name != null && tag.has("id") && tag.get("id").isJsonPrimitive())
				names.put(tag.get("id").getAsLong(), name);
		}
		return names;
	}

	private static List<JsonObject> listFirstAvailable(CytomineClient client, String... paths) throws IOException {
		CytomineHttpException last = null;
		for (var path : paths) {
			try {
				return CytomineObjects.listAll(client, path);
			} catch (CytomineHttpException e) {
				if (!NOT_AVAILABLE.contains(e.getStatusCode()))
					throw e;
				last = e;
			}
		}
		throw last;
	}

	private static String domainClass(ImageInstance image) {
		var className = image.className();
		return className == null || className.isBlank() ? DEFAULT_IMAGE_CLASS : className;
	}

	private static String string(JsonObject json, String key) {
		var e = json.get(key);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
	}
}
