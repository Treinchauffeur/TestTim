package org.treinchauffeur.testtim;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import org.treinchauffeur.testtim.io.GeoJsonConverter;
import org.treinchauffeur.testtim.io.LocationLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Date;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MainActivity";
    private Activity mActivity;
    double threshold = 0;
    int viewport = 0;
    OverlayShowingService service;
    private final DisplayMetrics displayMetrics = new DisplayMetrics();
    //Screen recorder stuff
    public MediaProjection mediaProjection;
    public int REC_RQST_CODE = 1312;
    public MediaProjectionManager mediaManager;
    public VirtualDisplay virtualDisplay;
    public MediaRecorder mediaRecorder;
    private int SCREEN_WIDTH, SCREEN_HEIGHT;

    @SuppressWarnings("deprecation")//TODO use a newer api to get display metrics
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mediaManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaRecorder = new MediaRecorder();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        SCREEN_HEIGHT = displayMetrics.heightPixels;
        SCREEN_WIDTH = displayMetrics.widthPixels;

        mActivity = this;
        Button btnStartService = findViewById(R.id.btnStartService);
        Button exportLogs = findViewById(R.id.exportLogs);
        Button btnRec = findViewById(R.id.recInit);
        Button exportJSON = findViewById(R.id.exportJson);

        EditText etThreshold = findViewById(R.id.etThreshold);
        EditText etViewport = findViewById(R.id.etViewport);
        EditText exportText = findViewById(R.id.etExportDate);

        service = new OverlayShowingService();

        String[] permissions = {"android.permission.ACCESS_BACKGROUND_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
        };


        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, 1);
        }

        btnStartService.setOnClickListener(view -> {
            if (!etThreshold.getText().toString().equals(""))
                threshold = Double.parseDouble(etThreshold.getText().toString());
            else {
                etThreshold.setError("Need to set threshold");
                return;
            }
            if (!etViewport.getText().toString().equals(""))
                viewport = Integer.parseInt(etViewport.getText().toString());
            else {
                etViewport.setError("Need to set viewport");
                return;
            }
            checkDrawOverlayPermission();
        });

        exportText.setText(LocationLogger.dateFormatter.format(new Date()));

        exportLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                File file = new File(getFilesDir().getPath() + "/" + exportText.getText() + ".txt");
                Log.d(TAG, "onClick: fetching " + file.getPath());

                if (!file.exists()) {
                    Toast.makeText(mActivity, "File doesn't exist for this day; format DD-MM-YYYY", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "onClick: File doesn't exist.");
                    return;
                }

                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getApplicationContext().getPackageName() + ".provider", file);
                intent.setDataAndType(uri, "text/plain");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                startActivity(intent);
            }
        });

        exportJSON.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    File file = new File(getFilesDir().getPath() + "/" + exportText.getText() + ".txt");
                    Uri toConvert = FileProvider.getUriForFile(MainActivity.this, getApplicationContext().getPackageName() + ".provider", file);
                    GeoJsonConverter.readFile(toConvert, MainActivity.this);

                    File convertedFile = new File(getFilesDir().getPath() + "/" + exportText.getText() + "_json" + ".json");
                    FileOutputStream out = new FileOutputStream(convertedFile);
                    OutputStreamWriter writer = new OutputStreamWriter(out);

                    writer.write(GeoJsonConverter.convert());
                    writer.close();
                    out.close();

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getApplicationContext().getPackageName() + ".provider", convertedFile);
                    intent.setDataAndType(uri, "text/plain");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    startActivity(intent);
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
        });
    }

    public final static int OVERLAY_REQUEST_CODE = 251;

    @SuppressWarnings("deprecation")//TODO update to latest android standards
    public void checkDrawOverlayPermission() {
        if (!Settings.canDrawOverlays(mActivity)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_REQUEST_CODE);
        } else {
            openFloatingWindow();
        }
    }

    private void openFloatingWindow() {
        Intent intent = new Intent(mActivity, service.getClass());
        intent.putExtra("threshold", threshold);
        intent.putExtra("viewport", viewport);

        mActivity.stopService(intent);
        mActivity.startForegroundService(intent);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OVERLAY_REQUEST_CODE) {
            if (Settings.canDrawOverlays(mActivity)) {
                openFloatingWindow();
            }
        } else if (requestCode == REC_RQST_CODE) {
            mediaProjection = mediaManager.getMediaProjection(resultCode, data);
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                    SCREEN_WIDTH, SCREEN_HEIGHT, displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(), null, null);
            mediaRecorder.start();
        }
    }

   /* private void shareScreen() {
        startActivityForResult(mediaManager.createScreenCaptureIntent(), REC_RQST_CODE);
    }

    private void initRecorder() {
        if (mediaRecorder == null) {
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(SCREEN_WIDTH, SCREEN_HEIGHT);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setOutputFile(getFilePath());
            mediaRecorder.setVideoEncodingBitRate(512 * 1000);
        }
    }

    private void prepareRecorder() {
        try {
            mediaRecorder.prepare();
        } catch (IllegalStateException | IOException e) {
            e.printStackTrace();
        }
    }



    public String getFilePath() {
        final String directory = Environment.getExternalStorageDirectory() + File.separator + "Recordings";
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            Toast.makeText(this, "Failed to get External Storage", Toast.LENGTH_SHORT).show();
            return null;
        }
        final File folder = new File(directory);
        boolean success = true;
        if (!folder.exists()) {
            success = folder.mkdir();
            Log.d(TAG, "Folder not yet created, trying to mkdir at: " + Environment.getExternalStorageDirectory() + File.separator + "Recordings");

        }
        String filePath;
        if (success) {
            String videoName = ("capture_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".mp4");
            filePath = directory + File.separator + videoName;
        } else {
            Toast.makeText(this, "Failed!", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Failed mkdir at: " + Environment.getExternalStorageDirectory());
            try {
                Intent i = Intent.getIntent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
            return null;
        }
        return filePath;
    }*/

}