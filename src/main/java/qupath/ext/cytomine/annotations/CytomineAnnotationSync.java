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

package qupath.ext.cytomine.annotations;

import qupath.ext.cytomine.client.CytomineAnnotations;
import qupath.ext.cytomine.client.CytomineAnnotations.RemoteAnnotation;
import qupath.ext.cytomine.client.CytomineAnnotations.Term;
import qupath.ext.cytomine.client.CytomineAuthException;
import qupath.ext.cytomine.client.CytomineClient;
import qupath.ext.cytomine.client.CytomineHttpException;
import qupath.ext.cytomine.client.CytomineObjects;
import qupath.ext.cytomine.client.CytomineUrls;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Two-way synchronization of the annotations of one image with Cytomine.
 * <p>
 * Each Cytomine annotation belongs to a user, and a user can only change their own annotations. Accordingly:
 * <ul>
 *     <li>annotations of the current user are editable in QuPath and can be pushed back;</li>
 *     <li>annotations of other users are pulled as locked objects named after their author, and never pushed.</li>
 * </ul>
 * QuPath objects remember the Cytomine annotation they come from (id, last update, a hash of the geometry and the term)
 * in their metadata. This allows detecting what changed on each side since the last synchronization. When an annotation
 * changed on both sides, nothing is overwritten and the conflict is reported.
 */
public class CytomineAnnotationSync {

	static final String KEY_ID = "Cytomine ID";
	static final String KEY_USER = "Cytomine user ID";
	static final String KEY_UPDATED = "Cytomine updated";
	static final String KEY_HASH = "Cytomine geometry hash";
	static final String KEY_TERM = "Cytomine term";
	static final String PROP_SYNCED_IDS = "Cytomine synced annotation IDs";

	/**
	 * Outcome of a pull or a push.
	 */
	public static class Report {
		/** Annotations added to QuPath. */
		public int added;
		/** Annotations changed in QuPath. */
		public int updated;
		/** Annotations removed from QuPath because they were deleted in Cytomine. */
		public int removed;
		/** Annotations created in Cytomine. */
		public int created;
		/** Annotations changed in Cytomine. */
		public int pushed;
		/** Annotations deleted in Cytomine. */
		public int deleted;
		/** Annotations that were not synchronized because they changed on both sides. */
		public final List<String> conflicts = new ArrayList<>();
		/** Other problems. */
		public final List<String> warnings = new ArrayList<>();

		/**
		 * @return a short description of the outcome
		 */
		public String summary() {
			var sb = new StringBuilder();
			sb.append(String.format("QuPath: %d added, %d updated, %d removed. Cytomine: %d created, %d updated, %d deleted.",
					added, updated, removed, created, pushed, deleted));
			if (!conflicts.isEmpty())
				sb.append("\n\nConflicts (kept as is):\n- ").append(String.join("\n- ", conflicts));
			if (!warnings.isEmpty())
				sb.append("\n\nProblems:\n- ").append(String.join("\n- ", warnings));
			return sb.toString();
		}
	}

	private final ImageData<BufferedImage> imageData;
	private final CytomineClient client;
	private final long imageId;
	private final long projectId;
	private final int imageHeight;
	private final long userId;
	private final Long ontologyId;
	private final Map<String, Term> termsByName = new HashMap<>();
	private final Map<Long, Term> termsById = new HashMap<>();
	private final Map<Long, String> userNames;

	/**
	 * Prepare the synchronization of the annotations of an image opened from Cytomine.
	 * @param imageData the image data
	 * @param client client logged in to the server of the image
	 * @throws IOException if the image does not come from Cytomine or the server cannot be queried
	 */
	public CytomineAnnotationSync(ImageData<BufferedImage> imageData, CytomineClient client) throws IOException {
		this.imageData = imageData;
		this.client = client;
		var uri = imageData.getServer().getURIs().stream().filter(CytomineUrls::isImageUri).findFirst()
				.orElseThrow(() -> new IOException("This image does not come from Cytomine"));
		this.imageId = CytomineUrls.imageId(uri);
		this.imageHeight = imageData.getServer().getHeight();
		this.projectId = CytomineObjects.getImage(client, imageId).projectId();
		this.userId = client.getUserId();
		this.ontologyId = CytomineAnnotations.getOntologyId(client, projectId);
		if (ontologyId != null) {
			for (var term : CytomineAnnotations.listTerms(client, ontologyId)) {
				termsByName.put(term.name(), term);
				termsById.put(term.id(), term);
			}
		}
		this.userNames = CytomineAnnotations.listUsers(client, projectId);
	}

	/**
	 * @return the classes corresponding to the terms of the ontology of the project
	 */
	public List<PathClass> getPathClasses() {
		return termsById.values().stream().map(CytomineAnnotationSync::toPathClass).collect(Collectors.toList());
	}

	/**
	 * Get the annotations from Cytomine.
	 * @return the report
	 * @throws IOException if the annotations cannot be listed
	 */
	public synchronized Report pull() throws IOException {
		var report = new Report();
		var remote = CytomineAnnotations.list(client, imageId);
		var hierarchy = imageData.getHierarchy();
		var local = localById();
		var toAdd = new ArrayList<PathObject>();
		var toRemove = new ArrayList<PathObject>();
		var synced = new LinkedHashSet<Long>();

		for (var r : remote) {
			boolean mine = r.userId() == userId;
			var obj = local.remove(r.id());
			if (mine)
				synced.add(r.id());
			try {
				if (obj == null) {
					toAdd.add(createLocal(r));
					report.added++;
				} else if (hasRemoteChanged(obj, r)) {
					if (mine && isModified(obj)) {
						report.conflicts.add(describe(obj) + " changed in both QuPath and Cytomine");
					} else {
						toRemove.add(obj);
						toAdd.add(createLocal(r));
						report.updated++;
					}
				}
			} catch (CytomineAuthException e) {
				// Stop, rather than reporting every annotation as a separate warning
				throw e;
			} catch (IOException e) {
				report.warnings.add("Annotation " + r.id() + ": " + e.getMessage());
			}
		}

		// What is left was deleted in Cytomine
		for (var obj : local.values()) {
			if (isMine(obj) && isModified(obj)) {
				obj.getMetadata().remove(KEY_ID);
				report.conflicts.add(describe(obj) + " was deleted in Cytomine but changed in QuPath: it is kept and will be created again on push");
			} else {
				toRemove.add(obj);
				report.removed++;
			}
		}

		if (!toRemove.isEmpty())
			hierarchy.removeObjects(toRemove, true);
		if (!toAdd.isEmpty())
			hierarchy.addObjects(toAdd);
		saveSyncedIds(synced);
		imageData.setChanged(true);
		return report;
	}

	/**
	 * @return the number of annotations of the current user that were synchronized before but have been deleted in QuPath
	 */
	public int countDeletedLocally() {
		var present = new HashSet<Long>();
		localById().keySet().forEach(present::add);
		return (int) loadSyncedIds().stream().filter(id -> !present.contains(id)).count();
	}

	/**
	 * Send the annotations of the current user to Cytomine.
	 * @param deleteRemoved whether to delete in Cytomine the annotations that were deleted in QuPath
	 * @return the report
	 * @throws IOException if the project data cannot be read
	 */
	public synchronized Report push(boolean deleteRemoved) throws IOException {
		var report = new Report();
		var previouslySynced = loadSyncedIds();
		var synced = new LinkedHashSet<Long>();
		var plane = ImagePlane.getDefaultPlane();

		try {
			for (var obj : new ArrayList<>(imageData.getHierarchy().getAnnotationObjects())) {
				if (obj.getROI() == null || !isMine(obj))
					continue;
				try {
					var idText = obj.getMetadata().get(KEY_ID);
					if (idText == null) {
						synced.add(createRemote(obj, report));
						continue;
					}
					long id = Long.parseLong(idText);
					synced.add(id);
					if (isModified(obj))
						updateRemote(obj, id, report);
				} catch (CytomineAuthException e) {
					throw e;
				} catch (IOException | RuntimeException e) {
					report.warnings.add(describe(obj) + ": " + e.getMessage());
				}
			}

			for (long id : previouslySynced) {
				if (synced.contains(id))
					continue;
				if (!deleteRemoved) {
					// Keep tracking it, so that it can be deleted later
					continue;
				}
				try {
					if (CytomineAnnotations.get(client, id).userId() == userId) {
						CytomineAnnotations.delete(client, id);
						report.deleted++;
					}
				} catch (CytomineAuthException e) {
					throw e;
				} catch (CytomineHttpException e) {
					// 404: already deleted in Cytomine, nothing left to do (and it stops being tracked)
					if (e.getStatusCode() != 404)
						report.warnings.add("Annotation " + id + " could not be deleted: " + e.getMessage());
				} catch (IOException e) {
					report.warnings.add("Annotation " + id + " could not be deleted: " + e.getMessage());
				}
			}
		} catch (IOException | RuntimeException e) {
			// Aborted, e.g. because the session could not be renewed. The annotations created so far must stay tracked
			// (they already carry their Cytomine id), and nothing that was tracked before is forgotten.
			var tracked = new LinkedHashSet<>(previouslySynced);
			tracked.addAll(synced);
			saveSyncedIds(tracked);
			imageData.setChanged(true);
			throw e;
		}
		if (!deleteRemoved)
			synced.addAll(previouslySynced.stream().filter(id -> !synced.contains(id)).toList());
		saveSyncedIds(synced);
		imageData.setChanged(true);
		return report;
	}

	private long createRemote(PathObject obj, Report report) throws IOException {
		var wkt = GeometryConversion.toWkt(obj.getROI(), imageHeight);
		var term = termFor(obj.getPathClass(), report);
		var created = CytomineAnnotations.create(client, imageId, projectId, wkt, term == null ? null : term.id());
		remember(obj, created, wkt);
		report.created++;
		return created.id();
	}

	private void updateRemote(PathObject obj, long id, Report report) throws IOException {
		RemoteAnnotation remote;
		try {
			remote = CytomineAnnotations.get(client, id);
		} catch (CytomineHttpException e) {
			// Any other problem (server error, timeout, authentication...) is not the same thing as an annotation that
			// was deleted in Cytomine, and is reported as such by the caller
			if (e.getStatusCode() != 404)
				throw e;
			report.conflicts.add(describe(obj) + " no longer exists in Cytomine (pull to refresh)");
			return;
		}
		if (remote.userId() != userId) {
			report.warnings.add(describe(obj) + " belongs to another user in Cytomine");
			return;
		}
		if (!Long.toString(remote.updated()).equals(obj.getMetadata().get(KEY_UPDATED))) {
			report.conflicts.add(describe(obj) + " changed in both QuPath and Cytomine");
			return;
		}
		var wkt = GeometryConversion.toWkt(obj.getROI(), imageHeight);
		if (!hash(wkt).equals(obj.getMetadata().get(KEY_HASH)))
			remote = CytomineAnnotations.updateLocation(client, remote, wkt);
		if (!Objects.equals(className(obj), obj.getMetadata().get(KEY_TERM))) {
			var term = termFor(obj.getPathClass(), report);
			if (term != null || obj.getPathClass() == null)
				CytomineAnnotations.setTerm(client, remote, term == null ? null : term.id());
		}
		remember(obj, remote, wkt);
		report.pushed++;
	}

	private Term termFor(PathClass pathClass, Report report) throws IOException {
		if (pathClass == null || pathClass == PathClass.NULL_CLASS)
			return null;
		var name = pathClass.toString();
		var term = termsByName.get(name);
		if (term == null) {
			if (ontologyId == null) {
				report.warnings.add("Class '" + name + "' was not sent as a term: the Cytomine project has no ontology");
				return null;
			}
			term = CytomineAnnotations.createTerm(client, ontologyId, name, pathClass.getColor());
			termsByName.put(name, term);
			termsById.put(term.id(), term);
		}
		return term;
	}

	private PathObject createLocal(RemoteAnnotation r) throws IOException {
		var roi = GeometryConversion.toRoi(r.wkt(), imageHeight, ImagePlane.getDefaultPlane());
		var term = firstTerm(r);
		var obj = PathObjects.createAnnotationObject(roi, term == null ? null : toPathClass(term));
		remember(obj, r, GeometryConversion.toWkt(roi, imageHeight));
		obj.getMetadata().put(KEY_TERM, term == null ? "" : term.name());
		if (r.userId() != userId) {
			obj.setName(userNames.getOrDefault(r.userId(), "User " + r.userId()));
			obj.setLocked(true);
		}
		return obj;
	}

	private void remember(PathObject obj, RemoteAnnotation r, String wkt) {
		var metadata = obj.getMetadata();
		metadata.put(KEY_ID, Long.toString(r.id()));
		metadata.put(KEY_USER, Long.toString(r.userId()));
		metadata.put(KEY_UPDATED, Long.toString(r.updated()));
		metadata.put(KEY_HASH, hash(wkt));
		metadata.put(KEY_TERM, className(obj));
	}

	private Term firstTerm(RemoteAnnotation r) {
		for (var id : r.termIds()) {
			var term = termsById.get(id);
			if (term != null)
				return term;
		}
		return null;
	}

	private boolean hasRemoteChanged(PathObject obj, RemoteAnnotation r) {
		var term = firstTerm(r);
		return !Long.toString(r.updated()).equals(obj.getMetadata().get(KEY_UPDATED)) ||
				!Objects.equals(term == null ? "" : term.name(), obj.getMetadata().get(KEY_TERM));
	}

	// Compared with what was synchronized last: the geometry and the class
	private boolean isModified(PathObject obj) {
		var hash = obj.getMetadata().get(KEY_HASH);
		if (hash == null || obj.getROI() == null)
			return true;
		return !hash.equals(hash(GeometryConversion.toWkt(obj.getROI(), imageHeight))) ||
				!Objects.equals(className(obj), obj.getMetadata().get(KEY_TERM));
	}

	private boolean isMine(PathObject obj) {
		var user = obj.getMetadata().get(KEY_USER);
		return user == null || user.equals(Long.toString(userId));
	}

	private Map<Long, PathObject> localById() {
		var map = new HashMap<Long, PathObject>();
		for (var obj : imageData.getHierarchy().getAnnotationObjects()) {
			var id = obj.getMetadata().get(KEY_ID);
			if (id != null)
				map.put(Long.parseLong(id), obj);
		}
		return map;
	}

	private Set<Long> loadSyncedIds() {
		var ids = new LinkedHashSet<Long>();
		var value = imageData.getProperty(PROP_SYNCED_IDS);
		if (value instanceof String s && !s.isBlank()) {
			for (var part : s.split(","))
				ids.add(Long.parseLong(part.strip()));
		}
		return ids;
	}

	private void saveSyncedIds(Set<Long> ids) {
		imageData.setProperty(PROP_SYNCED_IDS, ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
	}

	private static String className(PathObject obj) {
		var pathClass = obj.getPathClass();
		return pathClass == null || pathClass == PathClass.NULL_CLASS ? "" : pathClass.toString();
	}

	private static String describe(PathObject obj) {
		var id = obj.getMetadata().get(KEY_ID);
		return "Annotation " + (obj.getName() != null ? obj.getName() : obj.getDisplayedName()) + (id == null ? "" : " (#" + id + ")");
	}

	private static String hash(String wkt) {
		return Integer.toString(wkt.hashCode());
	}

	private static PathClass toPathClass(Term term) {
		return PathClass.fromString(term.name(), term.color());
	}
}
