package bms.player.beatoraja.skin;

import bms.player.beatoraja.ShaderManager;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxNativesLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 複数ページかつページ毎にテクスチャサイズが異なる BitmapFont を描画した際の
 * TYPE_BILINEAR の u_textureSize 更新を検証する単体テスト
 */
@Isolated
class SkinObjectRendererFontPageSizeTest {

	private static final int TEXTURE_SIZE_UNIFORM_LOCATION = 700;

	private Application prevApp;
	private GL20 prevGl;
	private GL20 prevGl20;
	private Files prevFiles;
	private Graphics prevGraphics;
	private Application mockApp;
	private Map<String, ShaderProgram> savedShaders;

	private float currentUniformWidth;
	private float currentUniformHeight;
	private final List<FlushedBatchRecord> flushedRecords = new ArrayList<>();
	private int uniformUpdateCount;

	private record FlushedBatchRecord(int textureWidth, int textureHeight, float uniformWidth, float uniformHeight) {
	}

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() throws Exception {
		GdxNativesLoader.load();
		prevApp = Gdx.app;
		prevGl = Gdx.gl;
		prevGl20 = Gdx.gl20;
		prevFiles = Gdx.files;
		prevGraphics = Gdx.graphics;

		Field shadersField = ShaderManager.class.getDeclaredField("shaders");
		shadersField.setAccessible(true);
		Map<String, ShaderProgram> managerMap = (Map<String, ShaderProgram>) shadersField.get(null);
		savedShaders = new HashMap<>(managerMap);
		managerMap.clear();

		currentUniformWidth = -1f;
		currentUniformHeight = -1f;
		uniformUpdateCount = 0;
		flushedRecords.clear();

		mockApp = createMockApplication();
		GL20 mockGl = createMockGl20();
		Gdx.app = mockApp;
		Gdx.gl = mockGl;
		Gdx.gl20 = mockGl;
		Gdx.files = new Lwjgl3Files();
		Gdx.graphics = createMockGraphics();
	}

	@AfterEach
	@SuppressWarnings("unchecked")
	void tearDown() throws Exception {
		if (mockApp != null) {
			ShaderProgram.clearAllShaderPrograms(mockApp);
			Mesh.clearAllMeshes(mockApp);
			Texture.clearAllTextures(mockApp);
		}
		Field shadersField = ShaderManager.class.getDeclaredField("shaders");
		shadersField.setAccessible(true);
		Map<String, ShaderProgram> managerMap = (Map<String, ShaderProgram>) shadersField.get(null);
		managerMap.clear();
		managerMap.putAll(savedShaders);

		Gdx.app = prevApp;
		Gdx.gl = prevGl;
		Gdx.gl20 = prevGl20;
		Gdx.files = prevFiles;
		Gdx.graphics = prevGraphics;
	}

	@Test
	@DisplayName("末尾に小さい追加ページを持つフォントで先頭ページのみ描画した際、先頭ページのテクスチャサイズがu_textureSizeに設定されること")
	void testMainPageGlyphUsesMainPageTextureSizeInsteadOfTrailingAppendPage() {
		BitmapFont font = createMultiPageFont();
		GlyphLayout layout = new GlyphLayout(font, "AB");

		RecordingSpriteBatch batch = new RecordingSpriteBatch();
		SkinObjectRenderer renderer = new SkinObjectRenderer(batch);
		renderer.setType(SkinObjectRenderer.TYPE_BILINEAR);

		batch.begin();
		renderer.draw(font, layout, 10f, 20f);
		batch.end();

		assertEquals(1, flushedRecords.size());
		FlushedBatchRecord record = flushedRecords.get(0);
		assertEquals(2048, record.textureWidth());
		assertEquals(2048, record.textureHeight());
		assertEquals(2048f, record.uniformWidth());
		assertEquals(2048f, record.uniformHeight());
	}

	@Test
	@DisplayName("サイズが異なる複数ページのグリフを混在描画した際、ページ切替前にフラッシュして各ページのu_textureSizeで描画されること")
	void testMixedSizePagesFlushAndUpdateUniformPerPage() {
		BitmapFont font = createMultiPageFont();
		GlyphLayout layout = new GlyphLayout(font, "A☆");

		RecordingSpriteBatch batch = new RecordingSpriteBatch();
		SkinObjectRenderer renderer = new SkinObjectRenderer(batch);
		renderer.setType(SkinObjectRenderer.TYPE_BILINEAR);

		batch.begin();
		renderer.draw(font, layout, 10f, 20f);
		batch.end();

		assertEquals(2, flushedRecords.size());
		// 1ページ目 (2048x2048) は 2048x2048 の uniform のままフラッシュされること
		assertEquals(new FlushedBatchRecord(2048, 2048, 2048f, 2048f), flushedRecords.get(0));
		// 2ページ目 (200x200) は 200x200 の uniform に更新されて描画されること
		assertEquals(new FlushedBatchRecord(200, 200, 200f, 200f), flushedRecords.get(1));
	}

	@Test
	@DisplayName("文字列直接指定のdrawオーバーロードでもページ毎のテクスチャサイズがu_textureSizeに反映されること")
	void testStringOverloadUpdatesUniformPerPage() {
		BitmapFont font = createMultiPageFont();

		RecordingSpriteBatch batch = new RecordingSpriteBatch();
		SkinObjectRenderer renderer = new SkinObjectRenderer(batch);
		renderer.setType(SkinObjectRenderer.TYPE_BILINEAR);

		batch.begin();
		renderer.draw(font, "A☆", 10f, 20f, Color.WHITE);
		batch.end();

		assertEquals(2, flushedRecords.size());
		assertEquals(new FlushedBatchRecord(2048, 2048, 2048f, 2048f), flushedRecords.get(0));
		assertEquals(new FlushedBatchRecord(200, 200, 200f, 200f), flushedRecords.get(1));
	}

	@Test
	@DisplayName("同一サイズの複数ページを跨ぐ際は不要なu_textureSize再設定を行わず描画され、空文字列では描画が発生しないこと")
	void testSameSizeMultiPageAndEmptyText() {
		BitmapFont font = createMultiPageFont();
		GlyphLayout sameSizePagesLayout = new GlyphLayout(font, "AC");
		GlyphLayout emptyLayout = new GlyphLayout(font, "");

		RecordingSpriteBatch batch = new RecordingSpriteBatch();
		SkinObjectRenderer renderer = new SkinObjectRenderer(batch);
		renderer.setType(SkinObjectRenderer.TYPE_BILINEAR);

		batch.begin();
		renderer.draw(font, emptyLayout, 10f, 20f);
		assertEquals(0, uniformUpdateCount);
		assertTrue(flushedRecords.isEmpty());

		renderer.draw(font, sameSizePagesLayout, 10f, 20f);
		batch.end();

		assertEquals(1, uniformUpdateCount);
		assertEquals(2, flushedRecords.size());
		assertEquals(new FlushedBatchRecord(2048, 2048, 2048f, 2048f), flushedRecords.get(0));
		assertEquals(new FlushedBatchRecord(2048, 2048, 2048f, 2048f), flushedRecords.get(1));
	}

	private static BitmapFont createMultiPageFont() {
		Texture page0 = new Texture(new StubTextureData(2048, 2048));
		Texture page1 = new Texture(new StubTextureData(2048, 2048));
		Texture appendPage = new Texture(new StubTextureData(200, 200));

		Array<TextureRegion> regions = new Array<>();
		regions.add(new TextureRegion(page0));
		regions.add(new TextureRegion(page1));
		regions.add(new TextureRegion(appendPage));

		BitmapFontData data = new BitmapFontData();
		data.lineHeight = 32f;
		data.capHeight = 24f;
		data.ascent = -24f;
		data.down = -32f;

		data.setGlyph('A', createGlyph('A', 0, 10, 10, 24, 24, 26));
		data.setGlyph('B', createGlyph('B', 0, 40, 10, 24, 24, 26));
		data.setGlyph('C', createGlyph('C', 1, 10, 10, 24, 24, 26));
		data.setGlyph('☆', createGlyph('☆', 2, 8, 8, 32, 32, 34));

		return new BitmapFont(data, regions, false);
	}

	private static Glyph createGlyph(int id, int page, int srcX, int srcY, int width, int height, int xadvance) {
		Glyph glyph = new Glyph();
		glyph.id = id;
		glyph.page = page;
		glyph.srcX = srcX;
		glyph.srcY = srcY;
		glyph.width = width;
		glyph.height = height;
		glyph.xadvance = xadvance;
		return glyph;
	}

	private final class RecordingSpriteBatch extends SpriteBatch {
		private Texture pendingTexture;

		RecordingSpriteBatch() {
			super(64);
		}

		@Override
		public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
			// SpriteBatch内部で別テクスチャに切り替わると自動flushされるため、直前のpendingTextureを保持しておく
			super.draw(texture, spriteVertices, offset, count);
			pendingTexture = texture;
		}

		@Override
		public void flush() {
			boolean hadVertices = pendingTexture != null;
			Texture flushingTexture = pendingTexture;
			super.flush();
			if (hadVertices) {
				flushedRecords.add(new FlushedBatchRecord(
						flushingTexture.getWidth(),
						flushingTexture.getHeight(),
						currentUniformWidth,
						currentUniformHeight));
				pendingTexture = null;
			}
		}
	}

	private static final class StubTextureData implements TextureData {
		private final int width;
		private final int height;
		private boolean prepared;

		private StubTextureData(int width, int height) {
			this.width = width;
			this.height = height;
		}

		@Override
		public TextureDataType getType() {
			return TextureDataType.Custom;
		}

		@Override
		public boolean isPrepared() {
			return prepared;
		}

		@Override
		public void prepare() {
			prepared = true;
		}

		@Override
		public Pixmap consumePixmap() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean disposePixmap() {
			return false;
		}

		@Override
		public void consumeCustomData(int target) {
		}

		@Override
		public int getWidth() {
			return width;
		}

		@Override
		public int getHeight() {
			return height;
		}

		@Override
		public Pixmap.Format getFormat() {
			return Pixmap.Format.RGBA8888;
		}

		@Override
		public boolean useMipMaps() {
			return false;
		}

		@Override
		public boolean isManaged() {
			return false;
		}
	}

	private static Application createMockApplication() {
		return (Application) Proxy.newProxyInstance(
				Application.class.getClassLoader(),
				new Class<?>[] { Application.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "equals" -> {
							return proxy == args[0];
						}
						case "hashCode" -> {
							return System.identityHashCode(proxy);
						}
						case "toString" -> {
							return "MockApplication";
						}
						case "getType" -> {
							return Application.ApplicationType.Desktop;
						}
						default -> {
							Class<?> returnType = method.getReturnType();
							if (returnType == boolean.class) {
								return false;
							}
							if (returnType == int.class) {
								return 0;
							}
							if (returnType == long.class) {
								return 0L;
							}
							return null;
						}
					}
				});
	}

	private static Graphics createMockGraphics() {
		return (Graphics) Proxy.newProxyInstance(
				Graphics.class.getClassLoader(),
				new Class<?>[] { Graphics.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "getWidth", "getBackBufferWidth" -> {
							return 1920;
						}
						case "getHeight", "getBackBufferHeight" -> {
							return 1080;
						}
						case "supportsExtension", "isGL30Available" -> {
							return false;
						}
						default -> {
							Class<?> returnType = method.getReturnType();
							if (returnType == boolean.class) {
								return false;
							}
							if (returnType == int.class) {
								return 0;
							}
							if (returnType == float.class) {
								return 0f;
							}
							if (returnType == long.class) {
								return 0L;
							}
							return null;
						}
					}
				});
	}

	private GL20 createMockGl20() {
		final int[] nextHandle = { 1 };
		final Map<String, Integer> uniformLocations = new HashMap<>();
		final Map<String, Integer> attribLocations = new HashMap<>();

		return (GL20) Proxy.newProxyInstance(
				GL20.class.getClassLoader(),
				new Class<?>[] { GL20.class },
				(proxy, method, args) -> {
					String name = method.getName();
					switch (name) {
						case "glCreateShader", "glCreateProgram", "glGenTexture", "glGenBuffer", "glGenFramebuffer" -> {
							return nextHandle[0]++;
						}
						case "glGetShaderiv" -> {
							int pname = (Integer) args[1];
							IntBuffer params = (IntBuffer) args[2];
							if (pname == GL20.GL_COMPILE_STATUS) {
								params.put(0, GL20.GL_TRUE);
							}
							return null;
						}
						case "glGetProgramiv" -> {
							int pname = (Integer) args[1];
							IntBuffer params = (IntBuffer) args[2];
							if (pname == GL20.GL_LINK_STATUS) {
								params.put(0, GL20.GL_TRUE);
							} else {
								params.put(0, 0);
							}
							return null;
						}
						case "glGetUniformLocation" -> {
							String uniformName = (String) args[1];
							if ("u_textureSize".equals(uniformName)) {
								return TEXTURE_SIZE_UNIFORM_LOCATION;
							}
							return uniformLocations.computeIfAbsent(uniformName, k -> 100 + uniformLocations.size());
						}
						case "glGetAttribLocation" -> {
							String attribName = (String) args[1];
							return attribLocations.computeIfAbsent(attribName, k -> attribLocations.size());
						}
						case "glUniform2f" -> {
							int location = (Integer) args[0];
							if (location == TEXTURE_SIZE_UNIFORM_LOCATION) {
								currentUniformWidth = (Float) args[1];
								currentUniformHeight = (Float) args[2];
								uniformUpdateCount++;
							}
							return null;
						}
						case "glGetShaderInfoLog", "glGetProgramInfoLog" -> {
							return "";
						}
						case "glCheckFramebufferStatus" -> {
							return GL20.GL_FRAMEBUFFER_COMPLETE;
						}
						default -> {
							Class<?> returnType = method.getReturnType();
							if (returnType == boolean.class) {
								return false;
							}
							if (returnType == int.class) {
								return 0;
							}
							if (returnType == float.class) {
								return 0f;
							}
							if (returnType == long.class) {
								return 0L;
							}
							return null;
						}
					}
				});
	}
}
