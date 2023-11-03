package net.pgaskin.windy;

import android.opengl.Matrix;
import android.util.Log;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
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
import com.badlogic.gdx.utils.TimeUtils;

public final class Windy implements Disposable {
    private static final int BINNING_CONTROL_HINT_QCOM = 0x8FB0;
    private static final int CPU_OPTIMIZED_QCOM = 0x8FB1;

    public static class Config {
        /** Degrees of longitude to show at once. */
        public int windowSize = 75;
        public Vector2 scale = new Vector2(1.2f, 1.15f); // for page swipe offset parallax
        public final int minPagesToSwipe = 4; // for page swipe offset parallax
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
    }

    public interface WindFieldProvider {
        /**
         * swapTexture takes null or a previously returned texture, and if a new wind field is ready, disposes of it and
         * returns a new one. It will be called from the render thread of a single Windy instance.
         */
        Texture swapTexture(Texture old);
    }

    public interface UserLocationProvider {
        /**
         * Gets the current user location (x: lng, y: lat). It will be called from the render thread of a single Windy
         * instance when the wind field is updated or the wallpaper is resized, and should return immediately. It can
         * return null if the user location is unavailable.
         */
        Vector2 getLocation(boolean requestIfMissing);
    }

    public interface PowerSaveModeProvider {
        /**
         * Checks whether power saving mode is enabled.
         */
        boolean isPowerSaveMode();
    }

    private static final int NUM_TIMES_REDRAW = 240;
    private static final int FPS_HIGH = 60;
    private static final int FPS_NORMAL = 13;
    private static final int FPS_POWERSAVE = 3;

    private final Config config;
    private final PowerSaveModeProvider powerSaveModeProvider;
    private final UserLocationProvider userLocationProvider;
    private final WindFieldProvider windFieldProvider;
    private final TextureRegion windField;
    private final Particles particles;
    private final Streamlines streamlines;
    private final Background background;

    private int redrawCounter, redrawTarget;
    private int width, height;
    private float offsetX, offsetXEased;
    private float streamlineDelta;

    public Windy(Config config, PowerSaveModeProvider powerSaveModeProvider, UserLocationProvider userLocationProvider, WindFieldProvider windFieldProvider) {
        this.config = config;
        this.powerSaveModeProvider = powerSaveModeProvider;
        this.userLocationProvider = userLocationProvider;
        this.windFieldProvider = windFieldProvider;

        Gdx.gl.glEnable(BINNING_CONTROL_HINT_QCOM);
        Gdx.gl.glHint(BINNING_CONTROL_HINT_QCOM, CPU_OPTIMIZED_QCOM);

        windField = new TextureRegion();
        particles = new Particles(config, 64);
        streamlines = new Streamlines(config);
        background = new Background(config);

        updateWindField();
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        redraw(NUM_TIMES_REDRAW, false); // note: replaces the redraw call in resize
    }

    public void resize(int width, int height) {
        if (this.width == width && this.height == height) return;

        this.width = width;
        this.height = height;

        updateUserLocation();
        particles.resize(width, height);
        streamlines.resize(width, height);
        background.resize(width, height);

        GLFrameBuffer.clearAllFrameBuffers(Gdx.app);
        System.gc();

        redraw(NUM_TIMES_REDRAW - 50, true);
    }

    private void redraw(int frames, boolean deferAnimation) {
        redrawCounter = 0;
        redrawTarget = frames;
        if (!deferAnimation) while (redrawCounter < redrawTarget) render(false);
    }

    private boolean updateWindField() {
        final Texture old = windField.getTexture();
        final Texture tex = windFieldProvider.swapTexture(old);
        if (tex == null) return false;
        tex.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        windField.setTexture(tex);
        if (old != null) {
            streamlines.decayAlpha(config.alphaDecayNewMap);
            redraw(0, true);
        }
        return old != null;
    }

    private void updateUserLocation() {
        final Vector2 location = this.userLocationProvider.getLocation(true);
        final float wndLng = config.windowSize * ((float) width / (float) height);
        final float wndLat = config.windowSize;
        final float centerLng = location != null ? location.x : -97.0f;
        final float centerLat = location != null ? location.y : 38.0f;
        final float boundL = MathUtils.clamp(centerLng - wndLng/2f, -180, 180);
        final float boundT = MathUtils.clamp(centerLat + wndLat/2f, -90, 90);
        final float boundR = MathUtils.clamp(boundL + wndLng, -180, 180);
        final float boundB = MathUtils.clamp(boundT - wndLat, -90, 90);
        windField.setRegion(lngToRatio(boundL), latToRatio(boundT), lngToRatio(boundR), latToRatio(boundB));
    }

    public void setOffsetX(float offset, float step) {
        final int steps = (int) (1.0f / step);
        final float stretch = Math.min(steps / (float) (config.minPagesToSwipe), 1.0f);
        offsetX = offset * stretch;
    }

    public void render() {
        render(true);
    }

    private void render(boolean isRendering) {
        if (updateWindField()) {
            updateUserLocation();
            streamlines.decayAlpha(config.alphaDecayNewMap);
            redraw(NUM_TIMES_REDRAW, true);
        }

        final boolean isPowerSaveMode = powerSaveModeProvider.isPowerSaveMode();

        final long frameStart = TimeUtils.nanoTime();

        final float frameDelta = Math.min(Gdx.graphics.getDeltaTime(), 1/18f);
        streamlineDelta += frameDelta;

        boolean isEasing = false;
        if (!isPowerSaveMode && Math.abs(offsetX - offsetXEased) >= 0.01d) {
            offsetXEased += (offsetX - offsetXEased) * frameDelta * 5.0f;
            isEasing = true;
        }

        float timeDelta = isRendering ? 1/18f : 85/1000f;
        int targetFPS = isEasing ? FPS_HIGH : !isPowerSaveMode ? FPS_NORMAL : FPS_POWERSAVE;

        boolean isRedrawing = false;
        if (redrawCounter < redrawTarget) {
            redrawCounter++;
            if (isRendering) {
                final float cos = (float) ((Math.cos((Math.PI * redrawCounter) / redrawTarget) * 0.5d) + 0.5d);
                targetFPS = (int) mapClamp(0.0f, 1.0f, targetFPS, FPS_HIGH, cos);
                timeDelta = mapClamp(0.0f, 0.3f, timeDelta, 85/1000f, cos);
                isRedrawing = true;
            }
        }

        if (!isRendering || isRedrawing || streamlineDelta > 1/18f) {
            streamlineDelta = 0.0f;
            particles.update(timeDelta, windField);
            streamlines.render(particles);
        }
        streamlines.decayAlpha();

        if (isRendering) {
            background.render(windField, streamlines, offsetXEased);
        }

        final long frameTime = TimeUtils.nanoTime() - frameStart;
        if (isRendering) {
            final long delay = Math.max((1000 / targetFPS) - (frameTime / 1000000), 1L);
            synchronized (Thread.currentThread()) {
                try { Thread.currentThread().wait(delay); } catch (InterruptedException ignored) {}
            }
        }
    }

    @Override
    public void dispose() {
        if (windField.getTexture() != null) windField.getTexture().dispose();
        if (particles != null) particles.dispose();
        if (streamlines != null) streamlines.dispose();
        if (background != null) background.dispose();
    }

    private static final class Background implements Disposable {
        private final Config config;
        private final ShaderProgram shader;
        private final SpriteBatch batch;
        private final Matrix4 offsetMatrix;
        private int width, height;

        public Background(Config config) {
            this.config = config;

            offsetMatrix = new Matrix4();

            shader = new ShaderProgram(Gdx.files.internal("windy/background.vert"), Gdx.files.internal("windy/background.frag"));
            if (!shader.isCompiled()) throw new GdxRuntimeException(shader.getLog());

            batch = new SpriteBatch(1);
            batch.setShader(shader);

            resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }

        public void resize(int width, int height) {
            this.width = width;
            this.height = height;

            float[] mt = new float[16];
            Matrix.orthoM(mt, 0, 0, width, 0, height, 0, 1);
            Matrix.translateM(mt, 0, width/2f, height/2f, 0);
            batch.getProjectionMatrix().set(mt);
        }

        public void render(TextureRegion windField, Streamlines streamlines, float offsetX) {
            final float abs = Math.abs(1.0f - config.scale.x) * 0.5f;
            offsetMatrix.setToRotation(Vector3.Z, -offsetX * config.minPagesToSwipe);
            offsetMatrix.translate(-offsetX * width * abs * 2.0f * 0.7f + width * abs, 0.0f, 0.0f);

            batch.disableBlending();
            batch.begin();
            shader.setUniformi("u_vectorField", 1);
            shader.setUniformf("u_vectorFieldBounds", windField.getU(), windField.getV(), windField.getU2() - windField.getU(), windField.getV2() - windField.getV());
            shader.setUniformMatrix("u_transform", offsetMatrix);
            shader.setUniformf("u_resolution", width, height);
            shader.setUniformf("u_backgroundColor1", config.bgColor);
            shader.setUniformf("u_backgroundColor2", config.bgColor2);
            shader.setUniformf("u_colorSlow", config.slowWindColor);
            shader.setUniformf("u_colorFast", config.fastWindColor);
            shader.setUniformf("u_size", config.scale.x, config.scale.y);
            windField.getTexture().bind(1);
            Gdx.gl.glActiveTexture(Gdx.gl.GL_TEXTURE0);
            batch.draw(streamlines.getTexture(), -width / 2f, -height / 2f, width, height);
            batch.end();
        }

        @Override
        public void dispose() {
            shader.dispose();
            batch.dispose();
        }
    }

    private static final class Streamlines implements Disposable {
        private final Config config;
        private final ShaderProgram particleShader;
        private final ShaderProgram trailShader;
        private final SpriteBatch trailBatch;
        private final Matrix4 matProjTrans;
        private FrameBuffer trailFbo, trailFboPing, trailFboPong;
        private float currentAlphaDecay;

        public Streamlines(Config config) {
            this.config = config;

            currentAlphaDecay = config.alphaDecay;

            float[] mt = new float[16];
            Matrix.orthoM(mt, 0, 0, 1, 0, 1, 0, 1);
            matProjTrans = new Matrix4(mt);

            particleShader = new ShaderProgram(Gdx.files.internal("windy/particle.vert"), Gdx.files.internal("windy/particle.frag"));
            if (!particleShader.isCompiled()) throw new GdxRuntimeException(particleShader.getLog());

            trailShader = new ShaderProgram(Gdx.files.internal("windy/trail.vert"), Gdx.files.internal("windy/trail.frag"));
            if (!trailShader.isCompiled()) throw new GdxRuntimeException(trailShader.getLog());

            trailBatch = new SpriteBatch(1);
            trailBatch.setShader(trailShader);

            resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }

        public void resize(int width, int height) {
            if (trailFboPing != null) {
                trailFboPing.dispose();
            }
            if (trailFboPong != null) {
                trailFboPong.dispose();
            }

            trailFboPing = createCustomFloatFrameBufferGPU(Gdx.gl30.GL_RG16F, Gdx.gl30.GL_RG, Gdx.gl30.GL_HALF_FLOAT, (int) (width * config.scale.x), (int) (height * config.scale.y));
            trailFboPong = createCustomFloatFrameBufferGPU(Gdx.gl30.GL_RG16F, Gdx.gl30.GL_RG, Gdx.gl30.GL_HALF_FLOAT, (int) (width * config.scale.x), (int) (height * config.scale.y));
            trailFbo = trailFboPing;

            trailFboPing.begin();
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(Gdx.gl.GL_COLOR_BUFFER_BIT | Gdx.gl.GL_DEPTH_BUFFER_BIT);
            trailFboPing.end();

            trailFboPong.begin();
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(Gdx.gl.GL_COLOR_BUFFER_BIT | Gdx.gl.GL_DEPTH_BUFFER_BIT);
            trailFboPong.end();

            float[] mt = new float[16];
            Matrix.orthoM(mt, 0, 0, trailFbo.getWidth(), 0, trailFbo.getHeight(), 0, 1);
            trailBatch.getProjectionMatrix().set(mt);
        }

        public void render(Particles particles) {
            final FrameBuffer trailFboIn = trailFbo;
            trailFbo = trailFbo == trailFboPing ? trailFboPong : trailFboPing;

            trailFbo.begin();

            trailBatch.disableBlending();
            trailBatch.begin();
            trailShader.setUniformf("u_fadeDecay", currentAlphaDecay);
            trailBatch.draw(trailFboIn.getColorBufferTexture(), 0, 0, trailFboIn.getWidth(), trailFboIn.getHeight(), 0, 0, 1, 1);
            trailBatch.end();

            Gdx.gl.glEnable(Gdx.gl.GL_BLEND);
            Gdx.gl.glBlendFunc(Gdx.gl.GL_SRC_ALPHA, Gdx.gl.GL_ONE_MINUS_SRC_ALPHA); // pre-multiplied alpha
            particleShader.bind();
            particleShader.setUniformi("u_positionTex", 1);
            particleShader.setUniformf("u_particleOpacity", config.particleOpacity);
            particleShader.setUniformMatrix("u_projTrans", matProjTrans);
            particles.getTexture().bind(1);
            Gdx.gl.glActiveTexture(Gdx.gl.GL_TEXTURE0);
            particles.getVbo().bind(particleShader);
            Gdx.gl.glDrawArrays(Gdx.gl.GL_POINTS, 0, config.particleCount);
            particles.getVbo().unbind(particleShader);

            trailFbo.end();
        }

        public Texture getTexture() {
            return trailFbo.getColorBufferTexture();
        }

        public void decayAlpha() {
            currentAlphaDecay += (config.alphaDecay - currentAlphaDecay) * 0.019f;
        }

        public void decayAlpha(float set) {
            currentAlphaDecay = set;
        }

        @Override
        public void dispose() {
            trailShader.dispose();
            particleShader.dispose();
            trailBatch.dispose();
            trailFboPing.dispose();
            trailFboPong.dispose();
        }
    }

    private static final class Particles implements Disposable {
        private final Config config;
        private final SpriteBatch batch;
        private final VertexBufferObject vbo;
        private final ShaderProgram shader;
        private float timeAcc = 0.0f;
        private final int dim;
        private final FrameBuffer fboPing;
        private final FrameBuffer fboPong;
        private FrameBuffer fbo;
        private int width, height;

        public Particles(Config config, int dim) {
            if (config.particleCount > dim * dim) {
                throw new RuntimeException("Cannot fit " + config.particleCount + " particles");
            }

            this.config = config;
            this.dim = dim;

            fboPing = createCustomFloatFrameBufferGPU(Gdx.gl30.GL_RGBA32F, Gdx.gl30.GL_RGBA, Gdx.gl.GL_FLOAT, dim, dim);
            fboPong = createCustomFloatFrameBufferGPU(Gdx.gl30.GL_RGBA32F, Gdx.gl30.GL_RGBA, Gdx.gl.GL_FLOAT, dim, dim);
            fbo = fboPing;

            final float[] indices = new float[config.particleCount * 3];
            indices:
            for (int y = 0; y < dim; y++) {
                for (int x = 0; x < dim; x++) {
                    int index = (y * dim + x) * 3;
                    if (index >= indices.length) {
                        break indices;
                    }
                    indices[index] = x / (float) (dim);
                    indices[index + 1] = y / (float) (dim);
                }
            }
            vbo = new VertexBufferObject(true, indices.length, VertexAttribute.Position());
            vbo.setVertices(indices, 0, indices.length);

            shader = new ShaderProgram(Gdx.files.internal("windy/particle_system.vert"), Gdx.files.internal("windy/particle_system.frag"));
            if (!shader.isCompiled()) throw new GdxRuntimeException(shader.getLog());

            batch = new SpriteBatch(1);
            float[] mt = new float[16];
            Matrix.orthoM(mt, 0, 0, dim, dim, 0, 0, 1);
            batch.getProjectionMatrix().set(mt);
            batch.setShader(shader);
        }

        public void resize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public void update(float timeDelta, TextureRegion vectorField) {
            timeAcc += timeDelta;
            timeAcc %= (float) dim;

            final FrameBuffer fboIn = fbo;
            fbo = fbo == fboPing ? fboPong : fboPing;

            fbo.begin();
            batch.begin();
            vectorField.getTexture().bind(1);
            shader.setUniformi("u_vectorField", 1);
            shader.setUniformf("u_timeAcc", timeAcc);
            shader.setUniformf("u_timeDelta", timeDelta);
            shader.setUniformf("u_resolution", (float) height / (float) width, 1);
            shader.setUniformf("u_size", config.scale.x, config.scale.y);
            shader.setUniformf("u_windSpeed", config.windSpeed);
            shader.setUniformf("u_particleLife", config.particleLife);
            shader.setUniformf("u_vectorFieldBounds", vectorField.getU(), vectorField.getV(), vectorField.getU2() - vectorField.getU(), vectorField.getV2() - vectorField.getV());
            Gdx.gl.glActiveTexture(Gdx.gl.GL_TEXTURE0);
            batch.draw(fboIn.getColorBufferTexture(), 0, 0);
            batch.end();
            fbo.end();
        }

        @Override
        public void dispose() {
            vbo.dispose();
            batch.dispose();
            shader.dispose();
            fboPing.dispose();
            fboPong.dispose();
        }

        public VertexBufferObject getVbo() {
            return vbo;
        }

        public Texture getTexture() {
            return fbo.getColorBufferTexture();
        }
    }

    private static float lngToRatio(float lng) {
        return (180.0f + lng) / 360.0f;
    }

    private static float latToRatio(float lat) {
        return 1.0f - ((90.0f + lat) / 180.0f);
    }

    private static float mapClamp(float inRangeStart, float inRangeEnd, float outRangeStart, float outRangeEnd, float value) {
        if (value <= inRangeStart) {
            return outRangeStart;
        }
        if (value >= inRangeEnd) {
            return outRangeEnd;
        }
        return MathUtils.map(inRangeStart, inRangeEnd, outRangeStart, outRangeEnd, value);
    }

    private static FrameBuffer createCustomFloatFrameBufferGPU(int internal, int format, int type, int width, int height) {
        final GLFrameBuffer.FrameBufferBuilder builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addFloatAttachment(internal, format, type, true);
        final FrameBuffer buffer = builder.build();
        final Texture texture = buffer.getTextureAttachments().first();
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return buffer;
    }
}
