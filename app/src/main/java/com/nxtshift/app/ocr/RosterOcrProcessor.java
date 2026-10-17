package com.nxtshift.app.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * On-device ML Kit OCR execution engine.
 * Never transmits images or raw OCR text off the device.
 */
public class RosterOcrProcessor {
    private static final String TAG = "RosterOcrProcessor";

    public interface OcrCallback {
        void onSuccess(List<RosterTextParser.ExtractedShiftItem> shifts, String fullExtractedText);
        void onError(Exception e);
    }

    public interface FullOcrCallback {
        void onSuccess(Text visionText, String fullExtractedText);
        void onError(Exception e);
    }

    private final TextRecognizer recognizer;

    public RosterOcrProcessor() {
        // Bundled on-device Latin recognizer model
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public void processImageUri(@NonNull Context context, @NonNull Uri imageUri, int rotationDegrees, final OcrCallback callback) {
        try {
            InputImage inputImage = InputImage.fromFilePath(context, imageUri);
            runRecognition(inputImage, callback);
        } catch (IOException e) {
            Log.e(TAG, "Error loading image from URI: " + imageUri, e);
            if (callback != null) callback.onError(e);
        }
    }

    public void processBitmap(@NonNull Bitmap bitmap, int rotationDegrees, final OcrCallback callback) {
        try {
            InputImage inputImage = InputImage.fromBitmap(bitmap, rotationDegrees);
            runRecognition(inputImage, callback);
        } catch (Exception e) {
            Log.e(TAG, "Error processing bitmap", e);
            if (callback != null) callback.onError(e);
        }
    }

    public void processBitmapFull(@NonNull Bitmap bitmap, int rotationDegrees, final FullOcrCallback callback) {
        try {
            InputImage inputImage = InputImage.fromBitmap(bitmap, rotationDegrees);
            recognizer.process(inputImage)
                    .addOnSuccessListener(visionText -> {
                        if (callback != null) {
                            callback.onSuccess(visionText, visionText.getText());
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "ML Kit text recognition failed on-device", e);
                        if (callback != null) {
                            callback.onError(e);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error processing bitmap", e);
            if (callback != null) callback.onError(e);
        }
    }

    private void runRecognition(InputImage image, final OcrCallback callback) {
        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    LocalDate now = LocalDate.now();
                    List<RosterTextParser.ExtractedShiftItem> shifts =
                            RosterTextParser.parseMlKitText(visionText, now.getYear(), now.getMonthValue());
                    if (callback != null) {
                        callback.onSuccess(shifts, visionText.getText());
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "ML Kit text recognition failed on-device", e);
                    if (callback != null) {
                        callback.onError(e);
                    }
                });
    }

    public void close() {
        recognizer.close();
    }
}
