package org.treinchauffeur.testtim;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.treinchauffeur.testtim.io.LocationLogger;

import java.util.Iterator;

public class OverlayShowingService extends Service implements SensorEventListener, LocationListener {

    private Context context;
    private WindowManager mWindowManager;
    private View overlayView;
    private LocationManager locationManager;
    private DisplayManager displayManager;
    private WindowManager.LayoutParams mWindowsParams;

    public static final String TAG = "OverLayService";

    private SensorManager sensorManager;
    private Sensor sensor;
    public static final int SENSOR_POLLING_RATE = 30000;
    private LineGraphSeries<DataPoint> mSeriesAccelX, mSeriesAccelY, mSeriesAccelZ;
    private double graphLastAccelXValue = 5d;
    private boolean placedLeft = false;
    private TextView graphText;
    double accelerometerThreshold = 0;
    int viewport;
    static double highestYViewable = 0;
    double xCorrector = 0, yCorrector = 0;
    float uncorrectedX = 0, uncorrectedY = 0;
    float finalX = 0, finalY = 0;
    static double lowestYViewable = 0;
    static double averageYViewable = 0;
    static double latestAccelValue;
    long lastSensorTimestamp = -1;

    boolean interfaceMovable = false;

    private LocationLogger locationLogger;

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

    float dataToAverage = 0;
    float diagonalAxisInput = 0;
    float ALPHA = 0.2f;

    //Gets average value of all the viewable graph points, this is to (help) determine whether the train is accelerating or decelerating
    //Also gets both the min and max values from those points, and stores them in a global value
    public static double calculateGraphAvg(Iterator<DataPoint> iterator) {
        double sum = 0;
        int count = 0;
        highestYViewable = latestAccelValue;
        lowestYViewable = latestAccelValue;
        while (iterator.hasNext()) {
            DataPoint point = iterator.next();
            if (point.getY() != 0) {
                sum += point.getY();
                count++;
                if (point.getY() < lowestYViewable)
                    lowestYViewable = point.getY();
                if (point.getY() > highestYViewable)
                    highestYViewable = point.getY();
            }
        }
        return sum / count;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);

        accelerometerThreshold = intent.getDoubleExtra("threshold", 0);
        viewport = intent.getIntExtra("viewport", 2);

        locationLogger = new LocationLogger(this);
        createNotification();
        drawLayout();
        setButtonActions();
        placeView();
        doLocationInfo();
        doGraphSetup();

        locationLogger.customMessage("Service Starting..");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (overlayView != null)
            mWindowManager.removeView(overlayView);
        locationLogger.customMessage("Service Stopping..");
        super.onDestroy();
    }

    /**
     * Necessary in order to make service operate in background; when mainactivity is closed
     */
    private void createNotification() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            pendingIntent = PendingIntent.getActivity(this, 251, intent, PendingIntent.FLAG_MUTABLE);

        String CHANNEL_ID = "Persistence";
        String CHANNEL_NAME = "Persistent notification";

        NotificationCompat.Builder builder;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notificationManager.createNotificationChannel(channel);
        builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID);
        builder.setChannelId(CHANNEL_ID);
        builder.setBadgeIconType(NotificationCompat.BADGE_ICON_NONE);

        builder.setContentTitle(getString(R.string.app_name));
        builder.setContentText(getString(R.string.active_in_background));
        Uri notificationSound = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION);
        builder.setSound(notificationSound);
        builder.setAutoCancel(false);
        builder.setSmallIcon(R.drawable.ic_launcher_foreground);
        builder.setContentIntent(pendingIntent);
        Notification notification = builder.build();
        startForeground(251, notification);
    }

    @SuppressLint("InflateParams")
    private void drawLayout() {
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        overlayView = layoutInflater.inflate(R.layout.overlay_window, null);//null because we have no root view
    }

    /**
     * Sets onClickListeners to pretty much all the buttons on the overlay.
     */
    private void setButtonActions() {
        TextView btnClose = overlayView.findViewById(R.id.btnClose);
        TextView btnCalibrate = overlayView.findViewById(R.id.calibrate);
        TextView btnResetCalibration = overlayView.findViewById(R.id.calibrateReset);
        graphText = overlayView.findViewById(R.id.graphText);

        btnClose.setOnClickListener(view -> {
            stopSelf();
            stopForeground(true);
            onDestroy();
        });

        //Tablet placement in cab, inverts accelerometer outputs
        TextView btnLeft = overlayView.findViewById(R.id.placedLeft);
        TextView btnRight = overlayView.findViewById(R.id.placedRight);

        btnLeft.setOnClickListener(view -> {
            btnLeft.setTextColor(getColor(R.color.transparent_white));
            btnRight.setTextColor(getColor(R.color.transparent_gray));
            placedLeft = true;
        });
        btnRight.setOnClickListener(view -> {
            btnLeft.setTextColor(getColor(R.color.transparent_gray));
            btnRight.setTextColor(getColor(R.color.transparent_white));
            placedLeft = false;
        });

        btnCalibrate.setOnClickListener(view -> {
            xCorrector = uncorrectedX;
            yCorrector = uncorrectedY;
            btnCalibrate.setTextColor(Color.parseColor("#888888"));
        });
        btnResetCalibration.setOnClickListener(view -> {
            xCorrector = 0;
            yCorrector = 0;
            btnCalibrate.setTextColor(getColor(R.color.transparent_white));
        });
    }

    /**
     * Does what it says on the tin; places the view.
     * Also sets window parameters, configures SOME buttons; the Move lock/unlock button as well as the Close button.
     */
    @SuppressLint("RtlHardcoded") //We're not working with right-to-left interfaces
    private void placeView() {
        int width = 300;
        int height = 700;

        mWindowsParams = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        mWindowsParams.gravity = Gravity.TOP | Gravity.LEFT;
        mWindowsParams.x = 595;
        mWindowsParams.y = 64;
        mWindowManager.addView(overlayView, mWindowsParams);

        TextView tvMove = overlayView.findViewById(R.id.btnMove);

        tvMove.setOnClickListener(view -> {
            if (interfaceMovable) {
                tvMove.setText(getString(R.string.unlock));
            } else {
                tvMove.setText(getString(R.string.lock));
            }
            interfaceMovable = !interfaceMovable;
        });
        tvMove.setOnLongClickListener(view -> {
            mWindowsParams.x = 595;
            mWindowsParams.y = 64;
            mWindowManager.updateViewLayout(overlayView, mWindowsParams);
            interfaceMovable = false;
            tvMove.setText(getString(R.string.unlock));
            Toast.makeText(context, R.string.toast_reset_placement, Toast.LENGTH_SHORT).show();
            return true;
        });

        //Makes view draggable
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @SuppressLint("ClickableViewAccessibility")
            //We're not clicking, we're initiating a window drag
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                //Touch in outside of view bounds, get rid of focus
                if(!isViewInBounds(v, (int) event.getRawX(), (int) event.getRawY())) {
                    mWindowsParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
                    mWindowManager.updateViewLayout(v, mWindowsParams);
                    return false;
                }

                mWindowsParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
                mWindowManager.updateViewLayout(v, mWindowsParams);

                //Ignore input
                if (!interfaceMovable) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mWindowsParams.x;
                        initialY = mWindowsParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        break;
                    case MotionEvent.ACTION_UP:
                        break;
                    case MotionEvent.ACTION_MOVE:
                        mWindowsParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        mWindowsParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        mWindowManager.updateViewLayout(overlayView, mWindowsParams);
                        break;
                }
                return false;
            }
        });
    }


    /**
     * Handles all the location data. Everything from displaying the GNSS data to getting and calculating the speed.
     */
    private void doLocationInfo() {
        ViewGroup graph = overlayView.findViewById(R.id.signalStrengthGraph);
        TextView tvFixType = overlayView.findViewById(R.id.tvFixType);
        TextView tvLatLong = overlayView.findViewById(R.id.tvLatLong);
        TextView tvElevation = overlayView.findViewById(R.id.tvElevation);
        TextView tvAccuracy = overlayView.findViewById(R.id.tvAccuracy);
        TextView tvSpeed = overlayView.findViewById(R.id.tvSpeed);

        GnssStatus.Callback status = new GnssStatus.Callback() {
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

            @SuppressLint("SetTextI18n")
            @Override
            public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                super.onSatelliteStatusChanged(status);

                //Satellite graph
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    if (graph.getChildAt(i) != null) {
                        if (status.getCn0DbHz(i) > 0) {

                            //coloring the bars dependant of signal strength
                            if (status.getCn0DbHz(i) >= 30)
                                graph.getChildAt(i).setBackgroundColor(getColor(R.color.transparent_green));
                            else if (status.getCn0DbHz(i) >= 15 && status.getCn0DbHz(i) < 30)
                                graph.getChildAt(i).setBackgroundColor(getColor(R.color.transparent_yellow));
                            else if (status.getCn0DbHz(i) < 15)
                                graph.getChildAt(i).setBackgroundColor(getColor(R.color.transparent_red));

                            //Setting layout height of individual bars when signal > 0;
                            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) graph.getChildAt(i).getLayoutParams();
                            lp.height = (int) (graph.getHeight() * (status.getCn0DbHz(i) / 45));

                            graph.getChildAt(i).setLayoutParams(lp);
                        } else {
                            //if signal 0, red graph bar & zero height
                            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) graph.getChildAt(i).getLayoutParams();
                            lp.height = 2;
                            graph.getChildAt(i).setLayoutParams(lp);
                            graph.getChildAt(i).setBackgroundColor(getColor(R.color.transparent_red));
                        }
                    }
                }

                //If we don't have location permissions, request them from the user
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, getString(R.string.permission_app_settings), Toast.LENGTH_SHORT).show();
                    return;
                }

                //After restarting device, lastknownlocation is pretty much always null.
                //Network provider is most likely to have lkl. Se we use that JUST FOR THE TIME BEING.
                //We assume a gps-provided location will be coming soon since we're requesting it & we have the permissions.
                Location location;
                if (locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                } else if (locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                } else {
                    location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }

                if (location != null) {
                    if (screenOn())
                        locationLogger.append(location, hasGPSFix);

                    hasGPSFix = true;
                    String fixTypeText = getString(R.string.gnss_fix);
                    if ((System.currentTimeMillis() - location.getTime()) > 2000) {
                        //The last known fix is too old, and therefor we conclude that we currently don't have one
                        tvFixType.setText(fixTypeText + getString(R.string.none));
                        tvFixType.setTextColor(getColor(R.color.transparent_red));
                        tvLatLong.setTextColor(getColor(R.color.transparent_gray));
                        tvElevation.setTextColor(getColor(R.color.transparent_gray));
                        tvAccuracy.setTextColor(getColor(R.color.transparent_gray));
                        tvSpeed.setTextColor(getColor(R.color.transparent_gray));
                        hasGPSFix = false;
                    } else if (!location.hasAltitude() || location.getAltitude() >= 200) {
                        //If we don't have altitude, or it's unreasonably high (let's say 200m for now, we consider this to be in error), fix is 2D.
                        tvFixType.setText(fixTypeText + "2D");
                        tvFixType.setTextColor(getColor(R.color.transparent_yellow));
                    } else if (location.getAltitude() < 200) {
                        //Best-case scenario. We have altitude, and we consider this to have a reasonable value.
                        tvFixType.setText(fixTypeText + "3D");
                        tvFixType.setTextColor(getColor(R.color.transparent_green));
                    } else {
                        //Don't think this scenario can exist, but let's be certain
                        tvFixType.setText(fixTypeText + getString(R.string.error));
                        tvFixType.setTextColor(getColor(R.color.transparent_red));
                    }

                    if (hasGPSFix) {
                        //We deliberately display the lat/long even when there is no current GNSS Fix available,
                        //so that we can see WHERE the signal was lost
                        tvLatLong.setTextColor(getColor(R.color.transparent_white));
                        tvLatLong.setText(getString(R.string.pos) +
                                location.getLatitude() + ", " + location.getLongitude());

                        //We assume that the location is inaccurate when the altitude is above 200m
                        //I mean this is the Netherlands after all
                        if (location.hasAltitude()) {
                            tvElevation.setText(getString(R.string.elevation) + (int) location.getAltitude() + "m");
                            if (location.getAltitude() >= 200)
                                tvElevation.setTextColor(getColor(R.color.transparent_red));
                            else
                                tvElevation.setTextColor(getColor(R.color.transparent_white));

                        }

                        //TimTim won't use the location when accuracy exceeds 50m, so we DO use it, but draw it in red
                        tvAccuracy.setText(getString(R.string.accuracy) + (int) location.getAccuracy() + "m");
                        if (location.getAccuracy() > 50)
                            tvAccuracy.setTextColor(getColor(R.color.transparent_red));
                        else
                            tvAccuracy.setTextColor(getColor(R.color.transparent_white));

                        //Pretty basic, displays the GNSS-provided speed
                        if (location.hasSpeed()) {
                            setSpeed(location.getSpeed());
                            tvSpeed.setText(getString(R.string.speed) + (int) speedKmh + getString(R.string.space_kmh));
                            tvSpeed.setTextColor(getColor(R.color.transparent_white));
                        } else {
                            setSpeed(-1);
                            tvSpeed.setText(getString(R.string.speed) + getString(R.string.nan));
                            tvSpeed.setTextColor(getColor(R.color.transparent_gray));
                        }
                    }
                }
            }
        };

        //Registers a GNSS callback listener (GnssStatus.Callback status) &
        //requests continuous location updates via GPS (assigned to an empty location change listener)
        locationManager.registerGnssStatusCallback(context.getMainExecutor(), status);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 1, context.getMainExecutor(), this);
    }

    /**
     * Handles accelerometer graph inputs
     *
     * @param event the sensor data
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

            //Weak attempt to kind of force a reasonable (but very much non-precise) polling-rate.
            if (lastSensorTimestamp != -1 && System.currentTimeMillis() - lastSensorTimestamp < (SENSOR_POLLING_RATE / 1000)) //converting micros to millis
                return;

            lastSensorTimestamp = System.currentTimeMillis();

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
                finalX = uncorrectedX;
                finalY = uncorrectedY;
            }

            //We want to get the diagonal acceleration force since the tablet is pretty much always angled at 45 degrees in the direction of travel.
            //By doing this we also negate side-to-side movement of the train in switches & other curves / the shitshow we call 'stable' ground.
            //TODO Use tangent to calculate force instead of doing this
            diagonalAxisInput = placedLeft ? ((finalY - (finalX * -1)) * -1) : ((finalY - finalX) * -1);

            //Applying low-pass-filter to data for smoothing.
            dataToAverage = dataToAverage + ALPHA * (diagonalAxisInput - dataToAverage);
            float average = dataToAverage;
            latestAccelValue = average;


            //Apply data to graph.
            graphLastAccelXValue += 0.15d;
            mSeriesAccelX.appendData(new DataPoint(graphLastAccelXValue, 0), true, 33);
            mSeriesAccelY.appendData(new DataPoint(graphLastAccelXValue, average), true, 33);
            mSeriesAccelZ.appendData(new DataPoint(graphLastAccelXValue, 0), true, 33);
        }

        //If acceleration is measured, display this to the user.
        //Ignores accelerometer when GPS-given speed exceeds 5m/s
        //Now making distinctions between accelerating, decelerating and just moving
        Iterator<DataPoint> viewableGraphPoints = mSeriesAccelY.getValues((mSeriesAccelY.getHighestValueX() - 4), mSeriesAccelY.getHighestValueX() - 1);
        averageYViewable = calculateGraphAvg(viewableGraphPoints);
        graphText.setText(getIsAcceleratingText(highestYViewable, lowestYViewable));
    }


    /**
     * Setup the graph style, and start listening for accelerometer value changes.
     * We have an on-board onchangelistener implemented for this reason.
     */
    private void doGraphSetup() {
        GraphView graphAccel = initGraph(R.id.graphAccel);
        graphAccel.getLegendRenderer().setVisible(false);
        graphAccel.setTitleColor(getColor(R.color.transparent_white));
        graphAccel.setTitleTextSize(8);
        graphAccel.getGridLabelRenderer().setTextSize(12);
        graphAccel.getGridLabelRenderer().setHorizontalAxisTitleColor(getColor(R.color.transparent_white));
        graphAccel.getGridLabelRenderer().setVerticalAxisTitleColor(getColor(R.color.transparent_white));
        graphAccel.getGridLabelRenderer().setVerticalLabelsColor(getColor(R.color.transparent_white));
        graphAccel.getGridLabelRenderer().setHorizontalLabelsColor(getColor(R.color.transparent_white));
        graphAccel.getGridLabelRenderer().setVerticalLabelsColor(getColor(R.color.transparent_white));
        graphAccel.getGridLabelRenderer().setHorizontalLabelsColor(getColor(R.color.transparent_white));
        graphAccel.getGridLabelRenderer().reloadStyles();

        mSeriesAccelX = initSeries(Color.BLACK, "X"); //back and forth
        mSeriesAccelY = initSeries(getColor(R.color.transparent_red), "Y");//side to side
        mSeriesAccelZ = initSeries(Color.BLACK, "Z"); //up and down

        graphAccel.addSeries(mSeriesAccelX);
        graphAccel.addSeries(mSeriesAccelY);
        graphAccel.addSeries(mSeriesAccelZ);

        graphAccel.getViewport().setMinY((double) viewport * -1);
        graphAccel.getViewport().setMaxY(viewport);
        graphAccel.getViewport().setYAxisBoundsManual(true);

        /*
                  Okay, so the android's sensor manager is a royal pain in the back-side.
                  Let me explain. There is a delay amount that determines the polling rate of the movement sensors,
                  which YOU, as the dev, can define.

                  However, android rather sees this as a 'suggestion', and often likes to ignore your provided rate.
                  In order to combat this, one COULD use NDK (c++ for android, basically) to override this native behaviour
                  For now, I'll just remove the train is moving & train is stationary texts below the accelerometer graph,
                  since those were flashing about quite a bit when the polling rate went berserk.

                  When the polling rate is respected, 200000 is a nice number of microseconds to wait.
         */
        sensorManager.registerListener(this, sensor, SENSOR_POLLING_RATE); //200000 being the polling delay in microseconds.

    }

    //Sets X and Y accelerometer values so that they can be used globally
    private void setXY(float x, float y) {
        uncorrectedX = x;
        uncorrectedY = y;
    }

    //Required override method, ignore.
    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        //Might be a duplicate call, but probably still necessary
    }

    /**
     * Initiate the series (our line, basically)
     * @param color color of the series in an integer format
     * @param title the title of the series, we do nothing with this
     * @return the series to return to the graph
     */
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

    /**
     * We like to use global variables to access the speed
     * @param speed the input speed in m/s
     */
    public void setSpeed(float speed) {
        this.speed = speed;
        this.speedKmh = (float) (speed * 3.6);
    }

    /**
     * Initiating the graph
     * @param id
     * @return
     */
    public GraphView initGraph(int id) {
        GraphView graph = overlayView.findViewById(id);
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(5);
        graph.getGridLabelRenderer().setLabelVerticalWidth(100);
        graph.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        graph.getLegendRenderer().setVisible(true);
        return graph;
    }

    /**
     * Gets the  text to display on the graph based on whether we have GPS (and therefor a known speed), or the accelerometer
     * that the train is generally moving, or specifically accelerating, decelerating or stationary.
     * @param highest highest speed recorded
     * @param lowest lowest speed recorded
     * @return whether the train is moving or stationary
     */
    private String getIsAcceleratingText(double highest, double lowest) {
        doHideGraph();
        if (hasGPSFix && speed > 5)
            return getString(R.string.at_speed);
        else if (lowest > 0.3 && highest > 0.3)
            return getString(R.string.train_accel);
        else if (highest < -0.3 && lowest < -0.3)
            return getString(R.string.train_decel);
        else if (highest - lowest > accelerometerThreshold)
            return /*getString(R.string.train_moving);*/ "";
        else
            return /*getString(R.string.train_stationary);*/ "";
    }

    /**
     * We don't want to keep logging locations when the screen is off.
     */
    private boolean screenOn() {
        for (Display display : displayManager.getDisplays()) {
            if (display.getState() != Display.STATE_OFF && display.getState() != Display.STATE_DOZE && display.getState() != Display.STATE_DOZE_SUSPEND)
                return true;
        }
        return false;
    }

    /**
     * When gps signal is available & reliable, the graph isn't really all that necessary.
     * So in order to take away any possible distraction, we 'hide' the graph with a semi-transparent black overlay.
     */
    private void doHideGraph() {
        View graphHider = overlayView.findViewById(R.id.graphHider);
        if (hasGPSFix && speed > 5)
            graphHider.setVisibility(View.VISIBLE);
        else
            graphHider.setVisibility(View.INVISIBLE);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        locationLogger.customMessage("Service Stopping..");
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        locationLogger.customMessage("Service rebound..");
        super.onRebind(intent);
    }

    /**
     * We want to know whether a certain coordinate-set is in bounds of our pop-up window. We have to know this
     * to properly catch and release the window focus.
     * @param view our window
     * @param x x-coordinate
     * @param y y-coordinate
     * @return whether a certain coordinate-set is in bounds of our pop-up window or not.
     */
    private boolean isViewInBounds(View view, int x, int y) {
        Rect outRect  = new Rect();
        int[] location = new int[2];
        view.getDrawingRect(outRect);
        view.getLocationOnScreen(location);
        outRect.offset(location[0], location[1]);
        return outRect.contains(x, y);
    }
}
