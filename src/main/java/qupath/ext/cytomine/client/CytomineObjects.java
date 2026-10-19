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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Objects returned by the Cytomine API.
 */
public final class CytomineObjects {

	private static final int PAGE_SIZE = 100;

	private CytomineObjects() {}

	/**
	 * A Cytomine project.
	 * @param id project id
	 * @param name project name
	 */
	public record Project(long id, String name) {
		@Override
		public String toString() {
			return name;
		}
	}

	/**
	 * An image instance, i.e. an image that belongs to a project.
	 * @param id image instance id
	 * @param projectId id of the project
	 * @param name displayed name
	 * @param width width in pixels
	 * @param height height in pixels
	 * @param pixelWidthMicrons pixel width in microns, or null if unknown
	 * @param pixelHeightMicrons pixel height in microns, or null if unknown
	 * @param magnification nominal magnification, or null if unknown
	 * @param samplePerPixel number of samples per pixel (3 for RGB images)
	 * @param channels number of channels, or 1 for RGB images
	 * @param depth number of z-slices
	 * @param duration number of time points
	 * @param bitPerSample bits per sample
	 */
	public record ImageInstance(long id, long projectId, String name, int width, int height,
								Double pixelWidthMicrons, Double pixelHeightMicrons, Double magnification,
								int samplePerPixel, int channels, int depth, int duration, int bitPerSample) {

		/**
		 * @return true if the image is stored as RGB (interleaved) rather than as separate channels
		 */
		public boolean isRGB() {
			return samplePerPixel == 3;
		}

		static ImageInstance fromJson(JsonObject json) {
			var name = string(json, "instanceFilename");
			if (name == null)
				name = string(json, "originalFilename");
			if (name == null)
				name = "Image " + json.get("id").getAsLong();
			return new ImageInstance(
					json.get("id").getAsLong(),
					longValue(json, "project", -1),
					name,
					(int) longValue(json, "width", 0),
					(int) longValue(json, "height", 0),
					doubleValue(json, "physicalSizeX"),
					doubleValue(json, "physicalSizeY"),
					doubleValue(json, "magnification"),
					(int) longValue(json, "samplePerPixel", 3),
					(int) Math.max(1, longValue(json, "channels", 1)),
					(int) Math.max(1, longValue(json, "depth", 1)),
					(int) Math.max(1, longValue(json, "duration", 1)),
					(int) longValue(json, "bitPerSample", 8));
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/**
	 * Get a project.
	 * @param client the client
	 * @param id project id
	 * @return the project
	 * @throws IOException if the request fails
	 */
	public static Project getProject(CytomineClient client, long id) throws IOException {
		return parseProject(client.getJsonObject("project/" + id + ".json"));
	}

	/**
	 * List all projects visible to the current user.
	 * @param client the client
	 * @return the projects
	 * @throws IOException if a request fails
	 */
	public static List<Project> listProjects(CytomineClient client) throws IOException {
		var projects = new ArrayList<Project>();
		for (var json : listAll(client, "project.json")) {
			projects.add(parseProject(json));
		}
		return projects;
	}

	/**
	 * List all images of a project.
	 * @param client the client
	 * @param projectId project id
	 * @return the images
	 * @throws IOException if a request fails
	 */
	public static List<ImageInstance> listImages(CytomineClient client, long projectId) throws IOException {
		var images = new ArrayList<ImageInstance>();
		for (var json : listAll(client, "project/" + projectId + "/imageinstance.json")) {
			images.add(ImageInstance.fromJson(json));
		}
		return images;
	}

	/**
	 * Get an image instance.
	 * @param client the client
	 * @param id image instance id
	 * @return the image
	 * @throws IOException if the request fails
	 */
	public static ImageInstance getImage(CytomineClient client, long id) throws IOException {
		return ImageInstance.fromJson(client.getJsonObject("imageinstance/" + id + ".json"));
	}

	/**
	 * A plane (channel, z-stack and time point) of an image instance.
	 * @param id slice instance id
	 * @param channel channel index
	 * @param zStack z index
	 * @param time time index
	 */
	public record Slice(long id, int channel, int zStack, int time) {}

	/**
	 * Get the first plane of an image.
	 * @param client the client
	 * @param imageId image instance id
	 * @return the slice
	 * @throws IOException if the request fails
	 */
	public static Slice getFirstSlice(CytomineClient client, long imageId) throws IOException {
		var json = client.getJsonObject("imageinstance/" + imageId + "/0/0/0/sliceinstance.json");
		return new Slice(json.get("id").getAsLong(),
				(int) longValue(json, "channel", 0),
				(int) longValue(json, "zStack", 0),
				(int) longValue(json, "time", 0));
	}

	/**
	 * A channel of a multichannel image.
	 * @param name channel name, possibly empty
	 * @param color channel color as a packed RGB integer, or null if unknown
	 */
	public record Channel(String name, Integer color) {}

	/**
	 * Get the name and color of each channel of an image.
	 * @param client the client
	 * @param imageId image instance id
	 * @param nChannels the number of channels
	 * @return one entry per channel
	 * @throws IOException if a request fails
	 */
	public static List<Channel> getChannels(CytomineClient client, long imageId, int nChannels) throws IOException {
		var channels = new ArrayList<Channel>();
		for (int c = 0; c < nChannels; c++) {
			var json = client.getJsonObject("imageinstance/" + imageId + "/" + c + "/0/0/sliceinstance.json");
			channels.add(new Channel(string(json, "channelName"), parseColor(string(json, "channelColor"))));
		}
		return channels;
	}

	/**
	 * Download a small thumbnail of an image.
	 * @param client the client
	 * @param imageId image instance id
	 * @param maxSize maximum width or height
	 * @return the thumbnail, or null if it could not be decoded
	 * @throws IOException if the request fails
	 */
	public static java.awt.image.BufferedImage getThumbnail(CytomineClient client, long imageId, int maxSize) throws IOException {
		var bytes = client.getBytes("imageinstance/" + imageId + "/thumb.png?maxSize=" + maxSize, "image/png");
		return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
	}

	static Integer parseColor(String s) {
		if (s == null)
			return null;
		s = s.strip();
		if (s.startsWith("#"))
			s = s.substring(1);
		try {
			if (s.length() == 3)
				s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
			if (s.length() != 6)
				return null;
			return Integer.parseInt(s, 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Project parseProject(JsonObject json) {
		return new Project(json.get("id").getAsLong(), string(json, "name") == null ? "Project " + json.get("id").getAsLong() : string(json, "name"));
	}

	static List<JsonObject> listAll(CytomineClient client, String path) throws IOException {
		var results = new ArrayList<JsonObject>();
		int offset = 0;
		while (true) {
			var page = client.getJsonObject(path + (path.contains("?") ? "&" : "?") + "max=" + PAGE_SIZE + "&offset=" + offset);
			var collection = page.getAsJsonArray("collection");
			if (collection == null || collection.isEmpty())
				break;
			collection.forEach(e -> results.add(e.getAsJsonObject()));
			offset += collection.size();
			long total = longValue(page, "size", results.size());
			if (offset >= total)
				break;
		}
		return results;
	}

	private static String string(JsonObject json, String key) {
		JsonElement e = json.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static long longValue(JsonObject json, String key, long defaultValue) {
		JsonElement e = json.get(key);
		return e == null || e.isJsonNull() ? defaultValue : e.getAsLong();
	}

	private static Double doubleValue(JsonObject json, String key) {
		JsonElement e = json.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsDouble();
	}
}
