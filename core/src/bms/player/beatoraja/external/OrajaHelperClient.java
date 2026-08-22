package bms.player.beatoraja.external;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

import bms.model.Mode;
import bms.player.beatoraja.ClearType;
import bms.player.beatoraja.ReplayData;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.pattern.Random;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.stream.NamedPipeSender;

public final class OrajaHelperClient {
	private static final String PIPE_NAME = "oraja_helper";
	private static final NamedPipeSender SENDER = new NamedPipeSender(PIPE_NAME);
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "oraja-helper-pipe-sender");
		thread.setDaemon(true);
		return thread;
	});
	private OrajaHelperClient() {
	}

	public static void sendSelect(SongData song) {
		sendSelect(song, null, null, null);
	}

	public static void sendSelect(SongData song, Mode mode, ScoreData bestScore, ScoreData minBpScore) {
		Map<String, Object> payload = basePayload("song_select", "select", song);
		addSelectScorePayload(payload, song, mode, bestScore, minBpScore);
		send(payload);
	}

	public static void sendPlay(SongData song, ReplayData replay, Mode mode) {
		Map<String, Object> payload = basePayload("song_play", "play", song);
		addOption(payload, replay, mode);
		send(payload);
	}

	public static void sendPlayEnd(SongData song, ReplayData replay, Mode mode, ScoreData score, int playedNotes,
			int totalNotes, long elapsedSeconds, boolean quickRetry) {
		Map<String, Object> payload = basePayload("song_play_end", "play", song);
		addOption(payload, replay, mode);
		payload.put("playEndMetrics", true);
		payload.put("playedNotes", playedNotes);
		payload.put("totalNotes", totalNotes);
		payload.put("elapsedSeconds", elapsedSeconds);
		payload.put("quickRetry", quickRetry);
		if (score != null) {
			payload.put("judges", judgePayload(score));
		}
		send(payload);
	}

	public static void sendResult(SongData song, ReplayData replay, Mode mode, ScoreData score) {
		if (score == null) {
			return;
		}
		Map<String, Object> payload = basePayload("song_result", "result", song);
		addOption(payload, replay, mode);
		payload.put("score", score.getExscore());
		payload.put("scoreRate", scoreRate(score, song));
		payload.put("clearLamp", ClearType.getClearTypeByID(score.getClear()).name());
		payload.put("clearLampId", score.getClear());
		payload.put("missCount", score.getMinbp());
		payload.put("judges", judgePayload(score));
		send(payload);
	}

	private static Map<String, Object> basePayload(String event, String scene, SongData song) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("event", event);
		payload.put("scene", scene);
		payload.put("title", song != null ? song.getFullTitle() : "");
		payload.put("artist", song != null ? song.getFullArtist() : "");
		payload.put("sha256", song != null ? song.getSha256() : "");
		payload.put("md5", song != null ? song.getMd5() : "");
		return payload;
	}

	private static void addOption(Map<String, Object> payload, ReplayData replay, Mode mode) {
		if (replay == null || mode == null) {
			return;
		}
		Random option = Random.getRandom(replay.randomoption, mode);
		payload.put("option", optionName(option));
		payload.put("optionId", replay.randomoption);
		payload.put("randomSeed", replay.randomoptionseed);
		payload.put("randomPlacement", lanePlacement(replay.laneShufflePattern, 0, mode));
		if (mode.player == 2) {
			Random option2p = Random.getRandom(replay.randomoption2, mode);
			payload.put("option2P", optionName(option2p));
			payload.put("option2PId", replay.randomoption2);
			payload.put("randomSeed2P", replay.randomoption2seed);
			payload.put("randomPlacement2P", lanePlacement(replay.laneShufflePattern, 1, mode));
			payload.put("doubleOption", replay.doubleoption);
		}
	}

	private static Map<String, Object> judgePayload(ScoreData score) {
		Map<String, Object> judges = new LinkedHashMap<>();
		judges.put("epg", score.getEpg());
		judges.put("lpg", score.getLpg());
		judges.put("egr", score.getEgr());
		judges.put("lgr", score.getLgr());
		judges.put("egd", score.getEgd());
		judges.put("lgd", score.getLgd());
		judges.put("ebd", score.getEbd());
		judges.put("lbd", score.getLbd());
		judges.put("epr", score.getEpr());
		judges.put("lpr", score.getLpr());
		judges.put("ems", score.getEms());
		judges.put("lms", score.getLms());
		return judges;
	}

	private static void addSelectScorePayload(Map<String, Object> payload, SongData song, Mode mode,
			ScoreData bestScore, ScoreData minBpScore) {
		Map<String, Object> bestPayload = bestScore != null ? scorePayload(bestScore, song, mode) : null;
		Map<String, Object> minBpPayload = minBpScore != null ? scorePayload(minBpScore, song, mode) : null;
		if (bestPayload != null) {
			payload.put("scoreBest", bestPayload);
		}
		if (minBpPayload != null) {
			payload.put("minBpBest", minBpPayload);
		}
		if (bestPayload != null && minBpPayload != null && !sameScoreEntry(bestScore, minBpScore)) {
			payload.put("scores", List.of(bestPayload, minBpPayload));
		} else if (bestPayload != null) {
			payload.put("scores", List.of(bestPayload));
		} else if (minBpPayload != null) {
			payload.put("scores", List.of(minBpPayload));
		}
	}

	private static Map<String, Object> scorePayload(ScoreData score, SongData song, Mode mode) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("score", score.getExscore());
		payload.put("scoreRate", scoreRate(score, song));
		payload.put("clearLamp", ClearType.getClearTypeByID(score.getClear()).name());
		payload.put("clearLampId", score.getClear());
		payload.put("missCount", score.getMinbp());
		payload.put("date", score.getDate());
		payload.put("option", scoreOptionName(score, mode));
		payload.put("optionId", score.getOption());
		payload.put("randomSeed", score.getSeed());
		payload.put("randomPlacement", score.getRandom() > 0 ? String.valueOf(score.getRandom()) : "");
		payload.put("judges", judgePayload(score));
		return payload;
	}

	private static boolean sameScoreEntry(ScoreData score1, ScoreData score2) {
		return score1.getDate() == score2.getDate() && score1.getExscore() == score2.getExscore()
				&& score1.getMinbp() == score2.getMinbp() && score1.getOption() == score2.getOption();
	}

	private static float scoreRate(ScoreData score, SongData song) {
		int notes = song != null ? song.getNotes() : 0;
		return notes > 0 ? score.getExscore() * 100.0f / (notes * 2.0f) : 0.0f;
	}

	private static String lanePlacement(int[][] patterns, int player, Mode mode) {
		if (patterns == null || player < 0 || player >= patterns.length || patterns[player] == null) {
			return "";
		}
		int[] pattern = patterns[player];
		int length = pattern.length;
		if (mode.scratchKey.length > player && length > 0 && pattern[length - 1] == mode.scratchKey[player]) {
			length--;
		}
		StringBuilder placement = new StringBuilder();
		for (int i = 0; i < length; i++) {
			placement.append(pattern[i] + 1);
		}
		return placement.toString();
	}

	private static String optionName(Random option) {
		return switch (option) {
			case IDENTITY -> "正規";
			case ROTATE -> "R-RANDOM";
			case S_RANDOM -> "S-RANDOM";
			case H_RANDOM -> "H-RANDOM";
			case ALL_SCR -> "ALL-SCR";
			case MIRROR_EX -> "MIRROR-EX";
			case RANDOM_EX -> "RANDOM-EX";
			case ROTATE_EX -> "R-RANDOM-EX";
			case S_RANDOM_EX -> "S-RANDOM-EX";
			case S_RANDOM_NO_THRESHOLD -> "S-RANDOM-NO-THRESHOLD";
			case RANDOM_PLAYABLE -> "RANDOM-PLAYABLE";
			case S_RANDOM_PLAYABLE -> "S-RANDOM-PLAYABLE";
			default -> option.name().replace('_', '-');
		};
	}

	private static void send(Map<String, Object> payload) {
		String body = toJson(payload);
		dumpPayload(body);
		EXECUTOR.execute(() -> SENDER.sendLine(body));
	}

	private static String toJson(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof String string) {
			return "\"" + escapeJson(string) + "\"";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return String.valueOf(value);
		}
		if (value instanceof Map<?, ?> map) {
			StringBuilder builder = new StringBuilder();
			builder.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					builder.append(',');
				}
				builder.append(toJson(String.valueOf(entry.getKey()))).append(':').append(toJson(entry.getValue()));
				first = false;
			}
			builder.append('}');
			return builder.toString();
		}
		if (value instanceof Iterable<?> iterable) {
			StringBuilder builder = new StringBuilder();
			builder.append('[');
			boolean first = true;
			for (Object item : iterable) {
				if (!first) {
					builder.append(',');
				}
				builder.append(toJson(item));
				first = false;
			}
			builder.append(']');
			return builder.toString();
		}
		return "\"" + escapeJson(String.valueOf(value)) + "\"";
	}

	private static String scoreOptionName(ScoreData score, Mode mode) {
		if (mode == null) {
			return "";
		}
		Random option = Random.getRandom(score.getOption() % 10, mode);
		String name = option != null ? optionName(option) : "";
		String placement = score.getRandom() > 0 ? String.valueOf(score.getRandom()) : "";
		return placement.length() > 0 ? name + " " + placement : name;
	}

	private static void dumpPayload(String body) {
		try {
			Path logDir = Path.of("log");
			Files.createDirectories(logDir);
			String line = "{\"time\":\"" + escapeJson(LocalDateTime.now().toString()) + "\",\"payload\":\""
					+ escapeJson(body) + "\"}\n";
			Files.writeString(logDir.resolve("oraja_helper_payload.jsonl"), line, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}

	private static String escapeJson(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder escaped = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"' -> escaped.append("\\\"");
			case '\\' -> escaped.append("\\\\");
			case '\b' -> escaped.append("\\b");
			case '\f' -> escaped.append("\\f");
			case '\n' -> escaped.append("\\n");
			case '\r' -> escaped.append("\\r");
			case '\t' -> escaped.append("\\t");
			default -> {
				if (c < 0x20) {
					escaped.append(String.format("\\u%04x", (int) c));
				} else {
					escaped.append(c);
				}
			}
			}
		}
		return escaped.toString();
	}
}
