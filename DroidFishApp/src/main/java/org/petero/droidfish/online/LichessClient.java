package org.petero.droidfish.online;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Map;

import org.json.JSONObject;

/** Minimal Lichess HTTP client. All calls block and must run off the UI thread. */
public final class LichessClient {
    private static final String BASE = "https://lichess.org";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;
    private static final int STREAM_READ_TIMEOUT = 60000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String token;

    public interface LineListener {
        void onLine(JSONObject obj);
    }

    /** Handle that can cancel a running stream from any thread. */
    public static final class Stream {
        private volatile HttpURLConnection conn;
        private volatile boolean cancelled;

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null)
                new Thread(c::disconnect).start(); // Closing TLS may do network I/O.
        }
    }

    public LichessClient(String token) {
        this.token = token;
    }

    public JSONObject get(String path) throws IOException {
        return parse(request("GET", path, null, READ_TIMEOUT));
    }

    public JSONObject post(String path, Map<String, String> form) throws IOException {
        return parse(request("POST", path, form, READ_TIMEOUT));
    }

    public void delete(String path) throws IOException {
        request("DELETE", path, null, READ_TIMEOUT);
    }

    /** Read an ndjson stream until it ends, fails or is cancelled. Empty keep-alive lines are skipped. */
    public void stream(String method, String path, Map<String, String> form,
                       Stream handle, LineListener listener) throws IOException {
        HttpURLConnection conn = open(method, path, form, STREAM_READ_TIMEOUT);
        handle.conn = conn;
        try {
            if (handle.cancelled)
                return;
            checkResponse(conn);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), UTF8))) {
                String line;
                while (!handle.cancelled && ((line = r.readLine()) != null)) {
                    line = line.trim();
                    if (line.isEmpty())
                        continue;
                    try {
                        listener.onLine(new JSONObject(line));
                    } catch (org.json.JSONException ignore) {
                    }
                }
            }
        } catch (IOException e) {
            if (!handle.cancelled)
                throw e;
        } finally {
            conn.disconnect();
        }
    }

    private String request(String method, String path, Map<String, String> form,
                           int readTimeout) throws IOException {
        HttpURLConnection conn = open(method, path, form, readTimeout);
        try {
            checkResponse(conn);
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String method, String path, Map<String, String> form,
                                   int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(readTimeout);
        conn.setInstanceFollowRedirects(false); // Never forward the token elsewhere.
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json, application/x-ndjson");
        conn.setRequestProperty("User-Agent", "AFGChess-Android");
        if (form != null) {
            byte[] body = encodeForm(form).getBytes(UTF8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
        } else if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);
            conn.getOutputStream().close();
        }
        return conn;
    }

    private static void checkResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code >= 200 && code < 300)
            return;
        String msg = "HTTP " + code;
        InputStream es = conn.getErrorStream();
        if (es != null) {
            try {
                JSONObject o = new JSONObject(readAll(es));
                String err = o.optString("error", "");
                if (!err.isEmpty())
                    msg = err;
            } catch (org.json.JSONException | IOException ignore) {
            }
        }
        if (code == 401)
            msg = "Not authorized. Please sign in again.";
        throw new IOException(msg);
    }

    private static JSONObject parse(String s) throws IOException {
        if (s == null || s.trim().isEmpty())
            return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, UTF8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0)
                sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static String encodeForm(Map<String, String> form) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0)
                sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }
}
