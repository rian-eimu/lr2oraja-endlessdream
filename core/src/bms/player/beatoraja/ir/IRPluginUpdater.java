package bms.player.beatoraja.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Updates external IR plugin jars before IRConnectionManager loads them.
 */
public final class IRPluginUpdater {
	private static final Logger logger = LoggerFactory.getLogger(IRPluginUpdater.class);

	private static final String DEFAULT_MANIFEST_URL =
			"https://github.com/rian-eimu/lr2oraja-endlessdream/releases/latest/download/ir-manifest.json";
	private static final String ENABLED_PROPERTY = "irPluginUpdateEnabled";
	private static final String MANIFEST_URL_PROPERTY = "irPluginUpdateManifestUrl";
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int READ_TIMEOUT_MS = 15000;

	private IRPluginUpdater() {
	}

	public static void updateBeforeIRLoad() {
		Path irDirectory = resolveIRDirectory();
		if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
			logger.info("[IRPluginUpdater] IR plugin auto update is disabled");
			activateExternalIRDirectory(irDirectory);
			return;
		}

		String manifestUrl = System.getProperty(MANIFEST_URL_PROPERTY, DEFAULT_MANIFEST_URL);
		if (manifestUrl == null || manifestUrl.isBlank()) {
			logger.info("[IRPluginUpdater] IR plugin manifest url is empty");
			activateExternalIRDirectory(irDirectory);
			return;
		}

		try {
			new IRPluginUpdater().update(irDirectory, manifestUrl);
		} catch (Exception e) {
			logger.warn("[IRPluginUpdater] Failed to update IR plugins: {}", e.getMessage());
		} finally {
			activateExternalIRDirectory(irDirectory);
		}
	}

	private static Path resolveIRDirectory() {
		String customIRDirectory = System.getProperty("customIRDirectory");
		if (customIRDirectory != null && !customIRDirectory.isBlank()) {
			return Path.of(customIRDirectory);
		}
		return Path.of("ir");
	}

	private static void activateExternalIRDirectory(Path irDirectory) {
		if (System.getProperty("customIRDirectory") != null || !containsJar(irDirectory)) {
			return;
		}
		String absolutePath = irDirectory.toAbsolutePath().normalize().toString();
		System.setProperty("customIRDirectory", absolutePath);
		logger.info("[IRPluginUpdater] Loading IR plugins from {}", absolutePath);
	}

	private static boolean containsJar(Path irDirectory) {
		if (!Files.isDirectory(irDirectory)) {
			return false;
		}
		try (var files = Files.list(irDirectory)) {
			return files.anyMatch(path -> Files.isRegularFile(path)
					&& path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"));
		} catch (IOException e) {
			logger.warn("[IRPluginUpdater] Failed to scan IR plugin directory: {}", e.getMessage());
			return false;
		}
	}

	private void update(Path irDirectory, String manifestUrl) throws IOException {
		IRManifest manifest = readManifest(manifestUrl);
		if (manifest.files == null || manifest.files.isEmpty()) {
			logger.info("[IRPluginUpdater] IR plugin manifest has no files");
			return;
		}

		Files.createDirectories(irDirectory);
		Files.createDirectories(irDirectory.resolve(".tmp"));
		Files.createDirectories(irDirectory.resolve(".backup"));

		int updated = 0;
		for (IRManifestFile file : manifest.files) {
			try {
				if (updateFile(irDirectory, file)) {
					updated++;
				}
			} catch (Exception e) {
				String fileName = file != null ? file.name : "(null)";
				logger.warn("[IRPluginUpdater] Failed to update IR plugin file {}: {}", fileName, e.getMessage());
			}
		}

		if (updated > 0) {
			logger.info("[IRPluginUpdater] Updated {} IR plugin file(s)", updated);
		} else {
			logger.info("[IRPluginUpdater] IR plugins are already up to date");
		}
	}

	private IRManifest readManifest(String manifestUrl) throws IOException {
		HttpURLConnection conn = openConnection(manifestUrl);
		try (InputStream input = conn.getInputStream()) {
			ObjectMapper mapper = new ObjectMapper();
			IRManifest manifest = mapper.readValue(input, IRManifest.class);
			if (manifest == null) {
				throw new IOException("IR plugin manifest is empty");
			}
			return manifest;
		} finally {
			conn.disconnect();
		}
	}

	private boolean updateFile(Path irDirectory, IRManifestFile file) throws IOException {
		validateFileEntry(file);

		Path target = irDirectory.resolve(file.name).normalize();
		if (!target.startsWith(irDirectory.normalize())) {
			throw new IOException("IR plugin path escapes target directory: " + file.name);
		}

		String expectedSha256 = normalizeSha256(file.sha256);
		if (Files.isRegularFile(target) && expectedSha256.equals(sha256(target))) {
			return false;
		}

		Path tmp = irDirectory.resolve(".tmp").resolve(file.name + ".download");
		Files.createDirectories(tmp.getParent());
		download(file.url, tmp);

		String actualSha256 = sha256(tmp);
		if (!expectedSha256.equals(actualSha256)) {
			Files.deleteIfExists(tmp);
			throw new IOException("SHA-256 mismatch for " + file.name);
		}

		Path backup = backupExistingFile(irDirectory, target);
		try {
			moveReplacing(tmp, target);
		} catch (IOException e) {
			restoreBackup(backup, target);
			throw e;
		}
		deleteBackup(backup);
		logger.info("[IRPluginUpdater] Updated {} to {}", file.name, file.version);
		return true;
	}

	private void validateFileEntry(IRManifestFile file) throws IOException {
		List<String> missing = new ArrayList<>();
		if (file == null) {
			throw new IOException("IR plugin manifest contains null file entry");
		}
		if (file.name == null || file.name.isBlank()) {
			missing.add("name");
		}
		if (file.url == null || file.url.isBlank()) {
			missing.add("url");
		}
		if (file.sha256 == null || file.sha256.isBlank()) {
			missing.add("sha256");
		}
		if (!missing.isEmpty()) {
			throw new IOException("IR plugin manifest file entry is missing: " + String.join(", ", missing));
		}
		if (file.name.contains("/") || file.name.contains("\\") || file.name.equals(".") || file.name.equals("..")) {
			throw new IOException("Invalid IR plugin file name: " + file.name);
		}
		if (!file.name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			throw new IOException("IR plugin file must be a jar: " + file.name);
		}
		normalizeSha256(file.sha256);
	}

	private static String normalizeSha256(String sha256) throws IOException {
		String normalized = sha256.trim().toLowerCase(Locale.ROOT);
		if (!normalized.matches("[0-9a-f]{64}")) {
			throw new IOException("Invalid SHA-256 value");
		}
		return normalized;
	}

	private void download(String fileUrl, Path tmp) throws IOException {
		HttpURLConnection conn = openConnection(fileUrl);
		try (InputStream input = conn.getInputStream()) {
			Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			conn.disconnect();
		}
	}

	private HttpURLConnection openConnection(String urlString) throws IOException {
		URL url = new URL(urlString);
		if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) {
			throw new IOException("Unsupported URL protocol: " + url.getProtocol());
		}

		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
		conn.setReadTimeout(READ_TIMEOUT_MS);
		conn.setRequestProperty("User-Agent", "lr2oraja-endlessdream-ir-updater");
		conn.connect();

		int responseCode = conn.getResponseCode();
		if (responseCode != HttpURLConnection.HTTP_OK) {
			conn.disconnect();
			if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
				throw new FileNotFoundException("IR plugin update file not found: " + urlString);
			}
			throw new IOException("Unexpected HTTP response code " + responseCode + " for " + urlString);
		}
		return conn;
	}

	private Path backupExistingFile(Path irDirectory, Path target) throws IOException {
		if (!Files.isRegularFile(target)) {
			return null;
		}
		Path backup = irDirectory.resolve(".backup").resolve(target.getFileName().toString() + ".bak");
		moveReplacing(target, backup);
		return backup;
	}

	private void restoreBackup(Path backup, Path target) {
		if (backup == null || !Files.isRegularFile(backup)) {
			return;
		}
		try {
			moveReplacing(backup, target);
		} catch (IOException restoreError) {
			logger.warn("[IRPluginUpdater] Failed to restore backup {}: {}", backup, restoreError.getMessage());
		}
	}

	private void deleteBackup(Path backup) {
		if (backup == null) {
			return;
		}
		try {
			Files.deleteIfExists(backup);
		} catch (IOException e) {
			logger.warn("[IRPluginUpdater] Failed to delete backup file {}: {}", backup, e.getMessage());
		}
	}

	private void moveReplacing(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}
			byte[] hash = digest.digest();
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				String value = Integer.toHexString(0xff & b);
				if (value.length() == 1) {
					hex.append('0');
				}
				hex.append(value);
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IOException("Failed to calculate SHA-256 for " + path, e);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	static class IRManifest {
		public int schemaVersion;
		public String updatedAt;
		public List<IRManifestFile> files;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	static class IRManifestFile {
		public String name;
		public String version;
		public String url;
		public String sha256;
	}
}
