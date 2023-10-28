package net.pgaskin.windy;

import android.app.WallpaperColors;
import android.content.Context;
import android.util.Log;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService;
import com.badlogic.gdx.backends.android.AndroidWallpaperListener;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.VertexBufferObject;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

public abstract class WindyWallpaperService extends AndroidLiveWallpaperService {
    public class Engine extends AndroidLiveWallpaperService.AndroidWallpaperEngine implements ApplicationListener, AndroidWallpaperListener, WindFieldProvider.WindFieldUpdateListener {
        private static final String TAG = "Windy";

        // TODO: refactor this

        private static final boolean DO_SCREENSHOTS = false;

        private static final int MIN_PAGES_TO_SWIPE = 4;
        private static final int NUM_TIMES_REDRAW = 240;
        private final Vector2 PARTICLE_SIZE = new Vector2(1.2f, 1.15f);

        private final Context context;
        private final Config config;
        private final PowerSaveController powerSaveController;
        private FPSThrottler fpsThrottler;

        private WindyParticles particleSystem;
        private ShaderProgram particleShader;
        private ShaderProgram trailShader;
        private SpriteBatch trailBatch;
        private FrameBuffer trailFbo, trailFbo1, trailFbo2;
        private ShaderProgram backgroundShader;
        private SpriteBatch backgroundBatch;
        private TextureRegion windFieldRegion;
        private Texture windFieldTexture;

        private volatile boolean createCalled = false;
        private volatile boolean loaded;
        private boolean initialized;
        private int redrawMapCounter = 0;
        private int width, height;
        private float currentAlphaDecay;
        private float offsetX, offsetXEased;
        private final Matrix4 offsetMatrix = new Matrix4();
        private boolean powerSaveOffsetFixed;
        private OrthographicCamera camera;
        private float lowFPSFrameDelta = 0.0f;

        private final WindFieldProvider windFieldProvider;
        private final Object windVectorFieldUpdatedLock = new Object();
        private boolean windVectorFieldUpdated = false;

        public Engine(Context context, Config config) {
            this.context = context;
            this.config = config;
            this.powerSaveController = new PowerSaveController(context);
            this.windFieldProvider = new WindFieldProvider(context, this);
        }

        private void updateBounds() {
            Vector2 location = this.config.fakeLocation != null
                ? this.config.fakeLocation
                : LocationActivity.updateLocation(this.context, true);
            float wndLng = this.config.windowSize * ((float)(this.width) / (float)(this.height));
            float wndLat = this.config.windowSize;
            float centerLng = location != null ? location.x : -97.0f;
            float centerLat = location != null ? location.y : 38.0f;
            float boundL = MathUtils.clamp(centerLng - wndLng/2f, -180, 180);
            float boundT = MathUtils.clamp(centerLat + wndLat/2f, -90, 90);
            float boundR = MathUtils.clamp(boundL + wndLng, -180, 180);
            float boundB = MathUtils.clamp(boundT - wndLat, -90, 90);
            this.windFieldRegion.setRegion(lngToRatio(boundL), latToRatio(boundT), lngToRatio(boundR), latToRatio(boundB));
        }

        private float lngToRatio(float lng) {
            return (180.0f + lng) / 360.0f;
        }

        private float latToRatio(float lat) {
            return 1.0f - ((90.0f + lat) / 180.0f);
        }

        @Override // ApplicationListener
        public void create() {
            this.fpsThrottler = new FPSThrottler(this.powerSaveController);
            this.currentAlphaDecay = this.config.alphaDecay;

            // QCOM_binning_control: BINNING_CONTROL_HINT_QCOM = CPU_OPTIMIZED_QCOM
            Gdx.gl.glEnable(0x8FB0);
            Gdx.gl.glHint(0x8FB0, 0x8FB1);

            // wind field
            Pixmap pixmap = this.windFieldProvider.currentPixmap();
            this.windFieldTexture = new Texture(pixmap);
            this.windFieldTexture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            this.windFieldTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            this.windFieldRegion = new TextureRegion(this.windFieldTexture);

            // particle system
            this.particleSystem = new WindyParticles(this.config, PARTICLE_SIZE);
            this.particleSystem.setVectorField(this.windFieldRegion);

            // particle
            this.particleShader = new ShaderProgram(Gdx.files.internal("windy/particle.vert"), Gdx.files.internal("windy/particle.frag"));
            if (!this.particleShader.isCompiled()) {
                throw new GdxRuntimeException(this.particleShader.getLog());
            }

            // trail
            this.trailShader = new ShaderProgram(Gdx.files.internal("windy/trail.vert"), Gdx.files.internal("windy/trail.frag"));
            if (!this.trailShader.isCompiled()) {
                throw new GdxRuntimeException(this.trailShader.getLog());
            }
            this.trailBatch = new SpriteBatch(1);
            this.trailBatch.setShader(this.trailShader);

            // background
            this.backgroundShader = new ShaderProgram(Gdx.files.internal("windy/background.vert"), Gdx.files.internal("windy/background.frag"));
            if (!this.backgroundShader.isCompiled()) {
                throw new GdxRuntimeException(this.backgroundShader.getLog());
            }
            this.backgroundBatch = new SpriteBatch(1);
            this.backgroundBatch.setShader(this.backgroundShader);

            // init
            this.initFrameBuffer(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            this.createCalled = true;
            this.redrawMap(NUM_TIMES_REDRAW);
            this.redrawMapCounter = NUM_TIMES_REDRAW;
            this.loaded = true;

            if (DO_SCREENSHOTS) {
                String path = this.context.getExternalCacheDir() + "/" + WindyWallpaperService.this.getClass().getSimpleName() + ".png";
                Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
                Pixmap scr = new Pixmap(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Pixmap.Format.RGBA8888);
                Gdx.gl.glReadPixels(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, scr.getPixels());
                PixmapIO.writePNG(new FileHandle(path), scr);
                Log.w(TAG, "screenshot: " + path);
            }

            this.resume();
        }

        @Override // ApplicationListener
        public void resize(int w, int h) {
            if (w != this.width || h != this.height) {
                this.initFrameBuffer(w, h);
                this.redrawMap(10);
            }
        }

        private void initFrameBuffer(int w, int h) {
            this.width = w;
            this.height = h;
            this.camera = new OrthographicCamera(1.0f, 1.0f);
            this.camera.translate(0.5f, 0.5f);
            this.camera.update();
            this.updateBounds();

            if (this.trailFbo1 != null) {
                this.trailFbo1.dispose();
            }
            if (this.trailFbo2 != null) {
                this.trailFbo2.dispose();
            }

            this.trailFbo1 = createCustomFrameBuffer(GL30.GL_RG16F, GL30.GL_RG, GL30.GL_HALF_FLOAT, (int) (w * PARTICLE_SIZE.x), (int) (h * PARTICLE_SIZE.y));
            this.trailFbo2 = createCustomFrameBuffer(GL30.GL_RG16F, GL30.GL_RG, GL30.GL_HALF_FLOAT, (int) (w * PARTICLE_SIZE.x), (int) (h * PARTICLE_SIZE.y));
            this.trailFbo = this.trailFbo1;

            this.trailFbo1.begin();
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            Gdx.gl.glClear(16640);
            this.trailFbo1.end();

            this.trailFbo2.begin();
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            Gdx.gl.glClear(16640);
            this.trailFbo2.end();

            GLFrameBuffer.clearAllFrameBuffers(Gdx.app);
            this.trailBatch.getProjectionMatrix().setToOrtho2D(0.0f, 0.0f, this.trailFbo1.getWidth(), this.trailFbo1.getHeight());
            this.backgroundBatch.getProjectionMatrix().setToOrtho2D(0.0f, 0.0f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()).translate((float)(w) / 2, (float)(h) / 2, 0.0f);
            System.gc();
        }

        private void redrawMap(int n) {
            this.initialized = false;
            for (int i = 0; i < n; i++) {
                this.render();
            }
            this.initialized = true;
            this.redrawMapCounter = 0;
        }

        @Override // ApplicationListener
        public void render() {
            if (this.createCalled) {
                this.fpsThrottler.begin();
                this.applyWindVectorFieldUpdate();
                this.currentAlphaDecay += (this.config.alphaDecay - this.currentAlphaDecay) * 0.019f;
                float frameDelta = Math.min(Gdx.graphics.getDeltaTime(), 0.055555556f);
                if (!this.powerSaveController.isPowerSaveMode()) {
                    this.powerSaveOffsetFixed = false;
                } else if (this.offsetX - this.offsetXEased < 0.01d) {
                    this.powerSaveOffsetFixed = true;
                }
                if (!this.powerSaveOffsetFixed) {
                    this.offsetXEased += (this.offsetX - this.offsetXEased) * frameDelta * 5.0f;
                }
                int updateFPS = (Float.compare(Math.abs(this.offsetX - this.offsetXEased), 1E-4f)) > 0 ? FPSThrottler.HIGH_FPS : FPSThrottler.LOWER_FPS;
                this.redrawMapCounter = Math.min(this.redrawMapCounter + 1, NUM_TIMES_REDRAW);
                boolean redrawing = this.redrawMapCounter < NUM_TIMES_REDRAW;
                float timeDelta = this.initialized ? 0.055555556f : 0.08500001f;
                if (redrawing) {
                    float cos = (float) ((Math.cos((Math.PI * this.redrawMapCounter) / NUM_TIMES_REDRAW) * 0.5d) + 0.5d);
                    updateFPS = (int) mapClamp(0.0f, 1.0f, updateFPS, FPSThrottler.HIGH_FPS, cos);
                    timeDelta = mapClamp(0.0f, 0.3f, timeDelta, 0.08500001f, cos);
                }
                this.lowFPSFrameDelta += frameDelta;
                if (!this.initialized || this.lowFPSFrameDelta > 0.055555556f || redrawing) {
                    this.lowFPSFrameDelta = 0.0f;
                    this.particleSystem.update(timeDelta);
                    Texture frameBuffer = this.trailFbo.getColorBufferTexture();
                    this.trailFbo = this.trailFbo == this.trailFbo1 ? this.trailFbo2 : this.trailFbo1;
                    this.trailFbo.begin();
                    Gdx.gl.glEnable(GL30.GL_BLEND);
                    this.trailBatch.disableBlending();
                    this.trailBatch.begin();
                    this.trailShader.setUniformf("u_fadeDecay", this.currentAlphaDecay);
                    this.trailBatch.draw(frameBuffer, 0.0f, 0.0f, frameBuffer.getWidth(), frameBuffer.getHeight(), 0.0f, 0.0f, 1.0f, 1.0f);
                    this.trailBatch.end();
                    Gdx.gl.glEnable(GL30.GL_BLEND);
                    Gdx.gl.glBlendFunc(GL30.GL_SRC_ALPHA, GL30.GL_ONE_MINUS_SRC_ALPHA);
                    this.particleShader.bind();
                    this.particleSystem.getParticlePositions().bind(1);
                    this.particleShader.setUniformi("u_positionTex", 1);
                    this.particleShader.setUniformf("u_particleOpacity", this.config.particleOpacity);
                    this.particleShader.setUniformMatrix("u_projTrans", this.camera.combined);
                    Gdx.gl.glActiveTexture(GL30.GL_TEXTURE0);
                    this.particleSystem.getParticleIndices().bind(this.particleShader);
                    Gdx.gl.glDrawArrays(0, 0, this.config.particleCount);
                    this.particleSystem.getParticleIndices().unbind(this.particleShader);
                    this.trailFbo.end();
                }
                this.backgroundBatch.disableBlending();
                this.backgroundBatch.begin();
                this.backgroundShader.setUniformf("u_vectorFieldBounds", this.windFieldRegion.getU(), this.windFieldRegion.getV(), this.windFieldRegion.getU2() - this.windFieldRegion.getU(), this.windFieldRegion.getV2() - this.windFieldRegion.getV());
                this.windFieldTexture.bind(1);
                this.backgroundShader.setUniformi("u_vectorField", 1);
                float abs = Math.abs(1.0f - PARTICLE_SIZE.x) * 0.5f;
                this.offsetMatrix.setToRotation(Vector3.Z, (-this.offsetXEased) * (float)(MIN_PAGES_TO_SWIPE));
                this.offsetMatrix.translate(((-this.offsetXEased) * this.width * abs * 2.0f * 0.7f) + (this.width * abs), 0.0f, 0.0f);
                this.backgroundShader.setUniformMatrix("u_transform", this.offsetMatrix);
                this.backgroundShader.setUniformf("u_resolution", this.width, this.height);
                this.backgroundShader.setUniformf("u_backgroundColor1", this.config.bgColor);
                this.backgroundShader.setUniformf("u_backgroundColor2", this.config.bgColor2);
                this.backgroundShader.setUniformf("u_colorSlow", this.config.slowWindColor);
                this.backgroundShader.setUniformf("u_colorFast", this.config.fastWindColor);
                this.backgroundShader.setUniformf("u_fboScale", 1.0f);
                this.backgroundShader.setUniformf("u_size", PARTICLE_SIZE.x, PARTICLE_SIZE.y);
                Gdx.gl.glActiveTexture(GL30.GL_TEXTURE0);
                this.backgroundBatch.draw(this.trailFbo.getColorBufferTexture(), (float)(-this.width) / 2, (float)(-this.height) / 2, this.width, this.height);
                this.backgroundBatch.end();
                if (this.initialized) {
                    this.fpsThrottler.end(updateFPS);
                }
            }
        }

        private float mapClamp(float inRangeStart, float inRangeEnd, float outRangeStart, float outRangeEnd, float value) {
            if (value <= inRangeStart) {
                return outRangeStart;
            }
            if (value >= inRangeEnd) {
                return outRangeEnd;
            }
            return MathUtils.map(inRangeStart, inRangeEnd, outRangeStart, outRangeEnd, value);
        }

        @Override // ApplicationListener
        public void pause() {
            this.powerSaveController.pause();
        }

        @Override // ApplicationListener
        public void resume() {
            this.windFieldProvider.requestUpdate();
            this.powerSaveController.resume();
        }

        @Override // ApplicationListener
        public void dispose() {
            if (this.particleShader != null) {
                this.particleShader.dispose();
            }
            if (this.backgroundShader != null) {
                this.backgroundShader.dispose();
            }
            if (this.particleSystem != null) {
                this.particleSystem.dispose();
            }
            if (this.windFieldTexture != null) {
                this.windFieldTexture.dispose();
            }
            if (this.trailFbo1 != null) {
                this.trailFbo1.dispose();
            }
            if (this.trailFbo2 != null) {
                this.trailFbo2.dispose();
            }
            this.powerSaveController.pause();
        }

        @Override // AndroidWallpaperListener
        public void offsetChange(float xOffset, float v1, float xOffsetStep, float v3, int i, int i1) {
            if (loaded && xOffsetStep != 0f && xOffsetStep != -1f) {
                int numSteps = (int) (1.0f / xOffsetStep);
                float offsetStretch = Math.min(numSteps / (float) (MIN_PAGES_TO_SWIPE), 1.0f);
                this.offsetX = xOffset * offsetStretch;
            }
        }

        @Override // AndroidWallpaperListener
        public void previewStateChange(boolean b) {}

        @Override // AndroidWallpaperListener
        public void iconDropped(int x, int y) {}

        @Override // AndroidWallpaperEngine
        public WallpaperColors onComputeColors() {
            if (this.config.wallpaperColors != null) {
                return this.config.wallpaperColors;
            }
            return super.onComputeColors();
        }

        @Override // WindVectorUpdateListener
        public void onWindFieldUpdate() {
            synchronized (this.windVectorFieldUpdatedLock) {
                if (Gdx.app != null) {
                    this.windVectorFieldUpdated = true;
                }
            }
        }

        private void applyWindVectorFieldUpdate() {
            synchronized (this.windVectorFieldUpdatedLock) {
                if (this.windVectorFieldUpdated) {
                    this.windVectorFieldUpdated = false;

                    if (this.windFieldTexture != null) {
                        this.windFieldTexture.dispose();
                    }

                    try {
                        Pixmap pixmap = this.windFieldProvider.currentPixmap();
                        this.windFieldTexture = new Texture(pixmap);
                        this.windFieldTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                        this.windFieldTexture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
                        this.windFieldRegion = new TextureRegion(this.windFieldTexture);
                        pixmap.dispose();
                        this.particleSystem.setVectorField(this.windFieldRegion);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to load updated texture: " + e);
                    }

                    this.currentAlphaDecay = this.config.alphaDecayNewMap;
                    this.redrawMapCounter = 0;
                    this.updateBounds();
                }
            }
        }

        private class WindyParticles implements Disposable {
            private final Config config;
            private final SpriteBatch batch;
            private final VertexBufferObject indices;
            private final ShaderProgram shader;
            private final Vector2 size;
            private TextureRegion vectorField;
            private float timeAcc = 0.0f;
            private static final int POSITION_TEXTURE_SIZE = 64;
            private FrameBuffer positionIn = createCustomFrameBuffer(GL30.GL_RGBA32F, GL30.GL_RGBA, GL30.GL_FLOAT, POSITION_TEXTURE_SIZE, POSITION_TEXTURE_SIZE);
            private FrameBuffer positionOut = createCustomFrameBuffer(GL30.GL_RGBA32F, GL30.GL_RGBA, GL30.GL_FLOAT, POSITION_TEXTURE_SIZE, POSITION_TEXTURE_SIZE);

            public WindyParticles(Config config, Vector2 size) {
                if (config.particleCount > POSITION_TEXTURE_SIZE*POSITION_TEXTURE_SIZE) {
                    throw new RuntimeException("Cannot fit " + config.particleCount + " particles");
                }

                this.size = size;
                this.config = config;

                float[] indices = new float[this.config.particleCount * 3];
                indices: for (int y = 0; y < 64; y++) {
                    for (int x = 0; x < 64; x++) {
                        int index = ((y * 64) + x) * 3;
                        if (index >= indices.length) {
                            break indices;
                        }
                        indices[index] = x / 64.0f;
                        indices[index + 1] = y / 64.0f;
                    }
                }

                this.indices = new VertexBufferObject(true, this.config.particleCount * 3, VertexAttribute.Position());
                this.indices.setVertices(indices, 0, indices.length);

                this.shader = new ShaderProgram(Gdx.files.internal("windy/particle_system.vert"), Gdx.files.internal("windy/particle_system.frag"));
                if (!this.shader.isCompiled()) {
                    throw new GdxRuntimeException(this.shader.getLog());
                }

                this.batch = new SpriteBatch(1);
                this.batch.getProjectionMatrix().setToOrtho(0.0f, 64.0f, 64.0f, 0.0f, 0.0f, 10.0f);
                this.batch.setShader(this.shader);
            }

            public VertexBufferObject getParticleIndices() {
                return this.indices;
            }

            public Texture getParticlePositions() {
                return this.positionOut.getColorBufferTexture();
            }

            public void setVectorField(TextureRegion vectorField) {
                this.vectorField = vectorField;
            }

            public void update(float timeDelta) {
                if (this.vectorField != null) {
                    this.timeAcc += timeDelta;
                    this.timeAcc %= 64.0f;

                    this.positionOut.begin();
                    this.batch.begin();
                    this.vectorField.getTexture().bind(1);
                    this.shader.setUniformi("u_vectorField", 1);
                    this.shader.setUniformf("u_timeAcc", this.timeAcc);
                    this.shader.setUniformf("u_timeDelta", timeDelta);
                    this.shader.setUniformf("u_resolution", (float) (Gdx.graphics.getHeight()) / (float) (Gdx.graphics.getWidth()), 1.0f);
                    this.shader.setUniformf("u_size", this.size.x, this.size.y);
                    this.shader.setUniformf("u_windSpeed", this.config.windSpeed);
                    this.shader.setUniformf("u_particleLife", this.config.particleLife);
                    this.shader.setUniformf("u_vectorFieldBounds", this.vectorField.getU(), this.vectorField.getV(), this.vectorField.getU2() - this.vectorField.getU(), this.vectorField.getV2() - this.vectorField.getV());
                    Gdx.gl.glActiveTexture(GL30.GL_TEXTURE0);
                    this.batch.draw(this.positionIn.getColorBufferTexture(), 0.0f, 0.0f);
                    this.batch.end();
                    this.positionOut.end();

                    FrameBuffer mParticlePositionsTmp = this.positionIn;
                    this.positionIn = this.positionOut;
                    this.positionOut = mParticlePositionsTmp;
                }
            }

            @Override // Disposable
            public void dispose() {
                this.indices.dispose();
                this.shader.dispose();
                if (this.positionIn != null) {
                    this.positionIn.dispose();
                }
                if (this.positionOut != null) {
                    this.positionOut.dispose();
                }
            }
        }

        private FrameBuffer createCustomFrameBuffer(int internal, int format, int type, int width, int height) {
            GLFrameBuffer.FrameBufferBuilder builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
            builder.addColorTextureAttachment(internal, format, type);
            FrameBuffer buffer = builder.build();
            Texture texture = buffer.getTextureAttachments().first();
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            return buffer;
        }
    }


    @Override // AndroidLiveWallpaperService
    public void onCreateApplication() {
        final AndroidApplicationConfiguration cfg = new AndroidApplicationConfiguration();
        cfg.useAccelerometer = false;
        cfg.useCompass = false;
        cfg.useGyroscope = false;
        cfg.numSamples = 2;
        cfg.r = cfg.g = cfg.b = cfg.a = 8;
        cfg.depth = 16;

        super.onCreateApplication();
        this.app.setLogLevel(Application.LOG_INFO);
        this.initialize(new Engine(this, this.onConfigure()), cfg);
    }

    public Config onConfigure() {
        return new Config();
    }

    public static class Config {
        public Vector2 fakeLocation = null;
        /** Degrees of longitude to show at once. */
        public int windowSize = 75;
        public int particleCount = 2048;
        public float windSpeed = 0.1f;
        public float particleLife = 8.0f;
        public Color slowWindColor = new Color(1.0f, 0.0f, 1.0f, 1.0f);
        public Color fastWindColor = new Color(0.0f, 1.0f, 1.0f, 1.0f);
        public Color bgColor = new Color(0.1f, 0.0f, 0.1f, 0.1f);
        public Color bgColor2 = new Color(0.1f, 0.0f, 0.1f, 0.1f);
        public float alphaDecay = 0.9965f;
        public float alphaDecayNewMap = 0.91f;
        public float particleOpacity = 0.6f;
        public WallpaperColors wallpaperColors;
    }
}