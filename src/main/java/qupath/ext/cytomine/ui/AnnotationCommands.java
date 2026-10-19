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

package qupath.ext.cytomine.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cytomine.annotations.CytomineAnnotationSync;
import qupath.ext.cytomine.client.CytomineClients;
import qupath.ext.cytomine.client.CytomineUrls;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Commands to pull annotations from, and push annotations to, Cytomine for the image open in the current viewer.
 */
public final class AnnotationCommands {

	private static final Logger logger = LoggerFactory.getLogger(AnnotationCommands.class);
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.cytomine.ui.strings");

	private AnnotationCommands() {}

	/**
	 * Get the annotations of the current image from Cytomine.
	 * @param qupath the QuPath instance
	 */
	public static void pull(QuPathGUI qupath) {
		var sync = prepare(qupath);
		if (sync == null)
			return;
		var title = resources.getString("annotations.pull");
		runInBackground(title, sync, s -> {
			var report = s.pull();
			var classes = s.getPathClasses();
			Platform.runLater(() -> {
				for (var pathClass : classes) {
					if (!qupath.getAvailablePathClasses().contains(pathClass))
						qupath.getAvailablePathClasses().add(pathClass);
				}
			});
			return report;
		}, report -> {
			refresh(qupath);
			Dialogs.showMessageDialog(title, report.summary());
		});
	}

	/**
	 * Send the annotations of the current image to Cytomine.
	 * @param qupath the QuPath instance
	 */
	public static void push(QuPathGUI qupath) {
		var sync = prepare(qupath);
		if (sync == null)
			return;
		var title = resources.getString("annotations.push");
		int removed = sync.countDeletedLocally();
		boolean deleteRemoved = removed > 0 && Dialogs.showConfirmDialog(title,
				String.format(resources.getString("annotations.push.delete"), removed));
		runInBackground(title, sync, s -> s.push(deleteRemoved), report -> {
			refresh(qupath);
			Dialogs.showMessageDialog(title, report.summary());
		});
	}

	private static CytomineAnnotationSync prepare(QuPathGUI qupath) {
		var imageData = qupath.getImageData();
		if (imageData == null) {
			Dialogs.showErrorMessage(resources.getString("error"), resources.getString("annotations.noimage"));
			return null;
		}
		var uri = imageData.getServer().getURIs().stream().filter(CytomineUrls::isImageUri).findFirst().orElse(null);
		if (uri == null) {
			Dialogs.showErrorMessage(resources.getString("error"), resources.getString("annotations.notcytomine"));
			return null;
		}
		var client = CytomineClients.getOrLogin(CytomineUrls.coreUrl(uri)).orElse(null);
		if (client == null)
			return null;
		try {
			return new CytomineAnnotationSync(imageData, client);
		} catch (Exception e) {
			logger.error("Unable to prepare the synchronization", e);
			Dialogs.showErrorMessage(resources.getString("error"), e.getLocalizedMessage());
			return null;
		}
	}

	private interface Work<T> {
		T run(CytomineAnnotationSync sync) throws Exception;
	}

	private static <T> void runInBackground(String title, CytomineAnnotationSync sync, Work<T> work, Consumer<T> onSuccess) {
		Callable<T> callable = () -> work.run(sync);
		var task = new Task<T>() {
			@Override
			protected T call() throws Exception {
				return callable.call();
			}
		};
		task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
		task.setOnFailed(e -> {
			logger.error("{} failed", title, task.getException());
			Dialogs.showErrorMessage(title, task.getException().getLocalizedMessage());
		});
		var thread = new Thread(task, "cytomine-annotations");
		thread.setDaemon(true);
		thread.start();
	}

	private static void refresh(QuPathGUI qupath) {
		var viewer = qupath.getViewer();
		if (viewer != null && viewer.getHierarchy() != null)
			viewer.getHierarchy().fireHierarchyChangedEvent(AnnotationCommands.class);
	}
}
