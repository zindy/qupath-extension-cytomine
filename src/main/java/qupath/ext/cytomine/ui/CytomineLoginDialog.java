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
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.event.ActionEvent;
import qupath.ext.cytomine.client.CytomineClient;
import qupath.ext.cytomine.client.CytomineClients;
import qupath.ext.cytomine.client.CytomineUrls;
import qupath.lib.gui.QuPathGUI;

import java.io.IOException;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Dialog to log in to a Cytomine server. The password is only held in the password field and in a char array that is
 * cleared once the login attempt has completed.
 */
public final class CytomineLoginDialog {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.cytomine.ui.strings");

	private CytomineLoginDialog() {}

	/**
	 * Show the dialog. Must be called on the JavaFX application thread.
	 * @param serverUrl the server to prefill, or null to use the last server
	 * @return the logged-in client (already registered in {@link CytomineClients}), or empty if cancelled
	 */
	public static Optional<CytomineClient> prompt(String serverUrl) {
		var server = serverUrl == null || serverUrl.isBlank() ? CytomineClients.getLastServer() : serverUrl;

		var serverField = new TextField(server);
		serverField.setPromptText("http://127.0.0.1");
		var iamField = new TextField(CytomineClients.getSavedIamUrl(server));
		iamField.setPromptText(resources.getString("login.iam.prompt"));
		var userField = new TextField(CytomineClients.getLastUsername());
		var passwordField = new PasswordField();
		var errorLabel = new Label();
		errorLabel.setStyle("-fx-text-fill: red;");
		errorLabel.setWrapText(true);

		var grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.setPadding(new Insets(10));
		grid.addRow(0, new Label(resources.getString("login.server")), serverField);
		grid.addRow(1, new Label(resources.getString("login.iam")), iamField);
		grid.addRow(2, new Label(resources.getString("login.username")), userField);
		grid.addRow(3, new Label(resources.getString("login.password")), passwordField);
		grid.add(errorLabel, 0, 4, 2, 1);
		GridPane.setHgrow(serverField, Priority.ALWAYS);
		serverField.setPrefColumnCount(28);

		var dialog = new Dialog<CytomineClient>();
		var qupath = QuPathGUI.getInstance();
		if (qupath != null)
			dialog.initOwner(qupath.getStage());
		dialog.setTitle(resources.getString("login.title"));
		dialog.getDialogPane().setContent(grid);
		var loginType = new ButtonType(resources.getString("login.button"), ButtonBar.ButtonData.OK_DONE);
		dialog.getDialogPane().getButtonTypes().addAll(loginType, ButtonType.CANCEL);
		var loginButton = dialog.getDialogPane().lookupButton(loginType);

		loginButton.addEventFilter(ActionEvent.ACTION, event -> {
			// Perform the login in the background and only close the dialog on success
			event.consume();
			errorLabel.setText("");
			grid.setDisable(true);
			loginButton.setDisable(true);
			var serverText = serverField.getText().strip();
			var iamText = iamField.getText().strip();
			var userText = userField.getText().strip();
			var password = passwordField.getText().toCharArray();
			var task = new Task<CytomineClient>() {
				@Override
				protected CytomineClient call() throws Exception {
					return login(serverText, iamText, userText, password);
				}
			};
			task.setOnSucceeded(e -> {
				CytomineClients.add(task.getValue());
				dialog.setResult(task.getValue());
				dialog.close();
			});
			task.setOnFailed(e -> {
				errorLabel.setText(task.getException().getLocalizedMessage());
				grid.setDisable(false);
				loginButton.setDisable(false);
				passwordField.requestFocus();
			});
			var thread = new Thread(task, "cytomine-login");
			thread.setDaemon(true);
			thread.start();
		});

		Platform.runLater(() -> (userField.getText().isEmpty() ? userField : passwordField).requestFocus());
		return dialog.showAndWait();
	}

	private static CytomineClient login(String server, String iam, String username, char[] password) throws IOException {
		try {
			if (server.isEmpty() || username.isEmpty() || password.length == 0)
				throw new IOException(resources.getString("login.error.missing"));
			String core = CytomineUrls.normalizeCoreUrl(server);
			if (iam.isEmpty()) {
				// Ask the web interface (which serves the configuration of the instance)
				var base = server.replaceAll("/+$", "").replaceAll("/api$", "");
				var endpoints = CytomineUrls.discover(base).orElse(null);
				if (endpoints == null)
					throw new IOException(resources.getString("login.error.iam"));
				core = endpoints.coreUrl();
				iam = endpoints.iamUrl();
			}
			return CytomineClient.login(core, iam, username, password);
		} finally {
			java.util.Arrays.fill(password, '\0');
		}
	}
}
