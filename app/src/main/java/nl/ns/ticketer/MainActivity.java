package nl.ns.ticketer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.snackbar.Snackbar;

import nl.ns.ticketer.io.GeoJsonConverter;
import nl.ns.ticketer.io.LocationLogger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = "MainActivity";
    private Activity mActivity;
    double threshold = 0;
    int viewport = 0;
    OverlayShowingService service;
    private final DisplayMetrics displayMetrics = new DisplayMetrics();
    //Screen recorder stuff

    public static final int JSON_REQUEST_CODE = 1759;

    EditText exportText;

    @SuppressWarnings("deprecation")//TODO use a newer api to get display metrics
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        mActivity = this;
        Button btnStartService = findViewById(R.id.btnStartService);
        Button exportLogs = findViewById(R.id.exportLogs);
        Button exportJSON = findViewById(R.id.exportJson);

        EditText etThreshold = findViewById(R.id.etThreshold);
        EditText etViewport = findViewById(R.id.etViewport);
        exportText = findViewById(R.id.etExportDate);

        service = new OverlayShowingService();

        exportText.setText(LocationLogger.dateFormatter.format(new Date()));

        exportLogs.setOnClickListener(view -> {
            File file = new File(getFilesDir().getPath() + "/" + exportText.getText() + LocationLogger.logFileSuffix);
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
        });

        exportJSON.setOnClickListener(view -> {
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, exportText.getText() + LocationLogger.jsonFileSuffix);

                startActivityForResult(intent, JSON_REQUEST_CODE);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

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
    }

    public final static int OVERLAY_REQUEST_CODE = 251;

    @SuppressWarnings("deprecation")//TODO update to latest android standards
    public void checkDrawOverlayPermission() {
        if (!Settings.canDrawOverlays(mActivity)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_REQUEST_CODE);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            String[] permissions = {/*"android.permission.ACCESS_BACKGROUND_LOCATION",*/ //Apparently we don't actually need this one?? TODO
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    "android.permission.POST_NOTIFICATIONS"
            };
            requestPermissions(permissions, 6543);
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
        } else if (requestCode == JSON_REQUEST_CODE) {
            if(data == null) {
                Snackbar.make(findViewById(android.R.id.content).getRootView(), "Er is een fout opgetreden!", Snackbar.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(mActivity, "Success!", Toast.LENGTH_SHORT).show();
            try {
                File file = new File(getFilesDir().getPath() + "/" + exportText.getText() + LocationLogger.logFileSuffix);
                Uri toConvert = FileProvider.getUriForFile(MainActivity.this, getApplicationContext().getPackageName() + ".provider", file);
                GeoJsonConverter.readFile(toConvert, MainActivity.this);

                OutputStream out = getContentResolver().openOutputStream(Objects.requireNonNull(data.getData()));
                assert out != null;
                out.write(GeoJsonConverter.convert().getBytes());
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}