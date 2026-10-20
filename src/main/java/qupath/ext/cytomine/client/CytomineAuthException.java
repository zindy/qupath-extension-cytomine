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

import java.io.IOException;

/**
 * Thrown when a Cytomine session could not be established or renewed.
 * <p>
 * The message is meant to be shown to the user as is: it says whether the server refused the credentials or could not
 * be reached (or failed) and, in the latter case, how many times a new login was attempted.
 */
public class CytomineAuthException extends IOException {

	private static final long serialVersionUID = 1L;

	private final boolean credentialsRejected;

	/**
	 * @param message description of the problem
	 * @param credentialsRejected true if the server refused the credentials (or there are no credentials left to try),
	 *                            false if the login failed for another reason, e.g. a server error
	 */
	public CytomineAuthException(String message, boolean credentialsRejected) {
		super(message);
		this.credentialsRejected = credentialsRejected;
	}

	/**
	 * @param message description of the problem
	 * @param credentialsRejected see {@link #CytomineAuthException(String, boolean)}
	 * @param cause the underlying failure
	 */
	public CytomineAuthException(String message, boolean credentialsRejected, Throwable cause) {
		super(message, cause);
		this.credentialsRejected = credentialsRejected;
	}

	/**
	 * @return true if the user has to log in again, because the server refused the credentials or because they are no
	 * longer available. Retrying later will not help in that case.
	 */
	public boolean isCredentialsRejected() {
		return credentialsRejected;
	}
}
