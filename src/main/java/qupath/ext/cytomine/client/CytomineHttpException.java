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
 * Thrown when the Cytomine core API answers a request with an HTTP error status.
 * <p>
 * It makes it possible to tell, for example, an annotation that does not exist any more (404) from a server that is
 * having problems (5xx). Authentication problems are reported with a {@link CytomineAuthException} instead.
 */
public class CytomineHttpException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int statusCode;

	/**
	 * @param statusCode the HTTP status code
	 * @param message description of the problem
	 */
	public CytomineHttpException(int statusCode, String message) {
		super(message);
		this.statusCode = statusCode;
	}

	/**
	 * @return the HTTP status code returned by the server
	 */
	public int getStatusCode() {
		return statusCode;
	}
}
