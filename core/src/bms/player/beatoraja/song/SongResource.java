package bms.player.beatoraja.song;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 楽曲に属する読み取り専用リソース。
 * ローカルファイル、アーカイブ内エントリ、またはリモートファイルを表す。
 */
public interface SongResource {

	SongResource parent();

	SongResource resolve(String relativePath);

	String name();

	String displayPath();

	/** 音声・画像キャッシュで使用される安定した識別キーを返す。 */
	String cacheKey();

	boolean exists() throws IOException;

	boolean isDirectory() throws IOException;

	long size() throws IOException;

	InputStream openStream() throws IOException;

	List<SongResource> list() throws IOException;

	/** 直接利用可能なローカルパスが存在する場合はそれを返す。 */
	default Optional<Path> localPath() {
		return Optional.empty();
	}

	/**
	 * サードパーティAPIが {@link Path} のみを受け付ける場合に単一ファイルのキャッシュエントリを作成する。
	 * アーカイブ全体を展開してはならない。
	 */
	default Path materialize() throws IOException {
		return SongResources.materialize(this);
	}
}

