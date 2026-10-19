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

package qupath.ext.cytomine;

import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cytomine.client.CytomineClients;
import qupath.ext.cytomine.ui.AnnotationCommands;
import qupath.ext.cytomine.ui.CytomineBrowser;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.util.ArrayList;
import java.util.ResourceBundle;

/**
 * QuPath extension to access images stored in a Cytomine server through its REST API.
 */
public class CytomineExtension implements QuPathExtension {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.cytomine.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(CytomineExtension.class);

	private static final String EXTENSION_NAME = resources.getString("name");
	private static final String EXTENSION_DESCRIPTION = resources.getString("description");
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

	private boolean isInstalled = false;
	private CytomineBrowser browser;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;

		var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
		var browseItem = new MenuItem(resources.getString("menu.browse"));
		browseItem.setOnAction(e -> {
			if (browser == null)
				browser = new CytomineBrowser(qupath);
			browser.show();
		});
		var logoutItem = new MenuItem(resources.getString("menu.logout"));
		logoutItem.setOnAction(e -> new ArrayList<>(CytomineClients.getAll())
				.forEach(c -> CytomineClients.remove(c.getCoreUri().toString())));
		var pullItem = new MenuItem(resources.getString("annotations.pull"));
		pullItem.setOnAction(e -> AnnotationCommands.pull(qupath));
		var pushItem = new MenuItem(resources.getString("annotations.push"));
		pushItem.setOnAction(e -> AnnotationCommands.push(qupath));
		menu.getItems().addAll(browseItem, new SeparatorMenuItem(), pullItem, pushItem, new SeparatorMenuItem(), logoutItem);
	}

	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}

	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}
