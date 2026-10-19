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

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cytomine.project.CytomineProjectSync;
import qupath.ext.cytomine.project.CytomineProjectSync.Link;
import qupath.ext.cytomine.project.CytomineProjectSync.Result;
import qupath.ext.cytomine.client.CytomineClient;
import qupath.ext.cytomine.client.CytomineClients;
import qupath.ext.cytomine.client.CytomineObjects;
import qupath.ext.cytomine.client.CytomineObjects.ImageInstance;
import qupath.ext.cytomine.client.CytomineObjects.Project;
import qupath.ext.cytomine.client.CytomineUrls;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.ProjectCommands;

import java.io.IOException;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Window to browse the projects and images of a Cytomine server, and open them in QuPath.
 */
public class CytomineBrowser {

	private static final Logger logger = LoggerFactory.getLogger(CytomineBrowser.class);
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.cytomine.ui.strings");

	private final QuPathGUI qupath;
	private final Stage stage = new Stage();

	private final Label serverLabel = new Label();
	private final Label statusLabel = new Label();
	private final FilteredList<Project> projects = new FilteredList<>(FXCollections.observableArrayList());
	private final FilteredList<ImageInstance> images = new FilteredList<>(FXCollections.observableArrayList());
	private final ListView<Project> projectList = new ListView<>(projects);
	private final ListView<ImageInstance> imageList = new ListView<>(images);

	private CytomineClient client;

	/**
	 * Create the browser.
	 * @param qupath the QuPath instance in which images will be opened
	 */
	public CytomineBrowser(QuPathGUI qupath) {
		this.qupath = qupath;
		buildUi();
	}

	/**
	 * Show the window, prompting to log in if necessary.
	 */
	public void show() {
		if (client == null)
			connect();
		stage.show();
		stage.toFront();
	}

	private void connect() {
		var newClient = CytomineClients.getOrLogin(null).orElse(null);
		if (newClient == null)
			return;
		client = newClient;
		serverLabel.setText(client.getUsername() + " @ " + client.getCoreUri());
		loadProjects();
	}

	private void buildUi() {
		stage.initOwner(qupath.getStage());
		stage.setTitle(resources.getString("browser.title"));

		var connectButton = new Button(resources.getString("browser.connect"));
		connectButton.setOnAction(e -> connect());
		var logoutButton = new Button(resources.getString("browser.logout"));
		logoutButton.setOnAction(e -> logout());
		var top = new HBox(10, serverLabel, connectButton, logoutButton);
		top.setPadding(new Insets(10));
		HBox.setHgrow(serverLabel, Priority.ALWAYS);
		serverLabel.setMaxWidth(Double.MAX_VALUE);

		projectList.setCellFactory(v -> new ListCell<>() {
			@Override
			protected void updateItem(Project item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : item.name());
			}
		});
		projectList.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> loadImages(n));

		imageList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		imageList.setCellFactory(v -> new ListCell<>() {
			@Override
			protected void updateItem(ImageInstance item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : item.name() + "  (" + item.width() + " x " + item.height() + ")");
			}
		});
		imageList.setOnMouseClicked(e -> {
			if (e.getClickCount() == 2)
				openSelected();
		});

		var projectFilter = new TextField();
		projectFilter.setPromptText(resources.getString("browser.filter"));
		projectFilter.textProperty().addListener((v, o, n) -> projects.setPredicate(
				p -> n == null || n.isBlank() || p.name().toLowerCase().contains(n.strip().toLowerCase())));
		var imageFilter = new TextField();
		imageFilter.setPromptText(resources.getString("browser.filter"));
		imageFilter.textProperty().addListener((v, o, n) -> images.setPredicate(
				i -> n == null || n.isBlank() || i.name().toLowerCase().contains(n.strip().toLowerCase())));

		var newProjectButton = new Button(resources.getString("browser.project.new"));
		newProjectButton.setOnAction(e -> createQuPathProject());
		newProjectButton.disableProperty().bind(projectList.getSelectionModel().selectedItemProperty().isNull());
		var syncButton = new Button(resources.getString("browser.project.sync"));
		syncButton.setOnAction(e -> syncQuPathProject());
		var projectButtons = new HBox(10, newProjectButton, syncButton);

		var left = new VBox(5, new Label(resources.getString("browser.projects")), projectFilter, projectList, projectButtons);
		var right = new VBox(5, new Label(resources.getString("browser.images")), imageFilter, imageList);
		VBox.setVgrow(projectList, Priority.ALWAYS);
		VBox.setVgrow(imageList, Priority.ALWAYS);
		left.setPadding(new Insets(0, 5, 0, 10));
		right.setPadding(new Insets(0, 10, 0, 5));
		var split = new SplitPane(left, right);
		split.setOrientation(Orientation.HORIZONTAL);
		split.setDividerPositions(0.35);

		var openButton = new Button(resources.getString("browser.open"));
		openButton.setOnAction(e -> openSelected());
		openButton.disableProperty().bind(Bindings.size(imageList.getSelectionModel().getSelectedItems()).isNotEqualTo(1));
		var importButton = new Button(resources.getString("browser.import"));
		importButton.setOnAction(e -> importSelected());
		importButton.disableProperty().bind(imageList.getSelectionModel().selectedItemProperty().isNull());
		var bottom = new HBox(10, statusLabel, openButton, importButton);
		bottom.setPadding(new Insets(10));
		HBox.setHgrow(statusLabel, Priority.ALWAYS);
		statusLabel.setMaxWidth(Double.MAX_VALUE);

		var root = new BorderPane(split);
		root.setTop(top);
		root.setBottom(bottom);
		stage.setScene(new Scene(root, 760, 520));
	}

	private void logout() {
		if (client != null)
			CytomineClients.remove(client.getCoreUri().toString());
		client = null;
		serverLabel.setText("");
		projects.getSource().clear();
		images.getSource().clear();
	}

	private void loadProjects() {
		var c = client;
		images.getSource().clear();
		runInBackground(() -> CytomineObjects.listProjects(c), list -> {
			list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
			setItems(projects, list);
			statusLabel.setText(String.format(resources.getString("browser.projects.count"), list.size()));
		});
	}

	private void loadImages(Project project) {
		if (project == null || client == null)
			return;
		var c = client;
		images.getSource().clear();
		runInBackground(() -> CytomineObjects.listImages(c, project.id()), list -> {
			list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
			setItems(images, list);
			statusLabel.setText(String.format(resources.getString("browser.images.count"), list.size()));
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> void setItems(FilteredList<T> list, List<T> items) {
		((javafx.collections.ObservableList<T>) list.getSource()).setAll(items);
	}

	private void openSelected() {
		var selected = imageList.getSelectionModel().getSelectedItem();
		if (selected == null || client == null)
			return;
		var uri = CytomineUrls.imageUri(client.getCoreUri().toString(), selected.id());
		try {
			qupath.openImage(qupath.getViewer(), uri.toString(), true, true);
		} catch (IOException e) {
			Dialogs.showErrorMessage(resources.getString("error"), e.getLocalizedMessage());
			logger.error("Unable to open image {}", uri, e);
		}
	}

	private void createQuPathProject() {
		var selected = projectList.getSelectionModel().getSelectedItem();
		if (selected == null || client == null)
			return;
		var chooser = new DirectoryChooser();
		chooser.setTitle(resources.getString("browser.project.folder"));
		var dir = chooser.showDialog(stage);
		if (dir == null)
			return;
		var c = client;
		var link = new Link(c.getCoreUri().toString(), selected.id(), selected.name());
		runInBackground(() -> {
			var project = CytomineProjectSync.createProject(dir);
			var result = CytomineProjectSync.sync(project, c, link, false);
			return new Object[]{project, result};
		}, out -> {
			@SuppressWarnings("unchecked")
			var project = (qupath.lib.projects.Project<java.awt.image.BufferedImage>) out[0];
			qupath.setProject(project);
			statusLabel.setText(describe((Result) out[1]));
		});
	}

	private void syncQuPathProject() {
		var project = qupath.getProject();
		if (project == null) {
			Dialogs.showErrorMessage(resources.getString("error"), resources.getString("browser.project.none"));
			return;
		}
		var link = CytomineProjectSync.getLink(project).orElse(null);
		if (link == null) {
			var selected = projectList.getSelectionModel().getSelectedItem();
			if (selected == null || client == null) {
				Dialogs.showErrorMessage(resources.getString("error"), resources.getString("browser.project.unlinked"));
				return;
			}
			link = new Link(client.getCoreUri().toString(), selected.id(), selected.name());
			if (!Dialogs.showConfirmDialog(resources.getString("browser.project.sync"),
					String.format(resources.getString("browser.project.link"), project.getName(), link)))
				return;
		}
		var syncLink = link;
		var c = CytomineClients.getOrLogin(syncLink.coreUrl()).orElse(null);
		if (c == null)
			return;
		runInBackground(() -> CytomineProjectSync.findMissing(project, c, syncLink), missing -> {
			boolean remove = !missing.isEmpty() && Dialogs.showConfirmDialog(resources.getString("browser.project.sync"),
					String.format(resources.getString("browser.project.remove"), missing.size()));
			runInBackground(() -> CytomineProjectSync.sync(project, c, syncLink, remove), result -> {
				qupath.refreshProject();
				statusLabel.setText(describe(result));
			});
		});
	}

	private static String describe(Result result) {
		return String.format(resources.getString("browser.project.result"),
				result.added().size(), result.renamed(), result.removed(), result.missing().size());
	}

	private void importSelected() {
		if (client == null)
			return;
		if (qupath.getProject() == null) {
			Dialogs.showErrorMessage(resources.getString("error"), resources.getString("browser.import.noproject"));
			return;
		}
		var core = client.getCoreUri().toString();
		var uris = imageList.getSelectionModel().getSelectedItems().stream()
				.map(i -> CytomineUrls.imageUri(core, i.id()).toString())
				.toArray(String[]::new);
		ProjectCommands.promptToImportImages(qupath, uris);
	}

	private <T> void runInBackground(Callable<T> work, Consumer<T> onSuccess) {
		statusLabel.setText(resources.getString("browser.loading"));
		var task = new Task<T>() {
			@Override
			protected T call() throws Exception {
				return work.call();
			}
		};
		task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
		task.setOnFailed(e -> {
			var ex = task.getException();
			logger.error("Cytomine request failed", ex);
			statusLabel.setText(ex.getLocalizedMessage());
		});
		var thread = new Thread(task, "cytomine-request");
		thread.setDaemon(true);
		thread.start();
	}
}
