package br.com.michelbrenner.bioar.arcore;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.UnavailableException;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MarkerScannerActivity extends Activity implements GLSurfaceView.Renderer {
    private static final int CAMERA_PERMISSION_REQUEST = 4101;
    private static final String MARKER_ID = "heart";
    private static final String MARKER_NAME = "Coração";
    private static final float MARKER_WIDTH_METERS = 0.14f;

    private GLSurfaceView surfaceView;
    private TextView statusView;
    private Session session;
    private boolean installRequested;
    private boolean surfaceResumed;
    private volatile boolean sessionResumed;
    private volatile boolean viewportChanged = true;
    private volatile String statusOnScreen = "";
    private volatile boolean markerRecognized;

    private int surfaceWidth;
    private int surfaceHeight;
    private int cameraTextureId = -1;
    private boolean cameraTextureAssigned;
    private int cameraProgram;
    private int positionAttribute;
    private int texCoordAttribute;
    private int textureUniform;

    // Renderização OpenGL do coração 3D ancorado à pose do marcador ARCore.
    private int heartProgram;
    private int heartPositionAttribute;
    private int heartNormalAttribute;
    private int heartMvpUniform;
    private int heartColorUniform;
    private Mesh heartBodyMesh;
    private Mesh sphereMesh;

    private final FloatBuffer quadVertices = createFloatBuffer(new float[] {
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    });
    private final FloatBuffer transformedTexCoords = createFloatBuffer(new float[8]);

    private static final String VERTEX_SHADER =
        "attribute vec4 a_Position;\n" +
        "attribute vec2 a_TexCoord;\n" +
        "varying vec2 v_TexCoord;\n" +
        "void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }\n";

    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "varying vec2 v_TexCoord;\n" +
        "void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }\n";

    private static final String HEART_VERTEX_SHADER =
        "attribute vec3 a_Position;\n" +
        "attribute vec3 a_Normal;\n" +
        "uniform mat4 u_MvpMatrix;\n" +
        "varying vec3 v_Normal;\n" +
        "void main() { gl_Position = u_MvpMatrix * vec4(a_Position, 1.0); v_Normal = a_Normal; }\n";

    private static final String HEART_FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "uniform vec4 u_Color;\n" +
        "varying vec3 v_Normal;\n" +
        "void main() { vec3 n = normalize(v_Normal); vec3 lightDir = normalize(vec3(-0.35, 0.55, 0.85)); " +
        "float diffuse = max(dot(n, lightDir), 0.0); float light = 0.58 + 0.42 * diffuse; " +
        "gl_FragColor = vec4(u_Color.rgb * light, u_Color.a); }\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScannerInterface();
    }

    private void buildScannerInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new GLSurfaceView(this);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setText("Preparando o scanner ARCore…");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16);
        statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(20, 16, 20, 16);
        statusView.setBackground(background(0xD9192938, 22));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        statusParams.setMargins(18, 24, 18, 0);
        root.addView(statusView, statusParams);

        Button finishButton = new Button(this);
        finishButton.setText("Finalizar teste");
        finishButton.setTextColor(Color.WHITE);
        finishButton.setTextSize(15);
        finishButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        finishButton.setAllCaps(false);
        finishButton.setBackground(background(0xFF0F766E, 18));
        finishButton.setOnClickListener(view -> finishScanner());
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonParams.setMargins(28, 0, 28, 26);
        root.addView(finishButton, buttonParams);
        setContentView(root);
    }

    private GradientDrawable background(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return drawable;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startArSession();
    }

    private void startArSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            postStatus("A realidade aumentada exige Android 7.0 ou superior.");
            return;
        }
        try {
            ArCoreApk.InstallStatus installStatus =
                ArCoreApk.getInstance().requestInstall(this, !installRequested);
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true;
                postStatus("Conclua a instalação/atualização do Google Play Services para RA e volte ao BioAR.");
                return;
            }

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST);
                postStatus("Autorize o acesso à câmera para iniciar a realidade aumentada.");
                return;
            }

            if (session == null) {
                session = new Session(this);
                configureImageDatabase();
            }
            session.resume();
            sessionResumed = true;
            resumeSurface();
            postStatus("Aponte a câmera para o marcador A1 do coração, impresso em 14 × 14 cm.");
        } catch (UnavailableException exception) {
            postStatus("ARCore indisponível: " + safeMessage(exception));
            closeSession();
        } catch (CameraNotAvailableException exception) {
            postStatus("A câmera não está disponível. Feche outros aplicativos que possam estar usando-a.");
            closeSession();
        } catch (Exception exception) {
            postStatus("Falha ao iniciar o scanner: " + safeMessage(exception));
            closeSession();
        }
    }

    private void configureImageDatabase() throws Exception {
        AugmentedImageDatabase database = new AugmentedImageDatabase(session);
        try (InputStream input = getAssets().open("ar_markers/heart-marker.png")) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) throw new IllegalStateException("A imagem de referência do coração não pôde ser carregada.");
            try {
                database.addImage(MARKER_ID, bitmap, MARKER_WIDTH_METERS);
            } finally {
                bitmap.recycle();
            }
        }

        Config config = new Config(session);
        config.setFocusMode(Config.FocusMode.AUTO);
        config.setAugmentedImageDatabase(database);
        config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        session.configure(config);
    }

    private void resumeSurface() {
        if (surfaceView != null && !surfaceResumed) {
            surfaceView.onResume();
            surfaceResumed = true;
        }
    }

    private void pauseSurface() {
        if (surfaceView != null && surfaceResumed) {
            surfaceView.onPause();
            surfaceResumed = false;
        }
    }

    @Override
    protected void onPause() {
        pauseSurface();
        if (session != null && sessionResumed) {
            session.pause();
            sessionResumed = false;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startArSession();
            } else {
                postStatus("A permissão da câmera é necessária para reconhecer o marcador.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        pauseSurface();
        closeSession();
        super.onDestroy();
    }

    private void closeSession() {
        sessionResumed = false;
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private void finishScanner() {
        Intent result = new Intent();
        result.putExtra("recognized", markerRecognized);
        result.putExtra("markerId", markerRecognized ? MARKER_ID : "");
        result.putExtra("markerName", markerRecognized ? MARKER_NAME : "");
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private void postStatus(String message) {
        if (message == null || message.equals(statusOnScreen)) return;
        statusOnScreen = message;
        runOnUiThread(() -> {
            if (statusView != null) statusView.setText(message);
        });
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return (message == null || message.trim().isEmpty())
            ? exception.getClass().getSimpleName() : message;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        cameraTextureId = createCameraTexture();
        cameraProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionAttribute = GLES20.glGetAttribLocation(cameraProgram, "a_Position");
        texCoordAttribute = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord");
        textureUniform = GLES20.glGetUniformLocation(cameraProgram, "sTexture");
        cameraTextureAssigned = false;

        heartProgram = createProgram(HEART_VERTEX_SHADER, HEART_FRAGMENT_SHADER);
        heartPositionAttribute = GLES20.glGetAttribLocation(heartProgram, "a_Position");
        heartNormalAttribute = GLES20.glGetAttribLocation(heartProgram, "a_Normal");
        heartMvpUniform = GLES20.glGetUniformLocation(heartProgram, "u_MvpMatrix");
        heartColorUniform = GLES20.glGetUniformLocation(heartProgram, "u_Color");
        heartBodyMesh = createHeartBodyMesh();
        sphereMesh = createSphereMesh();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        viewportChanged = true;
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session currentSession = session;
        if (currentSession == null || !sessionResumed || cameraTextureId < 0 || cameraProgram == 0) return;

        try {
            if (!cameraTextureAssigned) {
                currentSession.setCameraTextureName(cameraTextureId);
                cameraTextureAssigned = true;
            }
            if (viewportChanged && surfaceWidth > 0 && surfaceHeight > 0) {
                currentSession.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(),
                    surfaceWidth, surfaceHeight);
                viewportChanged = false;
            }

            Frame frame = currentSession.update();
            drawCameraBackground(frame);
            drawTrackedHeart(frame);
            updateImageStatus(currentSession);
        } catch (CameraNotAvailableException exception) {
            postStatus("A câmera ficou indisponível. Feche o scanner e tente novamente.");
        } catch (Exception exception) {
            postStatus("Erro durante o rastreamento: " + safeMessage(exception));
        }
    }

    private void updateImageStatus(Session currentSession) {
        for (AugmentedImage image : currentSession.getAllTrackables(AugmentedImage.class)) {
            if (!MARKER_ID.equals(image.getName())) continue;
            if (image.getTrackingState() == TrackingState.TRACKING) {
                markerRecognized = true;
                if (image.getTrackingMethod() == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                    postStatus("MARCADOR A1 RECONHECIDO\nCoração 3D ancorado ao marcador");
                } else {
                    postStatus("Coração reconhecido\nAguardando rastreamento completo…");
                }
                return;
            }
        }
        if (!markerRecognized) {
            postStatus("Aponte a câmera para o marcador A1 do coração, impresso em 14 × 14 cm.");
        }
    }

    private void drawTrackedHeart(Frame frame) {
        if (heartProgram == 0 || heartBodyMesh == null || sphereMesh == null) return;

        com.google.ar.core.Camera arCamera = frame.getCamera();
        float[] viewMatrix = new float[16];
        float[] projectionMatrix = new float[16];
        arCamera.getViewMatrix(viewMatrix, 0);
        arCamera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

        for (AugmentedImage image : session.getAllTrackables(AugmentedImage.class)) {
            if (!MARKER_ID.equals(image.getName())
                    || image.getTrackingState() != TrackingState.TRACKING
                    || image.getTrackingMethod() != AugmentedImage.TrackingMethod.FULL_TRACKING) {
                continue;
            }

            float[] markerPose = new float[16];
            float[] markerOffset = new float[16];
            float[] heartBaseModel = new float[16];
            image.getCenterPose().toMatrix(markerPose, 0);

            // O modelo nasce alguns milímetros acima da superfície impressa,
            // no eixo normal do marcador, e acompanha sua posição/orientação.
            Matrix.setIdentityM(markerOffset, 0);
            Matrix.translateM(markerOffset, 0, 0f, 0f, 0.006f);
            Matrix.multiplyMM(heartBaseModel, 0, markerPose, 0, markerOffset, 0);

            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);
            GLES20.glDisable(GLES20.GL_CULL_FACE);

            drawMesh(heartBodyMesh, heartBaseModel, viewMatrix, projectionMatrix,
                new float[] {0.78f, 0.055f, 0.12f, 1f});

            // Átrios e volume superior do coração.
            drawEllipsoid(heartBaseModel, viewMatrix, projectionMatrix,
                -0.012f, 0.014f, 0.006f, 0.018f, 0.019f, 0.014f,
                new float[] {0.91f, 0.16f, 0.21f, 1f});
            drawEllipsoid(heartBaseModel, viewMatrix, projectionMatrix,
                0.012f, 0.014f, 0.006f, 0.018f, 0.019f, 0.014f,
                new float[] {0.86f, 0.10f, 0.17f, 1f});

            // Grandes vasos: aorta, veias cavas e artéria pulmonar.
            float[] aortaColor = {0.88f, 0.20f, 0.12f, 1f};
            float[] veinColor = {0.12f, 0.39f, 0.86f, 1f};
            float[] pulmonaryColor = {0.38f, 0.30f, 0.82f, 1f};

            drawTube(heartBaseModel, viewMatrix, projectionMatrix,
                -0.010f, 0.026f, 0.004f, -0.010f, 0.060f, 0.004f, 0.0065f, aortaColor);
            drawTube(heartBaseModel, viewMatrix, projectionMatrix,
                -0.010f, 0.060f, 0.004f, 0.012f, 0.062f, 0.004f, 0.0065f, aortaColor);
            drawTube(heartBaseModel, viewMatrix, projectionMatrix,
                0.012f, 0.062f, 0.004f, 0.016f, 0.046f, 0.004f, 0.0060f, aortaColor);

            drawTube(heartBaseModel, viewMatrix, projectionMatrix,
                0.023f, -0.005f, -0.002f, 0.023f, 0.052f, -0.002f, 0.0050f, veinColor);
            drawTube(heartBaseModel, viewMatrix, projectionMatrix,
                -0.025f, 0.035f, 0.013f, 0.020f, 0.047f, 0.013f, 0.0055f, pulmonaryColor);

            // Pequeno relevo frontal para destacar a superfície muscular.
            drawEllipsoid(heartBaseModel, viewMatrix, projectionMatrix,
                -0.010f, -0.010f, 0.017f, 0.0045f, 0.020f, 0.0022f,
                new float[] {0.96f, 0.28f, 0.30f, 1f});
            drawEllipsoid(heartBaseModel, viewMatrix, projectionMatrix,
                0.010f, -0.010f, 0.017f, 0.0045f, 0.020f, 0.0022f,
                new float[] {0.96f, 0.28f, 0.30f, 1f});
            break;
        }
    }

    private void drawEllipsoid(float[] baseModel, float[] view, float[] projection,
            float x, float y, float z, float sx, float sy, float sz, float[] color) {
        float[] local = new float[16];
        float[] model = new float[16];
        Matrix.setIdentityM(local, 0);
        Matrix.translateM(local, 0, x, y, z);
        Matrix.scaleM(local, 0, sx, sy, sz);
        Matrix.multiplyMM(model, 0, baseModel, 0, local, 0);
        drawMesh(sphereMesh, model, view, projection, color);
    }

    private void drawTube(float[] baseModel, float[] view, float[] projection,
            float ax, float ay, float az, float bx, float by, float bz,
            float radius, float[] color) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.0001f) return;

        float[] local = new float[16];
        float[] model = new float[16];
        Matrix.setIdentityM(local, 0);
        Matrix.translateM(local, 0, (ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f);

        float dot = Math.max(-1f, Math.min(1f, dy / length));
        float angle = (float) Math.toDegrees(Math.acos(dot));
        float axisX = dz;
        float axisY = 0f;
        float axisZ = -dx;
        float axisLength = (float) Math.sqrt(axisX * axisX + axisZ * axisZ);
        if (axisLength > 0.0001f) {
            Matrix.rotateM(local, 0, angle, axisX / axisLength, axisY, axisZ / axisLength);
        } else if (dy < 0f) {
            Matrix.rotateM(local, 0, 180f, 1f, 0f, 0f);
        }

        Matrix.scaleM(local, 0, radius, length * 0.5f + radius, radius);
        Matrix.multiplyMM(model, 0, baseModel, 0, local, 0);
        drawMesh(sphereMesh, model, view, projection, color);
    }

    private void drawMesh(Mesh mesh, float[] model, float[] view, float[] projection, float[] color) {
        float[] viewModel = new float[16];
        float[] mvp = new float[16];
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0);

        GLES20.glUseProgram(heartProgram);
        GLES20.glUniformMatrix4fv(heartMvpUniform, 1, false, mvp, 0);
        GLES20.glUniform4fv(heartColorUniform, 1, color, 0);

        mesh.vertices.position(0);
        GLES20.glEnableVertexAttribArray(heartPositionAttribute);
        GLES20.glVertexAttribPointer(heartPositionAttribute, 3, GLES20.GL_FLOAT, false,
            6 * 4, mesh.vertices);
        mesh.vertices.position(3);
        GLES20.glEnableVertexAttribArray(heartNormalAttribute);
        GLES20.glVertexAttribPointer(heartNormalAttribute, 3, GLES20.GL_FLOAT, false,
            6 * 4, mesh.vertices);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount);
        GLES20.glDisableVertexAttribArray(heartPositionAttribute);
        GLES20.glDisableVertexAttribArray(heartNormalAttribute);
    }

    private Mesh createHeartBodyMesh() {
        List<Float> data = new ArrayList<>();
        final int segments = 72;
        final int rings = 18;
        final float depth = 0.018f;

        for (int side : new int[] {1, -1}) {
            float[] center = {0f, 0f, side * depth};
            float[][] previousRing = null;

            for (int ring = 1; ring <= rings; ring++) {
                float radius = ring / (float) rings;
                float[][] currentRing = new float[segments][3];
                for (int j = 0; j < segments; j++) {
                    double angle = (Math.PI * 2.0 * j) / segments;
                    double sin = Math.sin(angle);
                    double yShape = 13.0 * Math.cos(angle)
                        - 5.0 * Math.cos(2.0 * angle)
                        - 2.0 * Math.cos(3.0 * angle)
                        - Math.cos(4.0 * angle);
                    currentRing[j][0] = radius * 0.036f * (float) (sin * sin * sin);
                    currentRing[j][1] = radius * 0.00255f * (float) (yShape + 2.5);
                    currentRing[j][2] = side * depth
                        * (float) Math.sqrt(Math.max(0.0, 1.0 - radius * radius));
                }

                for (int j = 0; j < segments; j++) {
                    int next = (j + 1) % segments;
                    if (previousRing == null) {
                        appendTriangle(data, center, currentRing[j], currentRing[next], side);
                    } else {
                        appendTriangle(data, previousRing[j], currentRing[j], currentRing[next], side);
                        appendTriangle(data, previousRing[j], currentRing[next], previousRing[next], side);
                    }
                }
                previousRing = currentRing;
            }
        }
        return makeMesh(data);
    }

    private Mesh createSphereMesh() {
        List<Float> data = new ArrayList<>();
        final int rings = 16;
        final int segments = 24;
        for (int i = 0; i < rings; i++) {
            double phi0 = Math.PI * i / rings;
            double phi1 = Math.PI * (i + 1) / rings;
            for (int j = 0; j < segments; j++) {
                double theta0 = 2.0 * Math.PI * j / segments;
                double theta1 = 2.0 * Math.PI * (j + 1) / segments;
                float[] p00 = spherePoint(phi0, theta0);
                float[] p01 = spherePoint(phi0, theta1);
                float[] p10 = spherePoint(phi1, theta0);
                float[] p11 = spherePoint(phi1, theta1);
                appendSmoothTriangle(data, p00, p10, p11);
                appendSmoothTriangle(data, p00, p11, p01);
            }
        }
        return makeMesh(data);
    }

    private float[] spherePoint(double phi, double theta) {
        float x = (float) (Math.sin(phi) * Math.cos(theta));
        float y = (float) Math.cos(phi);
        float z = (float) (Math.sin(phi) * Math.sin(theta));
        return new float[] {x, y, z};
    }

    private void appendTriangle(List<Float> data, float[] a, float[] b, float[] c, int wantedZSign) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        if (nz * wantedZSign < 0f) {
            float[] swap = b; b = c; c = swap;
            ux = b[0] - a[0]; uy = b[1] - a[1]; uz = b[2] - a[2];
            vx = c[0] - a[0]; vy = c[1] - a[1]; vz = c[2] - a[2];
            nx = uy * vz - uz * vy;
            ny = uz * vx - ux * vz;
            nz = ux * vy - uy * vx;
        }
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 0.000001f) return;
        float[] normal = {nx / length, ny / length, nz / length};
        appendVertex(data, a, normal);
        appendVertex(data, b, normal);
        appendVertex(data, c, normal);
    }

    private void appendSmoothTriangle(List<Float> data, float[] a, float[] b, float[] c) {
        appendVertex(data, a, a);
        appendVertex(data, b, b);
        appendVertex(data, c, c);
    }

    private void appendVertex(List<Float> data, float[] position, float[] normal) {
        data.add(position[0]); data.add(position[1]); data.add(position[2]);
        data.add(normal[0]); data.add(normal[1]); data.add(normal[2]);
    }

    private Mesh makeMesh(List<Float> values) {
        float[] packed = new float[values.size()];
        for (int i = 0; i < values.size(); i++) packed[i] = values.get(i);
        FloatBuffer buffer = ByteBuffer.allocateDirect(packed.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(packed);
        buffer.position(0);
        return new Mesh(buffer, packed.length / 6);
    }

    private static final class Mesh {
        final FloatBuffer vertices;
        final int vertexCount;
        Mesh(FloatBuffer vertices, int vertexCount) {
            this.vertices = vertices;
            this.vertexCount = vertexCount;
        }
    }

    private void drawCameraBackground(Frame frame) {
        transformedTexCoords.clear();
        quadVertices.position(0);
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadVertices,
            Coordinates2d.TEXTURE_NORMALIZED, transformedTexCoords);
        transformedTexCoords.position(0);
        quadVertices.position(0);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(cameraProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(textureUniform, 0);
        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, quadVertices);
        GLES20.glEnableVertexAttribArray(texCoordAttribute);
        GLES20.glVertexAttribPointer(texCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoords);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionAttribute);
        GLES20.glDisableVertexAttribArray(texCoordAttribute);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    private int createCameraTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return textures[0];
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String info = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Falha ao preparar o renderizador da câmera: " + info);
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String info = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Falha ao compilar shader: " + info);
        }
        return shader;
    }

    private static FloatBuffer createFloatBuffer(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer floatBuffer = buffer.asFloatBuffer();
        floatBuffer.put(values);
        floatBuffer.position(0);
        return floatBuffer;
    }
}
