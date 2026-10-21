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

package qupath.ext.cytomine.project;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cytomine.client.CytomineAuthException;
import qupath.ext.cytomine.client.CytomineClient;
import qupath.ext.cytomine.client.CytomineMetadata;
import qupath.ext.cytomine.client.CytomineObjects;
import qupath.ext.cytomine.client.CytomineObjects.ImageInstance;
import qupath.ext.cytomine.client.CytomineUrls;
import qupath.ext.cytomine.server.CytomineImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps a QuPath project in step with a Cytomine project: one QuPath entry per Cytomine image.
 * <p>
 * The link is stored in {@value #LINK_FILE_NAME} in the QuPath project directory. Entries remember the id and name of
 * the Cytomine image in their metadata.
 */
public final class CytomineProjectSync {

	private static final Logger logger = LoggerFactory.getLogger(CytomineProjectSync.class);

	static final String LINK_FILE_NAME = "cytomine-project.json";
	static final String KEY_IMAGE_ID = "Cytomine image ID";
	static final String KEY_IMAGE_NAME = "Cytomine image name";
	// Cytomine properties are copied to the metadata of the entries under this prefix, so that they can be told apart
	// from what the user adds, and updated or removed when they change in Cytomine
	static final String KEY_PROPERTY_PREFIX = "Cytomine: ";
	// The tags last copied from Cytomine (a JSON array), to be able to remove those that are removed in Cytomine
	// without touching the tags added in QuPath
	static final String KEY_PULLED_TAGS = "Cytomine tags";
	private static final int MAX_CONSECUTIVE_METADATA_FAILURES = 3;
	private static final int THUMBNAIL_SIZE = 256;

	private CytomineProjectSync() {}

	/**
	 * Identifies the Cytomine project a QuPath project is linked to.
	 * @param coreUrl core API URL of the server
	 * @param projectId Cytomine project id
	 * @param projectName Cytomine project name, for display
	 */
	public record Link(String coreUrl, long projectId, String projectName) {
		@Override
		public String toString() {
			return projectName + " (#" + projectId + ")";
		}
	}

	/**
	 * Outcome of a synchronization.
	 * @param added names of the images added to the QuPath project
	 * @param renamed number of entries renamed to follow Cytomine
	 * @param missing entries whose Cytomine image no longer exists in the Cytomine project and were kept
	 * @param removed number of entries removed
	 * @param metadataUpdated number of entries whose tags or properties were updated from Cytomine
	 * @param metadataFailed number of images whose tags and properties could not be read (nothing is changed for them)
	 */
	public record Result(List<String> added, int renamed, List<ProjectImageEntry<BufferedImage>> missing, int removed,
						 int metadataUpdated, int metadataFailed) {}

	/**
	 * @param project a QuPath project
	 * @return the link of the project, if it has one
	 */
	public static Optional<Link> getLink(Project<?> project) {
		if (project == null)
			return Optional.empty();
		var file = linkFile(project);
		if (!file.isFile())
			return Optional.empty();
		try {
			var json = new Gson().fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), JsonObject.class);
			return Optional.of(new Link(
					json.get("coreUrl").getAsString(),
					json.get("projectId").getAsLong(),
					json.has("projectName") ? json.get("projectName").getAsString() : ""));
		} catch (IOException | RuntimeException e) {
			logger.warn("Unable to read {}: {}", file, e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Link a QuPath project to a Cytomine project.
	 * @param project the QuPath project
	 * @param link the Cytomine project
	 * @throws IOException if the link cannot be saved
	 */
	public static void setLink(Project<?> project, Link link) throws IOException {
		var json = new JsonObject();
		json.addProperty("coreUrl", link.coreUrl());
		json.addProperty("projectId", link.projectId());
		json.addProperty("projectName", link.projectName());
		Files.writeString(linkFile(project).toPath(), new Gson().toJson(json), StandardCharsets.UTF_8);
	}

	/**
	 * Create a new, empty QuPath project.
	 * @param directory an empty directory
	 * @return the project
	 * @throws IOException if the directory is not suitable
	 */
	public static Project<BufferedImage> createProject(File directory) throws IOException {
		if (directory.exists() && (!directory.isDirectory() || directory.list().length > 0))
			throw new IOException("Please choose an empty folder: " + directory);
		var project = Projects.createProject(directory, BufferedImage.class);
		if (project == null)
			throw new IOException("Unable to create a project in " + directory);
		return project;
	}

	/**
	 * Find the entries of a project that correspond to images no longer in the Cytomine project.
	 * @param project the QuPath project
	 * @param client client for the server of the link
	 * @param link the Cytomine project
	 * @return the entries without a matching Cytomine image
	 * @throws IOException if the Cytomine images cannot be listed
	 */
	public static List<ProjectImageEntry<BufferedImage>> findMissing(Project<BufferedImage> project, CytomineClient client,
																	Link link) throws IOException {
		var ids = new HashSet<Long>();
		CytomineObjects.listImages(client, link.projectId()).forEach(i -> ids.add(i.id()));
		var missing = new ArrayList<ProjectImageEntry<BufferedImage>>();
		for (var entry : project.getImageList()) {
			var id = cytomineImageId(entry, link.coreUrl());
			if (id != null && !ids.contains(id))
				missing.add(entry);
		}
		return missing;
	}

	/**
	 * Update a QuPath project so that it contains an entry for each image of the Cytomine project.
	 * Existing entries (and their data) are kept. Must not be called on the JavaFX thread as it makes requests.
	 * @param project the QuPath project
	 * @param client client for the server of the link
	 * @param link the Cytomine project
	 * @param removeMissing whether to remove the entries whose image no longer exists in Cytomine
	 * @return the outcome
	 * @throws IOException if Cytomine cannot be queried or the project cannot be saved
	 */
	public static Result sync(Project<BufferedImage> project, CytomineClient client, Link link, boolean removeMissing)
			throws IOException {
		var images = CytomineObjects.listImages(client, link.projectId());
		var existing = new HashMap<Long, ProjectImageEntry<BufferedImage>>();
		for (var entry : project.getImageList()) {
			var id = cytomineImageId(entry, link.coreUrl());
			if (id != null)
				existing.put(id, entry);
		}

		var added = new ArrayList<String>();
		int renamed = 0;
		int metadataUpdated = 0;
		int metadataFailed = 0;
		int consecutiveFailures = 0;
		boolean metadataAvailable = true;
		for (var image : images) {
			var entry = existing.remove(image.id());
			if (entry == null) {
				entry = addEntry(project, client, link, image);
				added.add(image.name());
			} else if (rename(entry, image)) {
				renamed++;
			}
			if (!metadataAvailable)
				continue;
			try {
				var tags = CytomineMetadata.listTags(client, image);
				var properties = CytomineMetadata.listProperties(client, image);
				if (mergeMetadata(entry.getMetadata(), entry.getTags(), tags, properties))
					metadataUpdated++;
				consecutiveFailures = 0;
			} catch (CytomineAuthException e) {
				throw e;
			} catch (IOException | RuntimeException e) {
				// Nothing is changed for this image: with partial information, tags or properties could wrongly be removed
				metadataFailed++;
				logger.warn("Unable to read the tags and properties of {}: {}", image.name(), e.getMessage());
				if (++consecutiveFailures >= MAX_CONSECUTIVE_METADATA_FAILURES) {
					logger.warn("Giving up on the tags and properties for this synchronization");
					metadataAvailable = false;
				}
			}
		}

		// What is left in the map is not in Cytomine any more
		var missing = new ArrayList<>(existing.values());
		int removed = 0;
		if (removeMissing && !missing.isEmpty()) {
			project.removeAllImages(missing, true);
			removed = missing.size();
			missing.clear();
		}
		setLink(project, link);
		project.syncChanges();
		return new Result(added, renamed, missing, removed, metadataUpdated, metadataFailed);
	}

	private static ProjectImageEntry<BufferedImage> addEntry(Project<BufferedImage> project, CytomineClient client,
															 Link link, ImageInstance image) throws IOException {
		var uri = CytomineUrls.imageUri(link.coreUrl(), image.id());
		var builder = DefaultImageServerBuilder.createInstance(CytomineImageServerBuilder.class, uri);
		var entry = project.addImage(builder);
		if (entry == null)
			throw new IOException("Unable to add " + image.name() + " to the project");
		entry.setImageName(image.name());
		entry.getMetadata().put(KEY_IMAGE_ID, Long.toString(image.id()));
		entry.getMetadata().put(KEY_IMAGE_NAME, image.name());
		try {
			var thumbnail = CytomineObjects.getThumbnail(client, image.id(), THUMBNAIL_SIZE);
			if (thumbnail != null)
				entry.setThumbnail(thumbnail);
		} catch (CytomineAuthException e) {
			throw e;
		} catch (IOException e) {
			logger.debug("No thumbnail for {}: {}", image.name(), e.getMessage());
		}
		return entry;
	}

	/**
	 * Update the metadata and the tags of an entry so that they include those of the Cytomine image.
	 * <ul>
	 *   <li>Properties are stored under {@value #KEY_PROPERTY_PREFIX} followed by the key. Those that no longer exist
	 *   in Cytomine are removed. Other metadata is never touched.</li>
	 *   <li>Tags are added to the entry. The ones that were copied before and are no longer in Cytomine are removed;
	 *   tags added in QuPath are kept.</li>
	 * </ul>
	 * @param metadata the metadata of the entry
	 * @param entryTags the tags of the entry
	 * @param cytomineTags the tags of the image in Cytomine
	 * @param properties the properties of the image in Cytomine
	 * @return true if anything changed
	 */
	static boolean mergeMetadata(Map<String, String> metadata, Set<String> entryTags, List<String> cytomineTags,
								 List<CytomineMetadata.Property> properties) {
		boolean changed = false;

		var wanted = new LinkedHashMap<String, String>();
		for (var property : properties)
			wanted.merge(KEY_PROPERTY_PREFIX + property.key(), property.value(), (a, b) -> a + "; " + b);
		for (var key : new ArrayList<>(metadata.keySet())) {
			if (key.startsWith(KEY_PROPERTY_PREFIX) && !wanted.containsKey(key)) {
				metadata.remove(key);
				changed = true;
			}
		}
		for (var property : wanted.entrySet()) {
			if (!property.getValue().equals(metadata.get(property.getKey()))) {
				metadata.put(property.getKey(), property.getValue());
				changed = true;
			}
		}

		var current = new LinkedHashSet<>(cytomineTags);
		var removedInCytomine = readTags(metadata.get(KEY_PULLED_TAGS)).stream().filter(t -> !current.contains(t)).toList();
		changed |= entryTags.removeAll(removedInCytomine);
		changed |= entryTags.addAll(current);
		if (current.isEmpty()) {
			changed |= metadata.remove(KEY_PULLED_TAGS) != null;
		} else {
			var json = new Gson().toJson(current);
			if (!json.equals(metadata.get(KEY_PULLED_TAGS))) {
				metadata.put(KEY_PULLED_TAGS, json);
				changed = true;
			}
		}
		return changed;
	}

	private static List<String> readTags(String json) {
		if (json == null || json.isBlank())
			return List.of();
		try {
			var tags = new Gson().fromJson(json, String[].class);
			return tags == null ? List.of() : List.of(tags);
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	// Only follow Cytomine if the user has not given the entry another name
	private static boolean rename(ProjectImageEntry<BufferedImage> entry, ImageInstance image) {
		var previous = entry.getMetadata().get(KEY_IMAGE_NAME);
		if (image.name().equals(previous))
			return false;
		boolean follow = previous == null || previous.equals(entry.getImageName());
		entry.getMetadata().put(KEY_IMAGE_NAME, image.name());
		if (follow && !image.name().equals(entry.getImageName())) {
			entry.setImageName(image.name());
			return true;
		}
		return false;
	}

	private static Long cytomineImageId(ProjectImageEntry<?> entry, String coreUrl) {
		try {
			var core = CytomineUrls.normalizeCoreUrl(coreUrl);
			for (var uri : entry.getURIs()) {
				if (CytomineUrls.isImageUri(uri) && CytomineUrls.coreUrl(uri).equals(core))
					return CytomineUrls.imageId(uri);
			}
		} catch (IOException e) {
			logger.debug("Unable to read URIs of {}: {}", entry, e.getMessage());
		}
		return null;
	}

	private static File linkFile(Project<?> project) {
		return new File(Projects.getBaseDirectory(project), LINK_FILE_NAME);
	}
}
