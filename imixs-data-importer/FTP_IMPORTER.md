# The FTP Importer

The CDI bean `FTPImporterService` implements a FTP Document importer. It enables automated file import from **FTP**, **FTPS**, or **SFTP** servers and creates a new workitem in the workflow engine for each imported file. The service reacts to the `DocumentImportEvent`.

## ⚙️ Configuration

The import process is triggered by a `DocumentImportEvent`.
The corresponding data source (`ItemCollection`) must contain the following fields:

| Field      | Description                                                              |
| ---------- | ------------------------------------------------------------------------ |
| `type`     | `FTP`                                                                    |
| `server`   | Hostname or IP address of the server                                     |
| `port`     | (optional) Port number — defaults to `21` for FTP/FTPS and `22` for SFTP |
| `user`     | Username                                                                 |
| `password` | Password                                                                 |
| `selector` | Directory path on the server (e.g. `/incoming/`)                         |
| `options`  | Optional properties (see below)                                          |

## 🧩 Optional Properties (`options`)

The following `options` can be provided to use FTP sub protocols.

| Option            | Description                                          | Default |
| ----------------- | ---------------------------------------------------- | ------- |
| `ftp.subprotocol` | Defines the subprotocol: `FTP`, `FTPS`, or `SFTP`    | `FTP`   |
| `ftp.insecure`    | If `true`, disables host key verification (for SFTP) | `false` |

**Example:**

```
ftp.subprotocol=SFTP
ftp.insecure=true
```

### 🔒 Security Notes

- The **insecure mode (`ftp.insecure=true`)** accepts all SSH host keys and should be used **for testing only**.
- In production, host key verification via `ssh.loadKnownHosts()` should always remain enabled.
