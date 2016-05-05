package com.ant.sunshine.app.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.text.format.Time;
import android.util.Log;

import com.ant.core.Utility;
import com.ant.core.WatchConfigurationPreferences;
import com.ant.core.WatchfaceSyncCommons;
import com.bumptech.glide.Glide;
import com.ant.sunshine.app.BuildConfig;
import com.ant.sunshine.app.activities.MainActivity;
import com.ant.sunshine.app.R;
import com.ant.sunshine.app.test.WeatherContract;
import com.ant.sunshine.app.muzei.WeatherMuzeiSource;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallbacks;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

public class SunshineSyncAdapter extends AbstractThreadedSyncAdapter implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String HTTP_API_OPENWEATHERMAP_ORG_DATA_2_5_FORECAST_DAILY = "http://api.openweathermap.org/data/2.5/forecast/daily?";
    public static final String QUERY_PARAM_DEFAULT = "q";
    public static final String MODE = "mode";
    public static final String UNITS = "units";
    public static final String CNT = "cnt";
    public static final String APPID = "APPID";
    public static final String JSON_QUERY_CITY = "city";
    public static final String JSON_KEY_NAME = "name";
    public static final String JSON_KEY_COORD = "coord";
    public static final String JSON_KEY_LAT = "lat";
    public static final String JSON_KEY_LON = "lon";
    public static final String JSON_KEY_LIST = "list";
    public static final String PRESSURE = "pressure";
    public static final String JSON_KEY_PRESSURE1 = PRESSURE;
    public static final String JSON_KEY_PRESSURE = JSON_KEY_PRESSURE1;
    public static final String JSON_KEY_HUMIDITY = "humidity";
    public static final String JSON_KEY_SPEED = "speed";
    public static final String JSON_KEY_DEG = "deg";
    public static final String JSON_KEY_TEMP = "temp";
    public static final String JSON_KEY_MAX = "max";
    public static final String JSON_KEY_MIN = "min";
    public static final String JSON_KEY_WEATHER = "weather";
    public static final String JSON_KEY_MAIN = "main";
    public static final String JSON_KEY_ID = "id";
    public static final String JSON_KEY_COD = "cod";
    public static final String GET = "GET";
    private static GoogleApiClient googleApiClient;


    // Now we have a String representing the complete forecast in JSON Format.
    // Fortunately parsing is easy:  constructor takes the JSON string and converts it
    // into an Object hierarchy for us.

    // These are the names of the JSON objects that need to be extracted.

    // Location information
    private final String OWM_CITY = JSON_QUERY_CITY;
    private final String OWM_CITY_NAME = JSON_KEY_NAME;
    final String OWM_COORD = JSON_KEY_COORD;

    // Location coordinate
    private final String OWM_LATITUDE = JSON_KEY_LAT;
    private final String OWM_LONGITUDE = JSON_KEY_LON;

    // Weather information.  Each day's forecast info is an element of the "list" array.
    private final String OWM_LIST = JSON_KEY_LIST;

    private final String OWM_PRESSURE = JSON_KEY_PRESSURE;
    private final String OWM_HUMIDITY = JSON_KEY_HUMIDITY;
    private final String OWM_WINDSPEED = JSON_KEY_SPEED;
    private final String OWM_WIND_DIRECTION = JSON_KEY_DEG;

    // All temperatures are children of the "temp" object.
    private final String OWM_TEMPERATURE = JSON_KEY_TEMP;
    private final String OWM_MAX = JSON_KEY_MAX;
    private final String OWM_MIN = JSON_KEY_MIN;

    private final String OWM_WEATHER = JSON_KEY_WEATHER;
    private final String OWM_DESCRIPTION = JSON_KEY_MAIN;
    private final String OWM_WEATHER_ID = JSON_KEY_ID;

    private final String OWM_MESSAGE_CODE = JSON_KEY_COD;

    public final String LOG_TAG = SunshineSyncAdapter.class.getSimpleName();
    public static final String ACTION_DATA_UPDATED =
            "com.example.android.sunshine.app.ACTION_DATA_UPDATED";
    // Interval at which to sync with the weather, in seconds.
    // 60 seconds (1 minute) * 180 = 3 hours
    public static final int SYNC_INTERVAL = 60 * 180;
    public static final int SYNC_FLEXTIME = SYNC_INTERVAL / 3;
    private static final long FOUR_HOURS_IN_MILLIS = 1000 * 60 * 60 * 4;

    private static final long DAY_IN_MILLIS = 1000 * 60 * 60 * 24;
    private static final int WEATHER_NOTIFICATION_ID = 3004;
    private static final String TAG = SunshineSyncAdapter.class.getCanonicalName();

    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[]{
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    public SunshineSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        initGoogleApiClient(context);
    }

    private void initGoogleApiClient(Context context) {
        Log.d(TAG, "initializing google client!");
        googleApiClient = new GoogleApiClient.Builder(context)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        startGoogleApiClient(context);
    }

    private PutDataMapRequest updateDataMapRequest(double high, double low, int weatherId) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WatchConfigurationPreferences.PATH);
        WatchConfigurationPreferences configurationPreferences = new WatchConfigurationPreferences.Builder()
                .addLowTemperature(low)
                .addHighTemperature(high)
                .addWeatherInfoId(weatherId)
                .build();

        if (configurationPreferences != null) {
            putDataMapReq.getDataMap().putDouble(WatchfaceSyncCommons.KEY_LOW_TEMP, configurationPreferences.getLowTemp());
            putDataMapReq.getDataMap().putDouble(WatchfaceSyncCommons.KEY_HIGH_TEMP, configurationPreferences.getHighTemp());
            putDataMapReq.getDataMap().putInt(WatchfaceSyncCommons.KEY_WEATHER_INFO, configurationPreferences.getWeatherId());
        }
        return putDataMapReq;
    }


    private void processWeatherData(double high, double low, int weatherId) {
        PutDataMapRequest putDataMapReq = updateDataMapRequest(high, low, weatherId);
        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        if (googleApiClient == null) {
            initGoogleApiClient(getContext());
        }
        Wearable.DataApi.putDataItem(googleApiClient, putDataReq).setResultCallback(new ResultCallbacks<DataApi.DataItemResult>() {
            @Override
            public void onSuccess(DataApi.DataItemResult dataItemResult) {
                Log.d(TAG, "data sent: Success!");
            }

            @Override
            public void onFailure(Status status) {
                Log.d(TAG, "date sent: Failure!");
            }
        });
    }


    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.d(LOG_TAG, "Starting sync");
        //String locationQuery = Utility.getPreferredLocation(getContext());
        //LatLng latLng = Utility.getLocation();
        String locQuery = Utility.getPreferredLocation(getContext());
        // These two need to be declared outside the try/catch
        // so that they can be closed in the finally block.
        BufferedReader reader = null;

        // Will contain the raw JSON response as a string.
        String forecastJsonStr = null;

        String format = "json";
        String units = "metric";
        int numDays = 14;

        try {
            //String locationQuery = getLocationQuery(latLng);
            Uri builtUri = getUriFromParams(locQuery, format, units, numDays);
            // Read the input stream into a String
            InputStream inputStream = getInputStream(builtUri, GET);
            reader = processInputStream(locQuery, inputStream);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error ", e);
            // If the code didn't successfully get the weather data, there's no point in attempting
            // to parse it.
            setLocationStatus(getContext(), Utility.LOCATION_STATUS_SERVER_DOWN);
        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
            setLocationStatus(getContext(), Utility.LOCATION_STATUS_SERVER_INVALID);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException e) {
                    Log.e(LOG_TAG, "Error closing stream", e);
                }
            }
        }
    }

    private Uri getUriFromParams(String locationQuery, String format, String units, int numDays) {
        // Construct the URL for the OpenWeatherMap query
        // Possible parameters are avaiable at OWM's forecast API page, at
        // http://openweathermap.org/API#forecast
        final String FORECAST_BASE_URL = HTTP_API_OPENWEATHERMAP_ORG_DATA_2_5_FORECAST_DAILY;
        final String QUERY_PARAM = QUERY_PARAM_DEFAULT;
        final String FORMAT_PARAM = MODE;
        final String UNITS_PARAM = UNITS;
        final String DAYS_PARAM = CNT;
        final String APPID_PARAM = APPID;

        return Uri.parse(FORECAST_BASE_URL).buildUpon()
                .appendQueryParameter(QUERY_PARAM, locationQuery)
                .appendQueryParameter(FORMAT_PARAM, format)
                .appendQueryParameter(UNITS_PARAM, units)
                .appendQueryParameter(DAYS_PARAM, Integer.toString(numDays))
                .appendQueryParameter(APPID_PARAM, BuildConfig.OPEN_WEATHER_MAP_API_KEY)
                .build();
    }

    @Nullable
    private BufferedReader processInputStream(String locationQuery, InputStream inputStream) throws IOException, JSONException {
        String forecastJsonStr;
        StringBuilder buffer = new StringBuilder();
        if (inputStream == null) {
            // Nothing to do.
            return null;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

        String line;
        while ((line = reader.readLine()) != null) {
            // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
            // But it does make debugging a *lot* easier if you print out the completed
            // buffer for debugging.
            buffer.append(line + "\n");
        }

        if (buffer.length() == 0) {
            // Stream was empty.  No point in parsing.
            setLocationStatus(getContext(), Utility.LOCATION_STATUS_SERVER_DOWN);
            return null;
        }
        forecastJsonStr = buffer.toString();
        getWeatherDataFromJson(forecastJsonStr, locationQuery);
        return reader;
    }

    @NonNull
    private InputStream getInputStream(Uri builtUri, String requestMethod) throws IOException {
        URL url = new URL(builtUri.toString());

        // Create the request to OpenWeatherMap, and open the connection
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestMethod(requestMethod);
        urlConnection.connect();
        return urlConnection.getInputStream();
    }

    /**
     * Take the String representing the complete forecast in JSON Format and
     * pull out the data we need to construct the Strings needed for the wireframes.
     * <p/>
     * Fortunately parsing is easy:  constructor takes the JSON string and converts it
     * into an Object hierarchy for us.
     */
    private void getWeatherDataFromJson(String forecastJsonStr,
                                        String locationSetting)
            throws JSONException {


        try {
            JSONObject forecastJson = new JSONObject(forecastJsonStr);

            // do we have an error?
            if (forecastJson.has(OWM_MESSAGE_CODE)) {
                int errorCode = forecastJson.getInt(OWM_MESSAGE_CODE);

                switch (errorCode) {
                    case HttpURLConnection.HTTP_OK:
                        break;
                    case HttpURLConnection.HTTP_NOT_FOUND:
                        setLocationStatus(getContext(), Utility.LOCATION_STATUS_INVALID);
                        return;
                    default:
                        setLocationStatus(getContext(), Utility.LOCATION_STATUS_SERVER_DOWN);
                        return;
                }
            }

            JSONArray weatherArray = forecastJson.getJSONArray(OWM_LIST);

            JSONObject cityJson = forecastJson.getJSONObject(OWM_CITY);
            String cityName = cityJson.getString(OWM_CITY_NAME);

            JSONObject cityCoord = cityJson.getJSONObject(OWM_COORD);
            double cityLatitude = cityCoord.getDouble(OWM_LATITUDE);
            double cityLongitude = cityCoord.getDouble(OWM_LONGITUDE);

            long locationId = addLocation(locationSetting, cityName, cityLatitude, cityLongitude);

            // Insert the new weather information into the database
            Vector<ContentValues> cVVector = new Vector<ContentValues>(weatherArray.length());

            // OWM returns daily forecasts based upon the local time of the city that is being
            // asked for, which means that we need to know the GMT offset to translate this data
            // properly.

            // Since this data is also sent in-order and the first day is always the
            // current day, we're going to take advantage of that to get a nice
            // normalized UTC date for all of our weather.

            Time dayTime = new Time();
            dayTime.setToNow();

            // we start at the day returned by local time. Otherwise this is a mess.
            int julianStartDay = Time.getJulianDay(System.currentTimeMillis(), dayTime.gmtoff);

            // now we work exclusively in UTC
            dayTime = new Time();

            cVVector = initAndGetWeatherContentValues(weatherArray, locationId, dayTime, julianStartDay);

            int inserted = 0;
            // add to database
            if (cVVector.size() > 0) {
                ContentValues[] cvArray = new ContentValues[cVVector.size()];
                cVVector.toArray(cvArray);
                getContext().getContentResolver().bulkInsert(WeatherContract.WeatherEntry.CONTENT_URI, cvArray);

                // delete old data so we don't build up an endless history
                getContext().getContentResolver().delete(WeatherContract.WeatherEntry.CONTENT_URI,
                        WeatherContract.WeatherEntry.COLUMN_DATE + " <= ?",
                        new String[]{Long.toString(dayTime.setJulianDay(julianStartDay - 1))});

                updateWidgets();
                updateMuzei();
                notifyWeather();
            }
            Log.d(LOG_TAG, "Sync Complete. " + cVVector.size() + " Inserted");
            setLocationStatus(getContext(), Utility.LOCATION_STATUS_OK);

        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
            setLocationStatus(getContext(), Utility.LOCATION_STATUS_SERVER_INVALID);
        }
    }

    private Vector<ContentValues> initAndGetWeatherContentValues(JSONArray weatherArray, long locationId, Time dayTime, int julianStartDay) throws JSONException {

        Vector<ContentValues> cVVector = new Vector<>();
        for (int i = 0; i < weatherArray.length(); i++) {
            // These are the values that will be collected.
            long dateTime;
            double pressure;
            int humidity;
            double windSpeed;
            double windDirection;

            double high;
            double low;

            String description;
            int weatherId;

            // Get the JSON object representing the day
            JSONObject dayForecast = weatherArray.getJSONObject(i);

            // Cheating to convert this to UTC time, which is what we want anyhow
            dateTime = dayTime.setJulianDay(julianStartDay + i);

            pressure = dayForecast.getDouble(OWM_PRESSURE);
            humidity = dayForecast.getInt(OWM_HUMIDITY);
            windSpeed = dayForecast.getDouble(OWM_WINDSPEED);
            windDirection = dayForecast.getDouble(OWM_WIND_DIRECTION);

            // Description is in a child array called "weather", which is 1 element long.
            // That element also contains a weather code.
            JSONObject weatherObject =
                    dayForecast.getJSONArray(OWM_WEATHER).getJSONObject(0);
            description = weatherObject.getString(OWM_DESCRIPTION);
            weatherId = weatherObject.getInt(OWM_WEATHER_ID);

            // Temperatures are in a child object called "temp".  Try not to name variables
            // "temp" when working with temperature.  It confuses everybody.
            JSONObject temperatureObject = dayForecast.getJSONObject(OWM_TEMPERATURE);
            high = temperatureObject.getDouble(OWM_MAX);
            low = temperatureObject.getDouble(OWM_MIN);

            ContentValues weatherValues = new ContentValues();

            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_LOC_KEY, locationId);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_DATE, dateTime);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_HUMIDITY, humidity);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_PRESSURE, pressure);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_WIND_SPEED, windSpeed);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_DEGREES, windDirection);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP, high);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP, low);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_SHORT_DESC, description);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID, weatherId);

            cVVector.add(weatherValues);
        }
        return cVVector;
    }

    private void updateWidgets() {
        Context context = getContext();
        // Setting the package ensures that only components in our app will receive the broadcast
        Intent dataUpdatedIntent = new Intent(ACTION_DATA_UPDATED)
                .setPackage(context.getPackageName());
        context.sendBroadcast(dataUpdatedIntent);
    }

    private void updateMuzei() {
        // Muzei is only compatible with Jelly Bean MR1+ devices, so there's no need to update the
        // Muzei background on lower API level devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Context context = getContext();
            context.startService(new Intent(ACTION_DATA_UPDATED)
                    .setClass(context, WeatherMuzeiSource.class));
        }
    }

    private void notifyWeather() {
        Context context = getContext();
        //checking the last update and notify if it' the first of the day
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String displayNotificationsKey = context.getString(R.string.pref_enable_notifications_key);
        boolean displayNotifications = prefs.getBoolean(displayNotificationsKey,
                Boolean.parseBoolean(context.getString(R.string.pref_enable_notifications_default)));

        if (displayNotifications) {

            String lastNotificationKey = context.getString(R.string.pref_last_notification);
            long lastSync = prefs.getLong(lastNotificationKey, 0);

            if (hasFourHoursPassed(lastSync)) {
                // Last sync was more than 1 day ago, let's send a notification with the weather.
                String locationQuery = Utility.getPreferredLocation(context);

                Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

                // we'll query our contentProvider, as always
                Cursor cursor = context.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

                if (cursor != null && cursor.moveToFirst()) {
                    int weatherId = cursor.getInt(Utility.INDEX_WEATHER_ID);
                    double high = cursor.getDouble(Utility.INDEX_MAX_TEMP);
                    double low = cursor.getDouble(Utility.INDEX_MIN_TEMP);
                    String desc = cursor.getString(Utility.INDEX_SHORT_DESC);

                    int iconId = Utility.getIconResourceForWeatherCondition(weatherId);
                    Resources resources = context.getResources();
                    int artResourceId = Utility.getArtResourceForWeatherCondition(weatherId);
                    String artUrl = Utility.getArtUrlForWeatherCondition(context, weatherId);

                    // On Honeycomb and higher devices, we can retrieve the size of the large icon
                    // Prior to that, we use a fixed size
                    @SuppressLint("InlinedApi")
                    int largeIconWidth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                            ? resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
                            : resources.getDimensionPixelSize(R.dimen.notification_large_icon_default);
                    @SuppressLint("InlinedApi")
                    int largeIconHeight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                            ? resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
                            : resources.getDimensionPixelSize(R.dimen.notification_large_icon_default);

                    // Retrieve the large icon
                    Bitmap largeIcon;
                    try {
                        largeIcon = Glide.with(context)
                                .load(artUrl)
                                .asBitmap()
                                .error(artResourceId)
                                .fitCenter()
                                .into(largeIconWidth, largeIconHeight).get();
                    } catch (InterruptedException | ExecutionException e) {
                        Log.e(LOG_TAG, "Error retrieving large icon from " + artUrl, e);
                        largeIcon = BitmapFactory.decodeResource(resources, artResourceId);
                    }
                    String title = context.getString(R.string.app_name);

                    // Define the text of the forecast.
                    String contentText = String.format(context.getString(R.string.format_notification),
                            desc,
                            Utility.formatTemperature(context, high),
                            Utility.formatTemperature(context, low));

                    // NotificationCompatBuilder is a very convenient way to build backward-compatible
                    // notifications.  Just throw in some data.
                    NotificationCompat.Builder mBuilder =
                            new NotificationCompat.Builder(getContext())
                                    .setColor(resources.getColor(R.color.primary_light))
                                    .setSmallIcon(iconId)
                                    .setLargeIcon(largeIcon)
                                    .setContentTitle(title)
                                    .setContentText(contentText);

                    // Make something interesting happen when the user clicks on the notification.
                    // In this case, opening the app is sufficient.
                    Intent resultIntent = new Intent(context, MainActivity.class);

                    // The stack builder object will contain an artificial back stack for the
                    // started Activity.
                    // This ensures that navigating backward from the Activity leads out of
                    // your application to the Home screen.
                    TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
                    stackBuilder.addNextIntent(resultIntent);
                    PendingIntent resultPendingIntent =
                            stackBuilder.getPendingIntent(
                                    0,
                                    PendingIntent.FLAG_UPDATE_CURRENT
                            );
                    mBuilder.setContentIntent(resultPendingIntent);

                    NotificationManager mNotificationManager =
                            (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
                    // WEATHER_NOTIFICATION_ID allows you to update the notification later on.
                    mNotificationManager.notify(WEATHER_NOTIFICATION_ID, mBuilder.build());

                    //refreshing last sync
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putLong(lastNotificationKey, System.currentTimeMillis());
                    editor.commit();
                    processWeatherData(high, low, weatherId);
                }
                cursor.close();
            }
        }
    }

    private boolean hasADayPassed(long lastSync) {
        return System.currentTimeMillis() - lastSync >= DAY_IN_MILLIS;
    }

    private boolean hasFourHoursPassed(long lastSync) {
        return System.currentTimeMillis() - lastSync >= FOUR_HOURS_IN_MILLIS;
    }

    /**
     * Helper method to handle insertion of a new location in the weather database.
     *
     * @param locationSetting The location string used to request updates from the server.
     * @param cityName        A human-readable city name, e.g "Mountain View"
     * @param lat             the latitude of the city
     * @param lon             the longitude of the city
     * @return the row ID of the added location.
     */
    long addLocation(String locationSetting, String cityName, double lat, double lon) {
        long locationId;

        // First, check if the location with this city name exists in the db
        Cursor locationCursor = getContext().getContentResolver().query(
                WeatherContract.LocationEntry.CONTENT_URI,
                new String[]{WeatherContract.LocationEntry._ID},
                WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING + " = ?",
                new String[]{locationSetting},
                null);

        if (locationCursor.moveToFirst()) {
            int locationIdIndex = locationCursor.getColumnIndex(WeatherContract.LocationEntry._ID);
            locationId = locationCursor.getLong(locationIdIndex);
        } else {
            // Now that the content provider is set up, inserting rows of data is pretty simple.
            // First create a ContentValues object to hold the data you want to insert.
            ContentValues locationValues = new ContentValues();

            // Then add the data, along with the corresponding name of the data type,
            // so the content provider knows what kind of value is being inserted.
            locationValues.put(WeatherContract.LocationEntry.COLUMN_CITY_NAME, cityName);
            locationValues.put(WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING, locationSetting);
            locationValues.put(WeatherContract.LocationEntry.COLUMN_COORD_LAT, lat);
            locationValues.put(WeatherContract.LocationEntry.COLUMN_COORD_LONG, lon);

            // Finally, insert location data into the database.
            Uri insertedUri = getContext().getContentResolver().insert(
                    WeatherContract.LocationEntry.CONTENT_URI,
                    locationValues
            );

            // The resulting URI contains the ID for the row.  Extract the locationId from the Uri.
            locationId = ContentUris.parseId(insertedUri);
        }

        locationCursor.close();
        // Wait, that worked?  Yes! - u are stupid, yes!
        return locationId;
    }

    /**
     * Helper method to schedule the sync adapter periodic execution
     */
    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        Account account = getSyncAccount(context);
        String authority = context.getString(R.string.content_authority);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // we can enable inexact timers in our periodic sync
            SyncRequest request = new SyncRequest.Builder().
                    syncPeriodic(syncInterval, flexTime).
                    setSyncAdapter(account, authority).
                    setExtras(new Bundle()).build();

            ContentResolver.requestSync(request);
        } else {
            ContentResolver.addPeriodicSync(account,
                    authority, new Bundle(), syncInterval);
        }
    }

    /**
     * Helper method to have the sync adapter sync immediately
     *
     * @param context The context used to access the account service
     */
    public static void syncImmediately(Context context) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(getSyncAccount(context),
                context.getString(R.string.content_authority), bundle);
    }

    /**
     * Helper method to get the fake account to be used with SyncAdapter, or make a new one
     * if the fake account doesn't exist yet.  If we make a new account, we call the
     * onAccountCreated method so we can initialize things.
     *
     * @param context The context used to access the account service
     * @return a fake account.
     */
    public static Account getSyncAccount(Context context) {
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        // Create the account type and default account
        Account newAccount = new Account(
                context.getString(R.string.app_name), context.getString(R.string.sync_account_type));

        // If the password doesn't exist, the account doesn't exist
        if (null == accountManager.getPassword(newAccount)) {

        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
            if (!accountManager.addAccountExplicitly(newAccount, "", null)) {
                return null;
            }
            /*
             * If you don't set android:syncable="true" in
             * in your <provider> element in the manifest,
             * then call ContentResolver.setIsSyncable(account, AUTHORITY, 1)
             * here.
             */

            onAccountCreated(newAccount, context);
        }
        return newAccount;
    }

    private static void onAccountCreated(Account newAccount, Context context) {
        /*
         * Since we've created an account
         */
        SunshineSyncAdapter.configurePeriodicSync(context, SYNC_INTERVAL, SYNC_FLEXTIME);

        /*
         * Without calling setSyncAutomatically, our periodic sync will not be enabled.
         */
        ContentResolver.setSyncAutomatically(newAccount, context.getString(R.string.content_authority), true);

        /*
         * Finally, let's do a sync to get things started
         */
        syncImmediately(context);
    }

    public static void initializeSyncAdapter(Context context) {
        getSyncAccount(context);
    }

    /**
     * Sets the location status into shared preference.  This function should not be called from
     * the UI thread because it uses commit to write to the shared preferences.
     *
     * @param c              Context to get the PreferenceManager from.
     * @param locationStatus The IntDef value to set
     */
    static private void setLocationStatus(Context c, @Utility.LocationStatus int locationStatus) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(c);
        SharedPreferences.Editor spe = sp.edit();
        spe.putInt(c.getString(R.string.pref_location_status_key), locationStatus);
        spe.commit();
    }

    public void startGoogleApiClient(Context context) {
        if (googleApiClient == null) {
            initGoogleApiClient(context);
        }
        googleApiClient.connect();
    }


    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "onConnected");
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.e(TAG, "onConnectionSuspended");
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(TAG, "onConnectionFailed");
    }

    public static void stopSyncing() {
        if (googleApiClient != null && googleApiClient.isConnected()) {
            googleApiClient.disconnect();
        }
    }
}