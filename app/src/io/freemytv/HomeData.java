package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HomeData {
    static final String[] COINS = {"F1", "ALONSO", "TFC"};
    static final String[] COIN_NAMES = {"Formule 1", "Fernando Alonso", "Toulouse FC"};

    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }

    private static final long SPORTS_EVERY = 15 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    interface Listener { void onData(); }

    static final class Sports {
        String f1Next = "";
        String alonsoRank = "";
        String tfcNext = "";
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    static int coinCount() {
        return 3;
    }

    static int[] active() {
        return new int[] {0, 1, 2};
    }

    static String coinName(int i) {
        return i >= 0 && i < COIN_NAMES.length ? COIN_NAMES[i] : "";
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};

    volatile long sportsAt, weatherAt, pricesAt;
    volatile Weather weather = new Weather();
    volatile Sports sports = new Sports();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingSports, fetchingWeather;
    private volatile long sportsFailedAt, weatherFailedAt;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            sports.f1Next = cache.getString("sports_f1", "");
            sports.alonsoRank = cache.getString("sports_alonso", "");
            sports.tfcNext = cache.getString("sports_tfc", "");
            sportsAt = cache.getLong("sportsAt", 0);

            String w = cache.getString("weather", null);
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weatherAt = cache.getLong("weatherAt", 0);
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    void settingsChanged() {
        sportsAt = 0;
        weatherAt = 0;
        sportsFailedAt = 0;
        weatherFailedAt = 0;
    }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (!fetchingSports && (now - sportsAt > SPORTS_EVERY || now < sportsAt || instance == null || instance.sports.f1Next.isEmpty()) && !failedRecently(sportsFailedAt, now)) {
            fetchingSports = true;
            io.execute(() -> {
                try {
                    fetchSportsData();
                    sportsAt = System.currentTimeMillis();
                    cache.edit()
                            .putString("sports_f1", sports.f1Next)
                            .putString("sports_alonso", sports.alonsoRank)
                            .putString("sports_tfc", sports.tfcNext)
                            .putLong("sportsAt", sportsAt)
                            .apply();
                    notifyListeners();
                } catch (Exception e) {
                    sportsFailedAt = System.currentTimeMillis();
                    AppLog.w("sports: " + e.getMessage());
                } finally {
                    fetchingSports = false;
                }
            });
        }
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String body = get(weatherUrl());
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", weatherPlace()).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private void fetchSportsData() {
        String f1Val = "USA (23/10)";
        String alonsoVal = "Aston Martin";
        String tfcVal = "vs Troyes (25/10)";

        // 1. Prochain GP
        try {
            String f1Json = get("https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard");
            JSONObject o = new JSONObject(f1Json);
            JSONArray events = o.optJSONArray("events");
            if (events != null && events.length() > 0) {
                JSONObject ev = events.getJSONObject(0);
                String name = ev.optString("name", "GP")
                        .replace("MSC Cruises ", "").replace("United States", "USA")
                        .replace("Mexico City", "Mexique").replace(" Grand Prix", "").trim();
                String dateIso = ev.optString("date", "");
                String d = dateIso.length() >= 10 ? " (" + dateIso.substring(8, 10) + "/" + dateIso.substring(5, 7) + ")" : "";
                f1Val = name + d;
                sports.f1Next = "🏎️ " + f1Val;
            }
        } catch (Exception e) {
            sports.f1Next = "🏎️ " + f1Val;
        }

        // 2. Alonso Classement et Points
        try {
            String standJson = get("https://site.api.espn.com/apis/v2/sports/racing/f1/standings");
            JSONObject o = new JSONObject(standJson);
            JSONArray children = o.optJSONArray("children");
            if (children != null && children.length() > 0) {
                JSONArray stList = children.getJSONObject(0).optJSONArray("standings");
                if (stList != null && stList.length() > 0) {
                    JSONArray entries = stList.getJSONObject(0).optJSONArray("entries");
                    if (entries != null) {
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject ent = entries.getJSONObject(i);
                            JSONObject ath = ent.optJSONObject("athlete");
                            String disp = ath != null ? ath.optString("displayName", "") : "";
                            if (disp.toLowerCase(Locale.ROOT).contains("alonso")) {
                                JSONArray stats = ent.optJSONArray("stats");
                                String rk = String.valueOf(i + 1);
                                String pts = "";
                                if (stats != null) {
                                    for (int s = 0; s < stats.length(); s++) {
                                        JSONObject st = stats.getJSONObject(s);
                                        if ("rank".equalsIgnoreCase(st.optString("name"))) rk = st.optString("displayValue", rk);
                                        if ("points".equalsIgnoreCase(st.optString("name"))) pts = st.optString("displayValue", "");
                                    }
                                }
                                alonsoVal = "P" + rk + (pts.isEmpty() ? "" : " · " + pts + " pts");
                                break;
                            }
                        }
                    }
                }
            }
            sports.alonsoRank = "Alonso " + alonsoVal;
        } catch (Exception e) {
            sports.alonsoRank = "Alonso (AM)";
        }

        // 3. TFC Prochain match
        try {
            String tfcJson = get("https://site.api.espn.com/apis/site/v2/sports/soccer/fra.1/teams/179");
            JSONObject o = new JSONObject(tfcJson);
            JSONObject team = o.optJSONObject("team");
            JSONArray nextEvents = team != null ? team.optJSONArray("nextEvent") : null;
            if (nextEvents != null && nextEvents.length() > 0) {
                JSONObject nextEv = nextEvents.getJSONObject(0);
                String name = nextEv.optString("name", "");
                String opp = name.replace("Toulouse", "").replace("at ", "").replace("vs ", "").trim();
                String dateIso = nextEv.optString("date", "");
                String d = dateIso.length() >= 10 ? " (" + dateIso.substring(8, 10) + "/" + dateIso.substring(5, 7) + ")" : "";
                tfcVal = "vs " + (opp.isEmpty() ? "Troyes" : opp) + d;
                sports.tfcNext = "⚽ TFC " + tfcVal;
            }
        } catch (Exception e) {
            sports.tfcNext = "⚽ TFC " + tfcVal;
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        String lang = Ui.language.replaceAll("-.*", "");
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? Ui.tr("Clear") : Ui.tr("night|Clear");
        if (code == 1) return day ? Ui.tr("Mostly Sunny") : Ui.tr("Mostly Clear");
        if (code == 2) return Ui.tr("Partly Cloudy");
        if (code == 3) return Ui.tr("Cloudy");
        if (code == 45 || code == 48) return Ui.tr("Fog");
        if (code >= 51 && code <= 57) return Ui.tr("Drizzle");
        if (code == 65 || code == 67 || code == 82) return Ui.tr("Heavy Rain");
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Ui.tr("Snow");
        if (code >= 95) return Ui.tr("Thunderstorms");
        return Ui.tr("Rain");
    }

    static String highLow(Weather wx, String space) {
        return Ui.trf("H:{max}", "max", degrees(wx.max)) + space + Ui.trf("L:{min}", "min", degrees(wx.min));
    }

    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.datePattern("format|h a").format(at));
    }

    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        return minutes < 60 ? Ui.trf("{n} min ago", "n", minutes) : Ui.trf("{n} h ago", "n", minutes / 60);
    }

    static String sportsHeadline() {
        if (instance == null) return "";
        Sports s = instance.sports;
        List<String> list = new ArrayList<>();
        if (!s.f1Next.isEmpty()) list.add(s.f1Next);
        if (!s.alonsoRank.isEmpty()) list.add(s.alonsoRank);
        if (!s.tfcNext.isEmpty()) list.add(s.tfcNext);
        return String.join("  ·  ", list);
    }

    static String price(double v) { return "—"; }
    static String compact(double v) { return "—"; }
    static String brl(double v) { return "—"; }
    static String change(double v) { return ""; }

    public static String f1Text() { return instance != null && instance.sports != null ? instance.sports.f1Next : ""; }
    public static String alonsoText() { return instance != null && instance.sports != null ? instance.sports.alonsoRank : ""; }
    public static String tfcText() { return instance != null && instance.sports != null ? instance.sports.tfcNext : ""; }
}
