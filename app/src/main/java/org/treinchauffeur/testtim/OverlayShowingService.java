package org.treinchauffeur.testtim;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.PowerManager;
import android.util.Log;
import android.view.ViewGroup;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.jjoe64.graphview.*;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;


import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Locale;

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
    public static final String TAG = "OverLayService";

    private GraphView graphAccel;
    private LineGraphSeries<DataPoint> mSeriesAccelX, mSeriesAccelY, mSeriesAccelZ;
    private double graphLastAccelXValue = 5d;
    private boolean placedLeft = false;
    private TextView graphText;
    double accelerometerThreshold = 0;
    int viewport;
    double xCorrector = 0, yCorrector = 0;
    float uncorrectedX = 0, uncorrectedY = 0;
    float finalX = 0, finalY = 0;
    private PowerManager.WakeLock wakeLock;

    private boolean simpleCalculations = true; //debugging

    private float speed = 0, speedKmh = 0;
    private boolean hasGPSFix;

    private MediaProjection recordingMediaProjection;


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
        //TODO setup recorder

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
        TextView btnCalibrate = overlayView.findViewById(R.id.calibrate);
        TextView btnResetCalibration = overlayView.findViewById(R.id.calibrateReset);
        graphText = overlayView.findViewById(R.id.graphText);

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopSelf();
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

        TextView recStart = overlayView.findViewById(R.id.tvRec);
        recStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                recordingStart();
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


    //Handles all the location data. Everything from displaying the GNSS data to getting and calculating the speed
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

            //We don't do anything with this
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

                            //Setting layout height of individual bars when signal > 0;
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

                //If we don't have location permissions, request them from the user
                //TODO Is this the right place?
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, "Please allow location permissions in app settings", Toast.LENGTH_SHORT).show();
                    return;
                }
                //After restarting device, lastknownlocation is pretty much always null.
                //Network provider is most likely to have lkl.
                Location location = null;
                if (locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                } else {
                    location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }


                hasGPSFix = true;
                String fixTypeText = "GNSS fix: ";
                if ((System.currentTimeMillis() - location.getTime()) > 2000) {
                    //The last known fix is too old, and therefor we currently we don't have one
                    tvFixType.setText(fixTypeText + "false");
                    tvFixType.setTextColor(Color.parseColor("#FF0000"));
                    tvLatLong.setTextColor(Color.parseColor("#88FFFFFF"));
                    tvElevation.setTextColor(Color.parseColor("#88FFFFFF"));
                    tvAccuracy.setTextColor(Color.parseColor("#88FFFFFF"));
                    tvSpeed.setTextColor(Color.parseColor("#88FFFFFF"));
                    hasGPSFix = false;
                } else if (!location.hasAltitude() || location.getAltitude() >= 200) {
                    //If we don't have altitude, or it's unreasonably high (let's say 200m for now, we consider this to be in error), fix is 2D.
                    tvFixType.setText(fixTypeText + "2D");
                    tvFixType.setTextColor(Color.parseColor("#FFFF00"));
                } else if (location.getAltitude() < 200) {
                    //Best-case scenario. We have altitude, and we consider this to have a reasonable value.
                    tvFixType.setText(fixTypeText + "3D");
                    tvFixType.setTextColor(Color.parseColor("#00FF00"));
                } else {
                    //So we have altitude, which is lower than 200m.
                    tvFixType.setText(fixTypeText + "ERROR");
                    tvFixType.setTextColor(Color.parseColor("#FF0000"));
                }


                if (hasGPSFix) {
                    //We deliberately display the lat/long even when there is no current GNSS Fix available,
                    //so that we can see WHERE the signal was lost
                    tvLatLong.setTextColor(Color.WHITE);
                    tvLatLong.setText("Pos: " +
                            location.getLatitude() + ", " + location.getLongitude());

                    //We assume that the location is inaccurate when the altitude is above 200m
                    //I mean this is the Netherlands after all
                    if (location.hasAltitude()) {
                        tvElevation.setText("Elevation: " + (int) location.getAltitude() + "m");
                        if (location.getAltitude() >= 200)
                            tvElevation.setTextColor(Color.parseColor("#FF0000"));
                        else
                            tvElevation.setTextColor(Color.WHITE);

                    }

                    //TimTim won't use the location when accuracy exceeds 50m, so we DO use it, but draw it in red
                    tvAccuracy.setText("Accuracy: " + (int) location.getAccuracy() + "m");
                    if (location.getAccuracy() > 50)
                        tvAccuracy.setTextColor(Color.parseColor("#FF0000"));
                    else
                        tvAccuracy.setTextColor(Color.WHITE);

                    //Pretty basic, displays the GNSS-provided speed
                    if (location.hasSpeed()) {
                        setSpeed(location.getSpeed());
                        tvSpeed.setText("Speed (GPS): " + (int) speedKmh + " km/h");
                        tvSpeed.setTextColor(Color.WHITE);
                    } else {
                        setSpeed(-1);
                        tvSpeed.setText("Speed (GPS): NaN");
                        tvSpeed.setTextColor(Color.parseColor("#88FFFFFF"));
                    }
                }
            }
        };

        //Registers a GNSS callback listener (GnssStatus.Callback status) & generic location listener (this class)
        //TODO is this the right place for this specific call?
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

            /* Sensor data vs the various tablet directions of movement
             * 0: up and down
             * 1: side to side
             * 2: back and forth
             */

            //puts values in global fields
            setXY(event.values[1], event.values[2]);

            //Calibration correction; setting the zero-point because using the linear-acceleration sensor (which negates gravity) is slow
            if (xCorrector != 0) {
                finalX = (float) (uncorrectedX - xCorrector);
                finalY = (float) (uncorrectedY - yCorrector);
            } else {
                finalX = (float) (uncorrectedX);
                finalY = (float) (uncorrectedY);
            }

            //We want to get the diagonal acceleration force since the tablet is pretty much always angled at 45 degrees in the direction of travel.
            //By doing this we also negate side-to-side movement of the train in switches & other curves / the shitshow we call 'stable' ground.
            //TODO Use tangent to calculate force instead of doing this
            diagonalAxisInput = placedLeft ? ((finalY - (finalX * -1)) * -1) : ((finalY - finalX) * -1);

            //Applying low-pass-filter to data for smoothing.
            dataToAverage = dataToAverage + ALPHA * (diagonalAxisInput - dataToAverage);
            float average = dataToAverage;

            //Apply data to graph.
            graphLastAccelXValue += 0.15d;
            mSeriesAccelX.appendData(new DataPoint(graphLastAccelXValue, 0), true, 33);
            mSeriesAccelY.appendData(new DataPoint(graphLastAccelXValue, average), true, 33);
            mSeriesAccelZ.appendData(new DataPoint(graphLastAccelXValue, 0), true, 33);
        }

        //Shows 'train is moving' on graph when acceleration forces are felt, the threshold being supplied by host activity
        Iterator<DataPoint> viewableData = mSeriesAccelY.getValues(mSeriesAccelY.getHighestValueX() - 5, mSeriesAccelY.getHighestValueX());
        double highestYViewable = 0;
        double lowestYViewable = 0;

        //Hate doing this, but the GraphView library apparently doesn't provide a better way
        //And as suspected, #TODO a bug where it always only returns one correct value, the other one is always incorrect..
        for (Iterator<DataPoint> it = viewableData; it.hasNext(); ) {
            DataPoint point = it.next();
            if (point.getY() > highestYViewable)
                highestYViewable = point.getY();
            if (point.getY() < lowestYViewable)
                lowestYViewable = point.getY();
        }

        //If acceleration is measured, display this to the user.
        //Ignores accelerometer when GPS-given speed exceeds 5m/s
        //Now making distinctions between accelerating, decelerating and just moving
        graphText.setText(getIsAcceleratingText(highestYViewable, lowestYViewable));
    }

    //Setup the graph style, and start listening for accelerometer value changes.
    //We have an on-board onchangelistener implemented for this reason.
    private void doGraphSetup() {
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

        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);

    }

    private void setXY(float x, float y) {
        uncorrectedX = x;
        uncorrectedY = y;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        //Required method, ignore.
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        //Might be a duplicate call, but probably stil necessary
    }

    //Initiate the Graph
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

    //Initiate the series (individual lines, of which we only use one)
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

    //Setting the speed in a global value.
    public void setSpeed(float speed) {
        this.speed = speed;
        this.speedKmh = (float) (speed * 3.6);
    }


    //Honestly don't get me started
    private void recordingStart() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String timeString = formatter.format(Calendar.getInstance().getTime());

        MediaProjectionManager recordingMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (recordingMediaProjection != null) {
            recordingMediaProjection.stop();
        }
    }

    //Gets the  text to display on the graph based on whether we have GPS (and therefor a known speed), or the accelerometer
    //that the train is generally moving, or specifically accelerating, decelerating or stationary.
    private String getIsAcceleratingText(double highest, double lowest) {
        if (hasGPSFix && speed > 5)
            return "V > 5 m/s";
        else if (lowest > 0.3 && highest > 0.3)
            return "Train is accelerating";
        else if (highest < -0.3 && lowest < -0.3)
            return "Train is decelerating";
        else if (highest - lowest > accelerometerThreshold) {
            //Log.d(TAG, "getIsAcceleratingText: "+highest +" "+ lowest); //Debug the highest/lowest values bug
            return "Train is moving";
        } else
            return "Train is stationary";
    }

}
