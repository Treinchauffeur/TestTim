package org.treinchauffeur.testtim;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.RingtoneManager;
import android.net.Uri;
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
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.jjoe64.graphview.*;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

public class OverlayShowingService extends Service implements SensorEventListener, LocationListener {

    private Context context;
    private WindowManager mWindowManager;
    private View overlayView;
    private LocationManager locationManager;
    private WindowManager.LayoutParams mWindowsParams;
    private GnssStatus.Callback status;
    private SensorManager sensorManager;
    private Sensor sensor;
    public static final String TAG = "OverLayService";

    private GraphView graphAccel;
    private LineGraphSeries<DataPoint> mSeriesAccelX, mSeriesAccelY, mSeriesAccelZ;
    private double graphLastAccelXValue = 5d;

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
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        createNotification();
        drawLayout(intent);
        placeView();
        doLocationInfo();
        doGraphSetup();

        return START_STICKY;
    }

    /**
     * Necessary in order to make service operate in background; when mainactivity is closed
     */
    private void createNotification() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 251, intent, PendingIntent.FLAG_MUTABLE);

        String CHANNEL_ID = "Persistence";
        String CHANNEL_NAME = "Persistent notification";

        NotificationCompat.Builder builder = null;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notificationManager.createNotificationChannel(channel);
        builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID);
        builder.setChannelId(CHANNEL_ID);
        builder.setBadgeIconType(NotificationCompat.BADGE_ICON_NONE);

        builder.setContentTitle("TestTim");
        builder.setContentText("Stays active in background");
        Uri notificationSound = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION);
        builder.setSound(notificationSound);
        builder.setAutoCancel(false);
        builder.setSmallIcon(R.drawable.ic_launcher_foreground);
        builder.setContentIntent(pendingIntent);
        Notification notification = builder.build();
        startForeground(251, notification);
    }

    private void doGraphSetup() {
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        graphAccel = initGraph(R.id.graphAccel, "m/s^2");
        graphAccel.getLegendRenderer().setVisible(false);
        graphAccel.setTitleColor(Color.WHITE);
        graphAccel.setTitleTextSize(8);
        graphAccel.getGridLabelRenderer().setTextSize(12);
        graphAccel.getGridLabelRenderer().setHorizontalAxisTitleColor(Color.WHITE);
        graphAccel.getGridLabelRenderer().setVerticalAxisTitleColor(Color.WHITE);
        graphAccel.getGridLabelRenderer().setVerticalLabelsColor(Color.WHITE);
        graphAccel.getGridLabelRenderer().setHorizontalLabelsColor(Color.WHITE);
        graphAccel.getGridLabelRenderer().setVerticalLabelsColor(Color.WHITE);
        graphAccel.getGridLabelRenderer().setHorizontalLabelsColor(Color.WHITE);
        graphAccel.getGridLabelRenderer().reloadStyles();


        mSeriesAccelX = initSeries(Color.BLUE, "X");
        mSeriesAccelY = initSeries(Color.RED, "Y");
        mSeriesAccelZ = initSeries(Color.GREEN, "Z");

        graphAccel.addSeries(mSeriesAccelX);
        graphAccel.addSeries(mSeriesAccelY);
        graphAccel.addSeries(mSeriesAccelZ);

        startAccel();
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
        int width = 534;
        int height = 400;

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
                //TODO Doesn't work..
                // I don't think there is a way to do this properly. Might have to do this in-app...
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.samsung.android.app.screenrecorder");
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
                            else if (status.getCn0DbHz(i) >= 15 && status.getCn0DbHz(i) < 30)
                                graph.getChildAt(i).setBackgroundColor(Color.parseColor("#ffff00"));
                            else if (status.getCn0DbHz(i) < 15)
                                graph.getChildAt(i).setBackgroundColor(Color.parseColor("#ff0000"));

                            //Setting layout height when signal > 0;
                            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) graph.getChildAt(i).getLayoutParams();
                            lp.height = (int) (graph.getHeight() * (status.getCn0DbHz(i) / 45));

                            graph.getChildAt(i).setLayoutParams(lp);
                        } else {
                            //if signal 0, red graph bar & zero height
                            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) graph.getChildAt(i).getLayoutParams();
                            lp.height = 2;
                            graph.getChildAt(i).setLayoutParams(lp);
                            graph.getChildAt(i).setBackgroundColor(Color.parseColor("#ff0000"));
                        }
                    }
                }

                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

                TextView tvFixType = overlayView.findViewById(R.id.tvFixType);
                TextView tvLatLong = overlayView.findViewById(R.id.tvLatLong);
                TextView tvAccuracy = overlayView.findViewById(R.id.tvAccuracy);
                TextView tvSpeed = overlayView.findViewById(R.id.tvSpeed);

                tvLatLong.setText("Lat/long: " +
                        location.getLatitude() + ", " + location.getLongitude());
                tvAccuracy.setText("Accuracy: " + (int) location.getAccuracy() + "m");

                if (location.getAccuracy() > 50)
                    tvAccuracy.setTextColor(Color.parseColor("#FF0000"));
                else
                    tvAccuracy.setTextColor(Color.WHITE);

                if (location.hasSpeed())
                    tvSpeed.setText("Speed (GPS): " + (int) (location.getSpeed() / 3.6) + " km/h");
                else tvSpeed.setText("Speed (GPS): NaN");

                String fixTypeText = "GNSS fix: ";
                if ((System.currentTimeMillis() - location.getTime()) > 2000) {
                    tvFixType.setText(fixTypeText + "None; fix data too old");
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        locationManager.registerGnssStatusCallback(context.getMainExecutor(), status);
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000, 1, context.getMainExecutor(), this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if(event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            graphLastAccelXValue += 0.15d;
            int multiplier = 3;
            mSeriesAccelX.appendData(new DataPoint(graphLastAccelXValue, event.values[0] * multiplier), true, 33);
            mSeriesAccelY.appendData(new DataPoint(graphLastAccelXValue, event.values[1] * multiplier), true, 33);
            mSeriesAccelZ.appendData(new DataPoint(graphLastAccelXValue, event.values[2] * multiplier), true, 33);
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        //Might be a duplicate call, but probably stil necessary
    }

    public GraphView initGraph(int id, String title) {
        GraphView graph = (GraphView) overlayView.findViewById(id);
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(5);
        graph.getGridLabelRenderer().setLabelVerticalWidth(100);
        graph.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        graph.getLegendRenderer().setVisible(true);
        return graph;
    }

    public LineGraphSeries<DataPoint> initSeries(int color, String title) {
        LineGraphSeries<DataPoint> series;
        series = new LineGraphSeries<>();
        series.setDrawDataPoints(true);
        series.setDataPointsRadius(1);
        series.setDrawBackground(false);
        series.setColor(color);
        series.setTitle(title);
        return series;
    }

    public void startAccel(){
        sensorManager = (SensorManager) this.getSystemService(Context.SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }
}
