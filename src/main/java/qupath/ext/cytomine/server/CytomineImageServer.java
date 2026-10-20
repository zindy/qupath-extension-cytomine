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
import qupath.ext.cytomine.client.CytomineClient;
import qupath.ext.cytomine.client.CytomineClients;
import qupath.ext.cytomine.client.CytomineObjects;
import qupath.ext.cytomine.client.CytomineObjects.ImageInstance;
import qupath.ext.cytomine.client.CytomineUrls;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

/**
 * ImageServer that reads 8-bit tiles from Cytomine.
 * <p>
 * Cytomine serves "normalized" tiles: 256x256 pixels, with each pyramid level half the size (rounded up) of the previous
 * one, whatever the format of the original file. Zoom 0 is the smallest level, and the highest zoom is full resolution.
 * <p>
 * RGB images are returned as RGB. Other images are returned with one channel per Cytomine channel; each channel is
 * requested separately. Cytomine rescales intensities for display, so pixel values are 8-bit display values and not
 * the original raw values.
 */
public class CytomineImageServer extends AbstractTileableImageServer {

	private static final Logger logger = LoggerFactory.getLogger(CytomineImageServer.class);

	private static final int TILE_SIZE = 256;

	private final URI uri;
	private final String[] args;
	private final CytomineClient client;
	private final ImageInstance image;
	private final long sliceId;
	private final int maxZoom;
	private final List<ImageChannel> channels;
	private final String rgbChannelQuery;
	private final PixelType pixelType;
	private final double[] channelMax;
	private final ImageServerMetadata originalMetadata;

	/**
	 * Create a server.
	 * @param uri image URI, see {@link CytomineUrls#imageUri(String, long)}
	 * @param client authenticated client
	 * @param args optional arguments (unused)
	 * @throws IOException if the image cannot be read from the server
	 */
	public CytomineImageServer(URI uri, CytomineClient client, String... args) throws IOException {
		super();
		this.uri = uri;
		this.args = args;
		this.client = client;
		long imageId = CytomineUrls.imageId(uri);
		this.image = CytomineObjects.getImage(client, imageId);
		this.sliceId = CytomineObjects.getFirstSlice(client, imageId).id();

		var levels = new ImageServerMetadata.ImageResolutionLevel.Builder(image.width(), image.height());
		int w = image.width();
		int h = image.height();
		int nLevels = 1;
		levels.addFullResolutionLevel();
		while (w > TILE_SIZE || h > TILE_SIZE) {
			w = (w + 1) / 2;
			h = (h + 1) / 2;
			levels.addLevel(w, h);
			nLevels++;
		}
		this.maxZoom = nLevels - 1;

		if (image.isRGB()) {
			this.channels = ImageChannel.getDefaultRGBChannels();
			this.rgbChannelQuery = chooseRgbChannelQuery();
			this.pixelType = PixelType.UINT8;
			this.channelMax = null;
		} else {
			this.channels = createChannels(client, imageId, image.channels());
			this.rgbChannelQuery = null;
			this.pixelType = image.bitPerSample() > 8 ? PixelType.UINT16 : PixelType.UINT8;
			this.channelMax = estimateRanges();
		}

		var builder = new ImageServerMetadata.Builder()
				.name(image.name())
				.width(image.width())
				.height(image.height())
				.sizeZ(image.depth())
				.sizeT(image.duration())
				.pixelType(pixelType)
				.rgb(image.isRGB())
				.channels(channels)
				.preferredTileSize(TILE_SIZE, TILE_SIZE)
				.levels(levels.build());
		if (image.pixelWidthMicrons() != null && image.pixelHeightMicrons() != null &&
				image.pixelWidthMicrons() > 0 && image.pixelHeightMicrons() > 0)
			builder.pixelSizeMicrons(image.pixelWidthMicrons(), image.pixelHeightMicrons());
		if (image.magnification() != null && image.magnification() > 0)
			builder.magnification(image.magnification());
		this.originalMetadata = builder.build();
	}

	private static List<ImageChannel> createChannels(CytomineClient client, long imageId, int nChannels) throws IOException {
		var info = CytomineObjects.getChannels(client, imageId, nChannels);
		var list = new ArrayList<ImageChannel>();
		for (int c = 0; c < nChannels; c++) {
			var name = info.get(c).name();
			if (name == null || name.isBlank())
				name = "Channel " + (c + 1);
			var color = info.get(c).color();
			if (color == null)
				color = ImageChannel.getDefaultChannelColor(c);
			list.add(ImageChannel.getInstance(name, color));
		}
		return list;
	}

	private String tilePath(int zoom, int tx, int ty, String query) {
		return "sliceinstance/" + sliceId + "/normalized-tile/zoom/" + zoom + "/tx/" + tx + "/ty/" + ty + ".jpg?" + query;
	}

	/**
	 * Find how to request RGB tiles. Requesting all channels (the web viewer's behavior) is needed for images stored as
	 * separate R, G and B channels, but fails for some images stored as interleaved RGB, for which only the first
	 * channel can be requested explicitly.
	 */
	private String chooseRgbChannelQuery() throws IOException {
		IOException lastError = null;
		for (var channelQuery : List.of("", "channels=0&")) {
			try {
				client.getBytes(tilePath(0, 0, 0, channelQuery + "zSlices=0&timepoints=0"), "image/jpeg");
				return channelQuery;
			} catch (CytomineAuthException e) {
				// Not a problem with this way of requesting tiles
				throw e;
			} catch (IOException e) {
				lastError = e;
				logger.debug("Tile request '{}' rejected by Cytomine for {}: {}", channelQuery, uri, e.getMessage());
			}
		}
		throw lastError;
	}

	/**
	 * @return the intensity range used for each channel of a multichannel image (null for RGB images)
	 */
	public double[] getIntensityRanges() {
		return channelMax == null ? null : channelMax.clone();
	}

	/**
	 * @return the Cytomine image instance represented by this server
	 */
	public ImageInstance getImage() {
		return image;
	}

	@Override
	protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
		int zoom = maxZoom - tileRequest.getLevel();
		int tx = tileRequest.getTileX() / TILE_SIZE;
		int ty = tileRequest.getTileY() / TILE_SIZE;
		var plane = "zSlices=" + tileRequest.getZ() + "&timepoints=" + tileRequest.getT();
		try {
			if (image.isRGB())
				return readRgbTile(tilePath(zoom, tx, ty, rgbChannelQuery + plane));
			return readChannelTiles(zoom, tx, ty, plane);
		} catch (CytomineAuthException e) {
			// Tiles are read in the background: the user would otherwise only see a blank viewer
			CytomineClients.reportAuthFailure(client, e);
			throw e;
		}
	}

	private BufferedImage readRgbTile(String path) throws IOException {
		var img = decode(path);
		if (img.getType() == BufferedImage.TYPE_INT_RGB || img.getType() == BufferedImage.TYPE_INT_ARGB)
			return img;
		var rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		var g = rgb.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return rgb;
	}

	private BufferedImage readChannelTiles(int zoom, int tx, int ty, String plane) throws IOException {
		int nChannels = channels.size();
		List<BufferedImage> tiles;
		try {
			tiles = IntStream.range(0, nChannels).parallel().mapToObj(c -> {
				try {
					return decode(tilePath(zoom, tx, ty, channelQuery(c, channelMax[c], plane)));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}).toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		int w = tiles.get(0).getWidth();
		int h = tiles.get(0).getHeight();
		var raster = Raster.createBandedRaster(
				pixelType == PixelType.UINT16 ? DataBuffer.TYPE_USHORT : DataBuffer.TYPE_BYTE, w, h, nChannels, null);
		for (int c = 0; c < nChannels; c++) {
			var tile = tiles.get(c);
			int tw = Math.min(w, tile.getWidth());
			int th = Math.min(h, tile.getHeight());
			var samples = intensities(tile, tw, th);
			// Tiles are 8-bit and were scaled to [0, max] by Cytomine: convert back to approximate original values
			if (pixelType == PixelType.UINT16) {
				double scale = channelMax[c] / 255.0;
				for (int i = 0; i < samples.length; i++)
					samples[i] = (int) Math.round(samples[i] * scale);
			}
			raster.setSamples(0, 0, tw, th, c, samples);
		}
		var colorModel = ColorModelFactory.createColorModel(pixelType, channels);
		return new BufferedImage(colorModel, raster, false, null);
	}

	// Cytomine colors each channel with its own color (black to color ramp): the brightest component is the intensity
	private static int[] intensities(BufferedImage tile, int w, int h) {
		var rgb = tile.getRGB(0, 0, w, h, null, 0, w);
		var samples = new int[rgb.length];
		for (int i = 0; i < rgb.length; i++)
			samples[i] = Math.max((rgb[i] >> 16) & 0xff, Math.max((rgb[i] >> 8) & 0xff, rgb[i] & 0xff));
		return samples;
	}

	/**
	 * Sorted intensities, ignoring saturated pixels and their surroundings (to skip JPEG artefacts around them).
	 * CZI mosaics often have the area that is not covered by a scene filled with the maximum value.
	 */
	private static int[] nonSaturated(int[] values, int w, int h) {
		var keep = new java.util.ArrayList<Integer>();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				boolean ok = true;
				for (int dy = -2; dy <= 2 && ok; dy++) {
					for (int dx = -2; dx <= 2; dx++) {
						int xx = x + dx;
						int yy = y + dy;
						if (xx >= 0 && yy >= 0 && xx < w && yy < h && values[yy * w + xx] >= 250) {
							ok = false;
							break;
						}
					}
				}
				if (ok)
					keep.add(values[y * w + x]);
			}
		}
		return keep.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	private String channelQuery(int c, double max, String plane) {
		return "channels=" + c + "&minIntensities=0&maxIntensities=" + (long) Math.ceil(max) + "&" + plane;
	}

	/**
	 * Choose the intensity range of each channel.
	 * Cytomine's automatic range relies on image histograms, which can be wrong (e.g. both bounds at the maximum
	 * value). Starting from the full range of the pixel type, the range is narrowed using the overview of the image
	 * until the brightest pixels use most of the 8-bit range of the tiles.
	 */
	private double[] estimateRanges() throws IOException {
		int nChannels = channels.size();
		double fullScale = Math.pow(2, Math.min(image.bitPerSample(), 16)) - 1;
		var ranges = new double[nChannels];
		java.util.Arrays.fill(ranges, fullScale);
		if (fullScale <= 255)
			return ranges;
		var plane = "zSlices=0&timepoints=0";
		for (int c = 0; c < nChannels; c++) {
			for (int iteration = 0; iteration < 5; iteration++) {
				var tile = decode(tilePath(0, 0, 0, channelQuery(c, ranges[c], plane)));
				var values = nonSaturated(intensities(tile, tile.getWidth(), tile.getHeight()), tile.getWidth(), tile.getHeight());
				if (values.length == 0)
					break;
				int p = values[(int) Math.min(values.length - 1, Math.floor(values.length * 0.999))];
				if (p >= 200)
					break;
				// Aim for the 99.9th percentile at about 3/4 of the range
				double newRange = p == 0 ? ranges[c] / 16 : ranges[c] * p / 190.0;
				ranges[c] = Math.max(255, Math.min(fullScale, newRange));
				if (ranges[c] <= 255)
					break;
			}
		}
		logger.debug("Intensity ranges for {}: {}", uri, java.util.Arrays.toString(ranges));
		return ranges;
	}

	private BufferedImage decode(String path) throws IOException {
		var bytes = client.getBytes(path, "image/jpeg");
		var img = ImageIO.read(new ByteArrayInputStream(bytes));
		if (img == null)
			throw new IOException("Unable to decode tile " + path);
		return img;
	}

	@Override
	protected ServerBuilder<BufferedImage> createServerBuilder() {
		return DefaultImageServerBuilder.createInstance(CytomineImageServerBuilder.class, getMetadata(), uri, args);
	}

	@Override
	protected String createID() {
		return getClass().getName() + ": " + uri + " " + String.join(" ", args);
	}

	@Override
	public Collection<URI> getURIs() {
		return List.of(uri);
	}

	@Override
	public String getServerType() {
		return "Cytomine";
	}

	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return originalMetadata;
	}

	@Override
	public void close() {
		// Nothing to release: the client is shared and owned by CytomineClients
	}
}