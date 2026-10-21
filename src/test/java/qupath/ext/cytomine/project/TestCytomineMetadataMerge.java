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

import org.junit.jupiter.api.Test;
import qupath.ext.cytomine.client.CytomineMetadata.Property;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the tags and properties of Cytomine are merged into the metadata and tags of a QuPath project entry.
 */
class TestCytomineMetadataMerge {

	private final HashMap<String, String> metadata = new HashMap<>();
	private final Set<String> tags = new LinkedHashSet<>();

	private boolean merge(List<String> cytomineTags, Property... properties) {
		return CytomineProjectSync.mergeMetadata(metadata, tags, cytomineTags, List.of(properties));
	}

	@Test
	void propertiesAndTagsAreCopied() {
		assertTrue(merge(List.of("tumor"), new Property("Stain", "H&E")));
		assertEquals("H&E", metadata.get("Cytomine: Stain"));
		assertEquals(Set.of("tumor"), tags);
	}

	@Test
	void secondPullWithoutChangeChangesNothing() {
		merge(List.of("tumor", "control"), new Property("Stain", "H&E"));
		assertFalse(merge(List.of("tumor", "control"), new Property("Stain", "H&E")));
	}

	@Test
	void changesAndRemovalsInCytomineFollow() {
		merge(List.of("tumor", "control"), new Property("Stain", "H&E"), new Property("Block", "A1"));
		assertTrue(merge(List.of("control"), new Property("Stain", "IHC")));
		assertEquals("IHC", metadata.get("Cytomine: Stain"));
		assertFalse(metadata.containsKey("Cytomine: Block"));
		assertEquals(Set.of("control"), tags);
	}

	@Test
	void whatTheUserAddedInQuPathIsNeverTouched() {
		metadata.put("Cytomine image ID", "7");
		metadata.put("Cytomine image name", "slide");
		metadata.put("Stain", "mine");
		tags.add("reviewed");
		merge(List.of("tumor"), new Property("Stain", "H&E"));
		// Tag removed in Cytomine, everything else stays
		merge(List.of());

		assertEquals("7", metadata.get("Cytomine image ID"));
		assertEquals("slide", metadata.get("Cytomine image name"));
		assertEquals("mine", metadata.get("Stain"));
		assertEquals(Set.of("reviewed"), tags);
		assertFalse(metadata.containsKey("Cytomine: Stain"));
	}

	@Test
	void noTagsLeavesNoBookkeeping() {
		merge(List.of("tumor"));
		assertTrue(metadata.containsKey("Cytomine tags"));
		merge(List.of());
		assertFalse(metadata.containsKey("Cytomine tags"));
		assertTrue(tags.isEmpty());
	}

	@Test
	void repeatedKeysAreJoined() {
		merge(List.of(), new Property("Operator", "A"), new Property("Operator", "B"));
		assertEquals("A; B", metadata.get("Cytomine: Operator"));
	}

	@Test
	void unreadableBookkeepingIsIgnored() {
		metadata.put("Cytomine tags", "not json");
		tags.add("mine");
		merge(List.of("tumor"));
		assertEquals(Set.of("mine", "tumor"), tags);
	}
}
