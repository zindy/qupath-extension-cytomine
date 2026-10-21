# QuPath Cytomine extension

Open and manage images stored in a [Cytomine](https://cytomine.org) server from [QuPath](https://qupath.github.io) 0.7+.

## Features

- Log in with your Cytomine account (Keycloak). The password and tokens are never stored on disk; only the server URL and
  the last username are remembered. See [Sessions and passwords](#sessions-and-passwords).
- Browse projects and images (*Extensions > Cytomine > Browse server...*) and open them in QuPath.
- RGB images (e.g. brightfield slides) and multichannel images (e.g. fluorescence), including z-stacks and time series.
- One QuPath project per Cytomine project: *New QuPath project...* creates a QuPath project with one entry per
Cytomine image, and *Sync QuPath project* adds new Cytomine images, follows renames, and (after confirmation) removes
entries whose image was deleted from Cytomine. Existing entries and their annotations are kept.
- Two-way annotation sync for the image in the viewer (*Extensions > Cytomine > Pull / Push annotations*). Your
  annotations can be edited in QuPath and pushed back; other users' annotations are pulled as locked objects named after
  their author. Ontology terms become QuPath classes, and new classes are created as terms. If an annotation changed on
  both sides it is left untouched and reported; annotations deleted in QuPath are only deleted in Cytomine after
  confirmation.

## Usage

Enter the URL of the Cytomine web interface (e.g. `http://127.0.0.1`). The extension reads the API and Keycloak URLs from
its `/configuration.json`; they can also be entered explicitly (e.g. `http://127.0.0.1:8100/iam/realms/cytomine`).

## Sessions and passwords

You log in once per server and QuPath session: opening the images of a synchronized project, *Browse server...* and the
annotation commands all share that login. *Connect...* in the server browser always asks for credentials, to log in to
another server or as another user.

- **Automatic reconnection.** While you are logged in, your password is kept in memory only (never written to disk or to
  the logs) so that the session can be renewed without asking again when it times out or is closed by the server.
  Requests refused with HTTP 401 are repeated once after renewing the session.
- **Errors.** If the session cannot be renewed, a message explains why: the server refused the password (for example
  because it was changed, in which case it is not retried, to avoid locking the account), or the authentication server
  is unreachable or failing (retried 3 times, with the HTTP status in the message).
- **Logging out** erases the password and ends the session on the server. This also applies to images that are still
  open, which report a login error until you log in again. Logging in again as the same user restores them.

## Build

```
./gradlew build
```

The jar is created in `build/libs`. Drag it onto QuPath to install it.

Tests that need a running Cytomine are skipped unless `CYTOMINE_USER` and `CYTOMINE_PASSWORD` are set
(`CYTOMINE_URL` defaults to `http://127.0.0.1`). The session handling (renewal, retries, logout) is tested against a
fake server and always runs.

## License

Copyright (C) 2026 Egor Zindy. This extension is free software, released under the [GNU General Public License v3.0](LICENSE) or (at your option) any later version.
