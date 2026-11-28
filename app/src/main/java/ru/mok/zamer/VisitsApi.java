package ru.mok.zamer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.NoRouteToHostException;
import javax.net.ssl.SSLException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class VisitsApi {
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("mok_visits", Context.MODE_PRIVATE);
    }
    static String base(String input) throws Exception {
        URI uri = new URI(input.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new Exception("Введите HTTPS-адрес сервера МОК");
        String path = uri.getPath();
        if (!(path == null || path.isEmpty() || path.equals("/") ||
                path.equals("/blinds-visits") || path.equals("/blinds-visits/")))
            throw new Exception("Нужен адрес кабинета выездов МОК");
        return "https://" + uri.getRawAuthority() + "/blinds-visits/";
    }
    static final class Failure extends Exception {
        final int status;
        Failure(int value, String message) { super(message); status=value; }
    }
    static JSONObject post(String base, String token, String action, JSONObject data) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URI(base+"api/"+action).toURL().openConnection();
        String stage = "открытие соединения";
        try {
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(12000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");
            if (!token.isEmpty()) c.setRequestProperty("Authorization","Bearer "+token);
            try (OutputStream out = c.getOutputStream()) {
                stage = "отправка запроса";
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            }
            stage = "ожидание ответа";
            int code=c.getResponseCode();
            if(code >= 300 && code < 400) throw new Failure(code,"Сервер вернул перенаправление");
            stage = "чтение ответа";
            try(InputStream in=code>=400?c.getErrorStream():c.getInputStream()){
                ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
                while(in!=null && (n=in.read(buf))!=-1) {
                    if(out.size()+n>4*1024*1024)throw new Failure(code,"Ответ сервера слишком велик");
                    out.write(buf,0,n);
                }
                JSONObject body;
                try {body=new JSONObject(out.toString("UTF-8"));}
                catch(Exception e){throw new Failure(code,"Не удалось прочитать ответ сервера МОК");}
                if(code!=200)throw new Failure(code,body.optString("error","Ошибка сервера"));
                return body;
            }
        } catch (IOException e) {
            throw new IOException(stage + " · " + transportReason(e), e);
        } finally {c.disconnect();}
    }
    private static String transportReason(IOException e) {
        String reason;
        if (e instanceof SSLException) reason = "ошибка защищённого соединения";
        else if (e instanceof SocketTimeoutException) reason = "истекло время ожидания";
        else if (e instanceof UnknownHostException) reason = "адрес сервера не найден";
        else if (e instanceof NoRouteToHostException) reason = "нет маршрута к серверу";
        else if (e instanceof ConnectException) reason = "соединение не установлено";
        else if (e instanceof EOFException) reason = "соединение закрылось раньше ответа";
        else reason = "сбой соединения";
        String detail = e.getClass().getSimpleName();
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof android.system.ErrnoException) {
                String errno = android.system.OsConstants.errnoName(((android.system.ErrnoException) cause).errno);
                if (errno != null) detail += ", " + errno;
                break;
            }
            if (cause instanceof java.security.cert.CertificateException ||
                    cause instanceof java.security.cert.CertPathValidatorException) {
                detail += ", " + cause.getClass().getSimpleName();
                break;
            }
        }
        return reason + " (" + detail + ")";
    }

    static String describe(Exception error) {
        if (error instanceof Failure) return "HTTP " + ((Failure) error).status + " · " + error.getMessage();
        if (error instanceof IOException && error.getCause() instanceof IOException) return error.getMessage();
        return "ошибка запроса (" + error.getClass().getSimpleName() + ")";
    }

    static JSONObject obj(Object... pairs) {
        JSONObject json=new JSONObject();
        try {for(int i=0;i<pairs.length;i+=2)json.put((String)pairs[i],pairs[i+1]);}
        catch(org.json.JSONException e){throw new IllegalArgumentException("Некорректные данные",e);}
        return json;
    }
    private VisitsApi() {}
}
