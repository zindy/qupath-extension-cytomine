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

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

import java.io.IOException;

/**
 * Converts between QuPath ROIs and the WKT geometries of Cytomine.
 * <p>
 * Cytomine uses image pixels, but with the origin at the bottom left of the image, so the y coordinate is flipped.
 */
final class GeometryConversion {

	private GeometryConversion() {}

	/**
	 * Convert a ROI to a WKT geometry in Cytomine coordinates, with a precision of 2 decimals.
	 * @param roi the ROI
	 * @param imageHeight the height of the image in pixels
	 * @return the WKT
	 */
	static String toWkt(ROI roi, int imageHeight) {
		Geometry geometry = flip(roi.getGeometry(), imageHeight);
		if (!geometry.isValid())
			geometry = geometry.buffer(0);
		var writer = new WKTWriter();
		writer.setPrecisionModel(new PrecisionModel(100));
		return writer.write(geometry);
	}

	/**
	 * Convert a WKT geometry in Cytomine coordinates to a ROI.
	 * @param wkt the WKT
	 * @param imageHeight the height of the image in pixels
	 * @param plane the plane of the ROI
	 * @return the ROI
	 * @throws IOException if the WKT cannot be parsed
	 */
	static ROI toRoi(String wkt, int imageHeight, ImagePlane plane) throws IOException {
		try {
			var geometry = flip(new WKTReader().read(wkt), imageHeight);
			if (!geometry.isValid())
				geometry = geometry.buffer(0);
			return GeometryTools.geometryToROI(geometry, plane);
		} catch (ParseException | RuntimeException e) {
			throw new IOException("Unsupported geometry: " + e.getMessage(), e);
		}
	}

	private static Geometry flip(Geometry geometry, int imageHeight) {
		return new AffineTransformation(1, 0, 0, 0, -1, imageHeight).transform(geometry);
	}
}
