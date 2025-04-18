package com.dorvis.textrecognitionandroid;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.camera.view.PreviewView;

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
    private ExecutorService cameraExecutor;
    private TextRecognizer textRecognizer;
    private TextToSpeech textToSpeech;
    private String lastRecognizedText = "";
    private boolean isTtsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        textView = findViewById(R.id.text_view);
        readTextButton = findViewById(R.id.read_text_button);

        // Initialize text-to-speech
        textToSpeech = new TextToSpeech(this, this);

        // Initialize the text recognizer
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        // Initialize CameraExecutor to handle camera lifecycle
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Set read text button click listener
        readTextButton.setOnClickListener(v -> readTextAloud());

        // Check for camera permission
        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language for TTS
            int result = textToSpeech.setLanguage(Locale.US);

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported");
                Toast.makeText(this, "Text-to-speech language not supported", Toast.LENGTH_SHORT).show();
            } else {
                isTtsReady = true;
                textToSpeech.setSpeechRate(0.85f); // Slightly slower for better comprehension
                textToSpeech.setPitch(1.0f);
                readTextButton.setEnabled(true);
            }
        } else {
            Log.e(TAG, "TTS initialization failed");
            Toast.makeText(this, "Text-to-speech initialization failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void readTextAloud() {
        if (isTtsReady && !lastRecognizedText.isEmpty()) {
            // Stop any ongoing speech
            if (textToSpeech.isSpeaking()) {
                textToSpeech.stop();
            }

            // Read the text aloud
            textToSpeech.speak(lastRecognizedText, TextToSpeech.QUEUE_FLUSH, null, "tts1");
        } else if (lastRecognizedText.isEmpty()) {
            Toast.makeText(this, "No text to read", Toast.LENGTH_SHORT).show();
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
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        // Create CameraProvider (ProcessCameraProvider)
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();

                // Unbind any previously bound use cases
                cameraProvider.unbindAll();

                // Select the camera to use (back camera)
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // Setup the Preview use case (CameraX Preview)
                Preview preview = new Preview.Builder()
                        .build();

                // Attach the preview to the PreviewView
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Setup the ImageAnalysis use case for text recognition
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                // Bind the camera lifecycle to the cameraProvider
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera setup failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            Task<Text> result = textRecognizer.process(image)
                    .addOnSuccessListener(text -> {
                        // Process and display recognized text
                        lastRecognizedText = text.getText();
                        runOnUiThread(() -> textView.setText(lastRecognizedText));
                    })
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Text recognition failed", e)
                    )
                    .addOnCompleteListener(task ->
                            imageProxy.close()
                    );
        } else {
            imageProxy.close();
        }
    }

    @Override
    protected void onDestroy() {
        // Shut down TextToSpeech
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        // Shut down the executor service to avoid memory leaks
        cameraExecutor.shutdown();

        super.onDestroy();
    }
}