package org.treinchauffeur.testtim;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssAntennaInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Date;
import java.util.List;

public class OverlayShowingService extends Service implements SensorEventListener {

    private Context context;
    private WindowManager mWindowManager;
    private View overlayView;
    private LocationManager locationManager;
    private WindowManager.LayoutParams mWindowsParams;
    private GnssStatus.Callback status;
    private SensorManager sensorManager;
    private Sensor sensor;
    public static final String TAG = "OverLayService";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

        drawLayout(intent);
        placeView();
        doLocationInfo();
        doSensorInfo();

        return START_STICKY;
    }

    private void doSensorInfo() {
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onDestroy() {

        if (overlayView != null) {
            mWindowManager.removeView(overlayView);
        }
        super.onDestroy();
    }

    private void placeView() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = (int) (metrics.widthPixels * 0.7f);
        int height = (int) (metrics.heightPixels * 0.7f);

        mWindowsParams = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);


        mWindowsParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        mWindowsParams.x = 30;
        mWindowsParams.y = 30;
        mWindowManager.addView(overlayView, mWindowsParams);


    }


    private void drawLayout(Intent intent) {

        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        overlayView = layoutInflater.inflate(R.layout.overlay_window, null);
        TextView btnClose = overlayView.findViewById(R.id.btnClose);
        TextView btnRec = overlayView.findViewById(R.id.btnRec);

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopSelf();
            }
        });

        btnRec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //TODO Doesn't work.. frustratingly..
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.samsung.android.app.smartcapture");
                startActivity(launchIntent);
            }
        });

    }


    private void doLocationInfo() {
        ViewGroup graph = overlayView.findViewById(R.id.signalStrengthGraph);
        status = new GnssStatus.Callback() {
            @Override
            public void onStarted() {
                super.onStarted();
            }

            @Override
            public void onStopped() {
                super.onStopped();
            }

            @Override
            public void onFirstFix(int ttffMillis) {
                super.onFirstFix(ttffMillis);
            }

            @Override
            public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                super.onSatelliteStatusChanged(status);

                //Satellite graph
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    if (graph.getChildAt(i) != null) {
                        if (status.getCn0DbHz(i) > 0) {

                            //coloring the bars
                            if (status.getCn0DbHz(i) >= 30)
                                graph.getChildAt(i).setBackgroundColor(Color.parseColor("#00ff00"));
                            else if (status.getCn0DbHz(i) >= 20 && status.getCn0DbHz(i) < 30)
                                graph.getChildAt(i).setBackgroundColor(Color.parseColor("#ffff00"));
                            else if (status.getCn0DbHz(i) < 20)
                                graph.getChildAt(i).setBackgroundColor(Color.parseColor("#ff0000"));

                            //Setting layout height when signal > 0;
                            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) graph.getChildAt(i).getLayoutParams();
                            lp.height = (int) status.getCn0DbHz(i) * 8; //multiplier to fit graph better
                            graph.getChildAt(i).setLayoutParams(lp);
                        } else {
                            //if signal 0, red graph bar & zero height
                            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) graph.getChildAt(i).getLayoutParams();
                            lp.height = 1;
                            graph.getChildAt(i).setLayoutParams(lp);
                            graph.getChildAt(i).setBackgroundColor(Color.parseColor("#ff0000"));
                        }
                    }
                }

                Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

                TextView tvFixType = overlayView.findViewById(R.id.tvFixType);
                TextView tvLatLong = overlayView.findViewById(R.id.tvLatLong);
                TextView tvAccuracy = overlayView.findViewById(R.id.tvAccuracy);
                TextView tvSpeed = overlayView.findViewById(R.id.tvSpeed);

                tvLatLong.setText("Lat/long: "+
                        location.getLatitude() +", "+ location.getLongitude());
                tvAccuracy.setText("Accuracy: "+location.getAccuracy());

                if(location.hasSpeed())
                tvSpeed.setText("Speed (GPS): "+(int) (location.getSpeed() / 3.6)+" km/h");
                else tvSpeed.setText("Speed (GPS): NaN");

                String fixTypeText = "GNSS fix: ";
                if ((System.currentTimeMillis() - location.getTime()) > 2000) {
                    tvFixType.setText(fixTypeText + "None");
                    tvFixType.setTextColor(Color.parseColor("#FF0000"));
                } else if (!location.hasAltitude() || location.getAltitude() >= 200) {
                    tvFixType.setText(fixTypeText + "2D");
                    tvFixType.setTextColor(Color.parseColor("#FFFF00"));
                } else if (location.getAltitude() < 200) {
                    tvFixType.setText(fixTypeText + "3D");
                    tvFixType.setTextColor(Color.parseColor("#00FF00"));
                } else {
                    tvFixType.setText(fixTypeText + "ERROR");
                    tvFixType.setTextColor(Color.parseColor("#FF0000"));
                }
            }
        };

        LocationListener locationListener = new LocationListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onLocationChanged(@NonNull Location location) {
                //Not sure if necessary, might duplicate calls
                /*
                TextView tvFixType = overlayView.findViewById(R.id.tvFixType);
                TextView tvLatLong = overlayView.findViewById(R.id.tvLatLong);
                TextView tvAccuracy = overlayView.findViewById(R.id.tvAccuracy);
                TextView tvSpeed = overlayView.findViewById(R.id.tvSpeed);

                tvLatLong.setText("Lat/long: "+
                        location.getLatitude() +", "+ location.getLongitude());
                tvAccuracy.setText("Accuracy: "+location.getAccuracy());

                if(location.hasSpeed())
                    tvSpeed.setText("Speed: "+location.getSpeed() / 3.6+" km/h");
                else tvSpeed.setText("Speed: NaN");

                String fixTypeText = "GNSS fix: ";
                if ((System.currentTimeMillis() - location.getTime()) > 2000) {
                    tvFixType.setText(fixTypeText + "None");
                    tvFixType.setTextColor(Color.parseColor("#FF0000"));
                } else if (!location.hasAltitude() || location.getAltitude() >= 200) {
                    tvFixType.setText(fixTypeText + "2D");
                    tvFixType.setTextColor(Color.parseColor("#FFFF00"));
                } else if (location.getAltitude() < 200) {
                    tvFixType.setText(fixTypeText + "3D");
                    tvFixType.setTextColor(Color.parseColor("#00FF00"));
                } else {
                    tvFixType.setText(fixTypeText + "ERROR");
                    tvFixType.setTextColor(Color.parseColor("#FF0000"));
                }
                */
            }
        };
        locationManager.registerGnssStatusCallback(status);
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500, 1, locationListener);
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        TextView tvAccel = overlayView.findViewById(R.id.tvAccel);
        if(sensorEvent.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            tvAccel.setText(""+ sensorEvent.values[0]);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }
}
