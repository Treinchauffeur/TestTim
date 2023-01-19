package org.treinchauffeur.testtim;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.os.PowerManager;
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
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.jjoe64.graphview.*;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.treinchauffeur.testtim.io.ScreenRecorder;

import java.util.Iterator;

public class OverlayShowingService extends Service implements SensorEventListener, LocationListener {

    private Context context;
    private WindowManager mWindowManager;
    private PowerManager powerManager;
    private View overlayView;
    private LocationManager locationManager;
    private WindowManager.LayoutParams mWindowsParams;
    private GnssStatus.Callback status;
    private SensorManager sensorManager;
    private Sensor sensor;
    private ScreenRecorder screenRecorder;
    public static final String TAG = "OverLayService";

    private GraphView graphAccel;
    private LineGraphSeries<DataPoint> mSeriesAccelX, mSeriesAccelY, mSeriesAccelZ;
    private double graphLastAccelXValue = 5d;
    private boolean placedLeft = false;
    private TextView graphHider;
    double accelerometerThreshold = 0;
    int viewport;
    double xCorrector = 0, yCorrector = 0;
    float uncorrectedX = 0, uncorrectedY = 0;
    float finalX = 0, finalY = 0;
    private PowerManager.WakeLock wakeLock;

    private boolean simpleCalculations = true; //debugging

    private float speed = 0, speedKmh = 0;
    private boolean hasGPSFix;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
    }

    @SuppressLint("WakelockTimeout")
//As long as service is active, we don't want the screen to timeout.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        accelerometerThreshold = intent.getDoubleExtra("threshold", 0);
        simpleCalculations = intent.getBooleanExtra("simpleCalculations", true);
        viewport = intent.getIntExtra("viewport", 2);

        //Because passing a KEEP_SCREEN_ON parameter to a window service (inst of an Activity) is apparently illegal.
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "TestTim: Main WakeLock");
        wakeLock.acquire();

        createNotification();
        drawLayout(intent);
        setButtonActions();
        placeView();
        doLocationInfo();
        doGraphSetup();
        setupRecorder();

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

    @Override
    public void onDestroy() {
        if (overlayView != null)
            mWindowManager.removeView(overlayView);
        if (screenRecorder != null)
            screenRecorder.onDestroy();
        if (wakeLock != null)
            wakeLock.release();

        super.onDestroy();
    }


    private void drawLayout(Intent intent) {
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        overlayView = layoutInflater.inflate(R.layout.overlay_window, null);
    }

    private void setButtonActions() {
        TextView btnClose = overlayView.findViewById(R.id.btnClose);
        TextView btnRec = overlayView.findViewById(R.id.btnRec);
        TextView btnCalibrate = overlayView.findViewById(R.id.calibrate);
        TextView btnResetCalibration = overlayView.findViewById(R.id.calibrateReset);
        graphHider = overlayView.findViewById(R.id.graphHider);

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopSelf();
            }
        });

        Chronometer chronometer = overlayView.findViewById(R.id.tvRec);
        btnRec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //chronometer.start(); //Yeahhhhhh let's not
                //TODO everything below.. Probably have to start figuring out local recording.
                //Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.samsung.android.app.screenrecorder");
                //startActivity(launchIntent);
                Toast.makeText(context, "Doesn't work yet, just counts..", Toast.LENGTH_SHORT).show();
            }
        });

        //Tablet placement in cab, inverts accelerometer outputs
        TextView btnLeft = overlayView.findViewById(R.id.placedLeft);
        TextView btnRight = overlayView.findViewById(R.id.placedRight);

        btnLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                btnLeft.setTextColor(Color.WHITE);
                btnRight.setTextColor(Color.parseColor("#888888"));
                placedLeft = true;
            }
        });
        btnRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                btnLeft.setTextColor(Color.parseColor("#888888"));
                btnRight.setTextColor(Color.WHITE);
                placedLeft = false;
            }
        });

        btnCalibrate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                xCorrector = uncorrectedX;
                yCorrector = uncorrectedY;
                btnCalibrate.setTextColor(Color.parseColor("#888888"));
            }
        });
        btnResetCalibration.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                xCorrector = 0;
                yCorrector = 0;
                btnCalibrate.setTextColor(Color.WHITE);
            }
        });

    }

    private void placeView() {
        int width = 534;
        int height = 500;

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

        TextView tvMove = overlayView.findViewById(R.id.btnMove);
        final boolean[] posLeftBtm = {true};

        tvMove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (posLeftBtm[0]) {
                    Log.d(TAG, "test");
                    mWindowsParams.gravity = Gravity.TOP | Gravity.RIGHT;
                    mWindowsParams.x = 30;
                    mWindowsParams.y = 100;
                    mWindowManager.updateViewLayout(overlayView, mWindowsParams);
                    posLeftBtm[0] = false;
                } else {
                    Log.d(TAG, "test2");
                    mWindowsParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
                    mWindowsParams.x = 30;
                    mWindowsParams.y = 30;
                    mWindowManager.updateViewLayout(overlayView, mWindowsParams);
                    posLeftBtm[0] = true;
                }
            }
        });
    }


    private void doLocationInfo() {
        ViewGroup graph = overlayView.findViewById(R.id.signalStrengthGraph);
        TextView tvFixType = overlayView.findViewById(R.id.tvFixType);
        TextView tvLatLong = overlayView.findViewById(R.id.tvLatLong);
        TextView tvElevation = overlayView.findViewById(R.id.tvElevation);
        TextView tvAccuracy = overlayView.findViewById(R.id.tvAccuracy);
        TextView tvSpeed = overlayView.findViewById(R.id.tvSpeed);
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
                    Toast.makeText(context, "Please allow location permissions in app settings", Toast.LENGTH_SHORT).show();
                    return;
                }
                Location location = null;
                if (locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                } else {
                    location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }


                hasGPSFix = true;
                String fixTypeText = "GNSS fix: ";
                if ((System.currentTimeMillis() - location.getTime()) > 2000) {
                    tvFixType.setText(fixTypeText + "false; fix data too old");
                    tvFixType.setTextColor(Color.parseColor("#FF0000"));
                    tvLatLong.setTextColor(Color.parseColor("#88FFFFFF"));
                    tvElevation.setTextColor(Color.parseColor("#88FFFFFF"));
                    tvAccuracy.setTextColor(Color.parseColor("#88FFFFFF"));
                    tvSpeed.setTextColor(Color.parseColor("#88FFFFFF"));
                    hasGPSFix = false;
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

                if (hasGPSFix) {
                    tvLatLong.setTextColor(Color.WHITE);
                    tvLatLong.setText("Lat/long: " +
                            location.getLatitude() + ", " + location.getLongitude());

                    if (location.hasAltitude()) {
                        tvElevation.setText("Elevation: " + (int) location.getAltitude() + "m");
                        if (location.getAltitude() >= 200)
                            tvElevation.setTextColor(Color.parseColor("#FF0000"));
                        else
                            tvElevation.setTextColor(Color.WHITE);

                    }
                    tvAccuracy.setText("Accuracy: " + (int) location.getAccuracy() + "m");

                    if (location.getAccuracy() > 50)
                        tvAccuracy.setTextColor(Color.parseColor("#FF0000"));
                    else
                        tvAccuracy.setTextColor(Color.WHITE);

                    if (location.hasSpeed()) {
                        setSpeed(location.getSpeed());
                        tvSpeed.setText("Speed (GPS): " + (int) speedKmh + " km/h");
                        tvSpeed.setTextColor(Color.WHITE);
                    } else {
                        tvSpeed.setText("Speed (GPS): NaN");
                        tvSpeed.setTextColor(Color.parseColor("#88FFFFFF"));
                    }
                }
            }
        };
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Please allow location permissions in app settings", Toast.LENGTH_SHORT).show();
            return;
        }
        locationManager.registerGnssStatusCallback(context.getMainExecutor(), status);
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500, 1, context.getMainExecutor(), this);
    }

    float dataToAverage = 0;
    float diagonalAxisInput = 0;
    float ALPHA = 0.2f;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

            //0: up and down
            //1: side to side
            //2: back and forth

            setXY(event.values[1], event.values[2]);

            //Calibration correction; setting the zero-point because using linear-acceleration is slow
            if (xCorrector != 0) {
                finalX = (float) (uncorrectedX - xCorrector);
                finalY = (float) (uncorrectedY - yCorrector);
            } else {
                finalX = (float) (uncorrectedX);
                finalY = (float) (uncorrectedY);
            }

            if (!simpleCalculations) {
                diagonalAxisInput = placedLeft ? ((finalY - (finalX * -1)) * -1) : ((finalY - finalX) * -1);
            } else {
                diagonalAxisInput = finalY;
            }

            dataToAverage = dataToAverage + ALPHA * (diagonalAxisInput - dataToAverage); //Low-pass filter
            float average = dataToAverage;

            graphLastAccelXValue += 0.15d;
            mSeriesAccelX.appendData(new DataPoint(graphLastAccelXValue, 0), true, 33);
            mSeriesAccelY.appendData(new DataPoint(graphLastAccelXValue, average), true, 33);
            mSeriesAccelZ.appendData(new DataPoint(graphLastAccelXValue, 0), true, 33);
        }

        //Shows 'train is moving' on graph when acceleration forces are fel, the threshold being supplied by host activity
        Iterator<DataPoint> viewableData = mSeriesAccelY.getValues(mSeriesAccelY.getHighestValueX() - 3, mSeriesAccelY.getHighestValueX());
        double highestYViewable = 0;
        double lowestYViewable = 0;

        //Hate doing this, but the GraphView library apparently doesn't provide a better way
        for (Iterator<DataPoint> it = viewableData; it.hasNext(); ) {
            DataPoint point = it.next();
            if (point.getY() > highestYViewable)
                highestYViewable = point.getY();
            if (point.getY() < lowestYViewable)
                lowestYViewable = point.getY();
        }

        if (accelerometerThreshold > 0) {
            if (highestYViewable - lowestYViewable > accelerometerThreshold) {
                graphHider.setVisibility(View.VISIBLE);
            } else {
                graphHider.setVisibility(View.GONE);
            }
        }
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


        mSeriesAccelX = initSeries(Color.BLACK, "X"); //back and forth
        mSeriesAccelY = initSeries(Color.RED, "Y");//side to side
        mSeriesAccelZ = initSeries(Color.BLACK, "Z"); //up and down

        graphAccel.addSeries(mSeriesAccelX);
        graphAccel.addSeries(mSeriesAccelY);
        graphAccel.addSeries(mSeriesAccelZ);


        graphAccel.getViewport().setMinY((double) viewport * -1);
        graphAccel.getViewport().setMaxY((double) viewport);
        graphAccel.getViewport().setYAxisBoundsManual(true);

        startAccel();
    }

    private void setXY(float x, float y) {
        uncorrectedX = x;
        uncorrectedY = y;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        //Might be a duplicate call, but probably stil necessary
    }

    public GraphView initGraph(int id, String title) {
        GraphView graph = overlayView.findViewById(id);
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

    public void startAccel() {
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    public void setSpeed(float speed) {
        this.speed = speed;
        this.speedKmh = (float) (speed * 3.6);
    }

    private void setupRecorder() {
        screenRecorder = new ScreenRecorder(this, overlayView);
        screenRecorder.setupRecorder();
    }
}
