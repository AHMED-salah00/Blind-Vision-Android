package com.dorvis.textrecognitionandroid;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    private PreviewView previewView;
    private TextView textView;
    private Button readTextButton;
    private Button stopButton;

    private ExecutorService cameraExecutor;
    private TextRecognizer textRecognizer;
    private TextToSpeech textToSpeech;

    private String lastRecognizedText = "";
    private boolean isTtsReady = false;
    private volatile boolean shouldCapture = false;
    private boolean isReading = false;

    private ImageAnalysis imageAnalysis;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());

        previewView = findViewById(R.id.previewView);
        textView = findViewById(R.id.text_view);
        readTextButton = findViewById(R.id.read_text_button);
        stopButton = findViewById(R.id.stop_button);

        // Set large touch target areas for buttons
        readTextButton.setMinimumHeight(160);
        stopButton.setMinimumHeight(160);

        // Initialize text recognizer and TTS
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        textToSpeech = new TextToSpeech(this, this);

        // Executor for camera
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Set up accessibility
        setupAccessibility();

        // Button click: trigger single-frame capture
        readTextButton.setOnClickListener(v -> {
            if (!isReading) {
                announceForAccessibility("Capturing text");
                shouldCapture = true;
                Toast.makeText(this, "Capturing text...", Toast.LENGTH_SHORT).show();
            }
        });

        // Stop button click: stop TTS
        stopButton.setOnClickListener(v -> {
            if (isReading && textToSpeech != null) {
                textToSpeech.stop();
                isReading = false;
                announceForAccessibility("Stopped reading");
            }
        });

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    private void setupAccessibility() {
        // Set custom accessibility delegate for the main view
        ViewCompat.setAccessibilityDelegate(findViewById(android.R.id.content), new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription("Text Reader App. Use the Read Text button to capture and read text.");
            }
        });

        // Set up TTS utterance listener
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                mainHandler.post(() -> isReading = true);
            }

            @Override
            public void onDone(String utteranceId) {
                mainHandler.post(() -> {
                    isReading = false;
                    announceForAccessibility("Reading complete");
                });
            }

            @Override
            public void onError(String utteranceId) {
                mainHandler.post(() -> {
                    isReading = false;
                    announceForAccessibility("Error reading text");
                });
            }
        });
    }

    private void announceForAccessibility(String text) {
        textView.announceForAccessibility(text);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(Locale.getDefault());
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported");
                Toast.makeText(this, "Text-to-speech language not supported", Toast.LENGTH_SHORT).show();
                announceForAccessibility("Text-to-speech language not supported");
            } else {
                isTtsReady = true;
                textToSpeech.setSpeechRate(0.85f);
                textToSpeech.setPitch(1.0f);
                readTextButton.setEnabled(true);
                stopButton.setEnabled(true);

                // Welcome message
                mainHandler.postDelayed(() -> {
                    String welcomeMessage = "Text Reader ready. Tap the Read Text button to scan and read text.";
                    textToSpeech.speak(welcomeMessage, TextToSpeech.QUEUE_FLUSH, null, "welcome");
                }, 1000);
            }
        } else {
            Log.e(TAG, "TTS initialization failed");
            Toast.makeText(this, "Text-to-speech initialization failed", Toast.LENGTH_SHORT).show();
            announceForAccessibility("Text-to-speech initialization failed");
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
                announceForAccessibility("Camera permission granted");
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
                announceForAccessibility("Camera permission denied. App cannot function without camera access.");
                finish();
            }
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
                cameraProvider.unbindAll();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

                announceForAccessibility("Camera ready");
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera setup failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(ImageProxy imageProxy) {
        if (!shouldCapture) {
            imageProxy.close();
            return;
        }

        shouldCapture = false;

        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            Task<Text> result = textRecognizer.process(image)
                    .addOnSuccessListener(text -> {
                        lastRecognizedText = text.getText();
                        runOnUiThread(() -> {
                            textView.setText(lastRecognizedText);

                            if (isTtsReady) {
                                if (!lastRecognizedText.isEmpty()) {
                                    // Announce that text was found before reading it
                                    announceForAccessibility("Text found");

                                    // Short delay before reading text to allow announcement to complete
                                    mainHandler.postDelayed(() -> {
                                        isReading = true;
                                        textToSpeech.speak(lastRecognizedText, TextToSpeech.QUEUE_FLUSH, null, "reading");
                                    }, 1000);
                                } else {
                                    String noTextMessage = "No text found. Please try again.";
                                    textToSpeech.speak(noTextMessage, TextToSpeech.QUEUE_FLUSH, null, "no_text");
                                    Toast.makeText(MainActivity.this, noTextMessage, Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Text recognition failed", e);
                        runOnUiThread(() -> {
                            String errorMessage = "Text recognition failed. Please try again.";
                            textToSpeech.speak(errorMessage, TextToSpeech.QUEUE_FLUSH, null, "error");
                            Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                        });
                    })
                    .addOnCompleteListener(task -> imageProxy.close());
        } else {
            imageProxy.close();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Announce app status when resuming
        if (isTtsReady) {
            mainHandler.postDelayed(() -> {
                textToSpeech.speak("Text Reader ready", TextToSpeech.QUEUE_FLUSH, null, "resume");
            }, 1000);
        }
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        super.onDestroy();
    }
}