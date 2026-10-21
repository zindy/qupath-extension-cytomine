package qupath.ext.cytomine.annotations;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCytomineAnnotationMeasurements {

	@Test
	void measurementsBecomePrefixedProperties() {
		var obj = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()));
		assertTrue(CytomineAnnotationSync.measurementsOf(obj).isEmpty());

		try (var list = obj.getMeasurementList()) {
			list.put("Area px^2", 100);
			list.put("Not a number", Double.NaN);
			list.put("Mean", 0.5);
		}
		var properties = CytomineAnnotationSync.measurementsOf(obj);
		assertEquals(2, properties.size());
		assertEquals("100.0", properties.get("QuPath: Area px^2"));
		assertEquals("0.5", properties.get("QuPath: Mean"));
	}
}
