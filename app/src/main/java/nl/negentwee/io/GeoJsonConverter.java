package nl.negentwee.io;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class GeoJsonConverter {

    public static final String TAG = "JsonConverter";
    static ArrayList<Spot> spots = new ArrayList<>();

    public static void readFile(Uri uri, Context c) {
        spots = new ArrayList<>();
        try {
            InputStream inputStream = c.getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.split(" ")[2].startsWith("<!--"))
                    continue;
                if (line.split(" ").length != 7)
                    continue;

                double lat, lng;
                int accuracy, speed;
                boolean gpsFix;
                String timeDate;

                timeDate = line.split(" ")[0] + " " + line.split(" ")[1];
                lat = Double.parseDouble(line.split(" ")[2]);
                lng = Double.parseDouble(line.split(" ")[3]);
                accuracy = Integer.parseInt(line.split(" ")[4].split("-")[1]); //get rid of +- symbols
                speed = Integer.parseInt(line.split(" ")[5]);
                gpsFix = line.split(" ")[6].equalsIgnoreCase("GNSS-fix");

                Spot s = new Spot(lat, lng, accuracy, speed, gpsFix, timeDate);
                spots.add(s);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String convert() {
        try {
            String jsonExport;
            if (spots.size() == 0) {
                Log.d(TAG, "ERROR, array size is 0");
                return "";
            }
            JSONObject geoJSON = new JSONObject();
            geoJSON.put("type", "FeatureCollection");
            JSONArray convertedArray = new JSONArray();
            for (Spot s : spots) {
                JSONObject geoObj = new JSONObject();
                JSONObject geometryObj = new JSONObject();
                JSONArray latlong = new JSONArray();
                latlong.put(s.lng);
                latlong.put(s.lat);
                geometryObj.put("coordinates", latlong);
                geometryObj.put("type", "Point");

                JSONObject propsObj = new JSONObject();
                propsObj.put("Time Date", s.timeDate);
                propsObj.put("Accuracy", s.accuracy);
                propsObj.put("Speed", s.speed);
                propsObj.put("GNSS-fix", s.gpsFix);

                geoObj.put("geometry", geometryObj);
                geoObj.put("type", "Feature");
                geoObj.put("properties", propsObj);

                convertedArray.put(geoObj);
                geoJSON.put("features", convertedArray);
            }

            jsonExport = geoJSON.toString();
            return jsonExport;
        } catch (JSONException e) {
            e.printStackTrace();
            return "";
        }
    }

    private static class Spot {
        public double lat, lng;
        public int accuracy, speed;
        boolean gpsFix;
        String timeDate;

        public Spot(double lat, double lng, int accuracy, int speed, boolean gpsFix, String timeDate) {
            this.lat = lat;
            this.lng = lng;
            this.accuracy = accuracy;
            this.speed = speed;
            this.gpsFix = gpsFix;
            this.timeDate = timeDate;
        }
    }
}
