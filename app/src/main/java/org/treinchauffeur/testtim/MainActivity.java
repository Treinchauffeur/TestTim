package org.treinchauffeur.testtim;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private Activity mActivity;
    double threshold = 0;
    int viewport = 0;
    OverlayShowingService service;
    private boolean simpleCalculations;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mActivity = this;
        Button btnStartService = (Button) findViewById(R.id.btnStartService);
        Button btnClose = (Button) findViewById(R.id.btnClose);

        EditText etThreshold = findViewById(R.id.etThreshold);
        EditText etViewport = findViewById(R.id.etViewport);
        service = new OverlayShowingService();

        CheckBox cbSimple = findViewById(R.id.simpleCalculations);
        simpleCalculations = cbSimple.isChecked();

        String[] permissions = {"android.permission.ACCESS_BACKGROUND_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.SYSTEM_ALERT_WINDOW",
        };


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions, 1);
        }


        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mActivity.finish();
            }
        });

        btnStartService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
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
            }
        });

    }

    public final static int OVERLAY_REQUEST_CODE = 251;

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
        intent.putExtra("simpleCalculations", simpleCalculations);

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
        }
    }

}