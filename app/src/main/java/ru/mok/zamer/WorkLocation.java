package ru.mok.zamer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.*;
import android.os.*;
import org.json.JSONArray;
import java.util.UUID;
import java.util.concurrent.*;

public class WorkLocation extends Service implements LocationListener {
    static final String STOP="stop", CHANNEL="mok_visit_gps";
    static boolean running;
    private static final int NOTIFICATION=481;
    private LocationManager manager;
    private SQLiteDatabase queue;
    private ScheduledExecutorService io;
    private String track, base, token;
    private volatile boolean ending;
    private final Handler ui=new Handler(Looper.getMainLooper());
    @Override public void onCreate(){
        super.onCreate();
        manager=(LocationManager)getSystemService(LOCATION_SERVICE);
        io=Executors.newSingleThreadScheduledExecutor();
        queue=openOrCreateDatabase("visit_locations.db",MODE_PRIVATE,null);
        queue.execSQL("CREATE TABLE IF NOT EXISTS samples(track TEXT,at INTEGER,lat REAL,lon REAL,accuracy REAL,PRIMARY KEY(track,at))");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
            new NotificationChannel(CHANNEL,"Рабочая геолокация",NotificationManager.IMPORTANCE_LOW));
    }
    static boolean pending(Context c){return VisitsApi.prefs(c).contains("track");}
    static void start(Context c){c.startForegroundService(new Intent(c,WorkLocation.class));}
    static void stop(Context c){c.startForegroundService(new Intent(c,WorkLocation.class).setAction(STOP));}
    private Notification notice(String msg) {
        PendingIntent view=PendingIntent.getActivity(this,0,new Intent(this,VisitsActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WorkLocation.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("МОК Замер · геоданные").setContentText(msg).setContentIntent(view)
            .setOngoing(true).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
    }
    private void status(String msg){if(running)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notice(msg));}
    private void foreground() {
        Notification n=notice("Передача координат рабочего телефона");
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIFICATION,n);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        SharedPreferences prefs=VisitsApi.prefs(this);
        boolean stop=intent!=null && STOP.equals(intent.getAction());
        if(!running){
            try {foreground();} catch(RuntimeException e){stopSelf();return START_NOT_STICKY;}
            token=prefs.getString("token","");base=prefs.getString("base","");
            if(token.isEmpty() || (stop && !pending(this))){stopSelf();return START_NOT_STICKY;}
            track=prefs.getString("track",null);
            if(track==null){
                track=UUID.randomUUID().toString().replace("-","");
                prefs.edit().putString("track",track).putLong("openedAt",System.currentTimeMillis()/1000).putBoolean("opened",false).putBoolean("closing",false).commit();
            }
            running=true;
            ending=stop || prefs.getBoolean("closing",false);
            if(!ending) {
                boolean listening=false;
                for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
                    try{if(manager.isProviderEnabled(provider)){
                        manager.requestLocationUpdates(provider,30000,0,this);listening=true;
                    }}catch(SecurityException|IllegalArgumentException ignored){}
                }
                if(!listening) {ending=true;status("Нет доступа к геолокации");}
            }
            if(ending)prefs.edit().putBoolean("closing",true).commit();
            io.scheduleWithFixedDelay(this::sync,0,20,TimeUnit.SECONDS);
        } else if(stop && !ending) {
            ending=true;manager.removeUpdates(this);prefs.edit().putBoolean("closing",true).commit();
            status("GPS остановлен · завершаем отправку");io.execute(this::sync);
        }
        return START_NOT_STICKY;
    }
    @Override public void onLocationChanged(Location p){
        if(ending || track==null || !p.hasAccuracy())return;
        long ts=p.getTime()/1000; double lat=p.getLatitude(),lon=p.getLongitude();float acc=p.getAccuracy();
        long now=System.currentTimeMillis()/1000;
        if(Math.abs(now-ts)>120 || acc<0 || acc>100000)return;
        io.execute(()->{
            queue.execSQL("INSERT OR REPLACE INTO samples VALUES(?,?,?,?,?)",new Object[]{track,ts,lat,lon,acc});
        });
    }
    private void sync(){
        SharedPreferences p=VisitsApi.prefs(this);
        if(!running || !track.equals(p.getString("track","")))return;
        try {
            if(!p.getBoolean("opened",false)){
                VisitsApi.post(base,token,"worker/geo/open",VisitsApi.obj("track",track,"opened_at",p.getLong("openedAt",System.currentTimeMillis()/1000)));
                p.edit().putBoolean("opened",true).commit();
            }
            long expiry=System.currentTimeMillis()/1000-30L*86400+120;
            queue.execSQL("DELETE FROM samples WHERE at<?",new Object[]{expiry});
            while(running) {
                JSONArray batch=new JSONArray();long last=0;
                try(Cursor rows=queue.rawQuery("SELECT at,lat,lon,accuracy FROM samples WHERE track=? ORDER BY at LIMIT 100",new String[]{track})){
                    while(rows.moveToNext()){last=rows.getLong(0);batch.put(VisitsApi.obj("at",last,"lat",rows.getDouble(1),"lon",rows.getDouble(2),"accuracy",rows.getDouble(3)));}
                }
                if(batch.length()==0)break;
                VisitsApi.post(base,token,"worker/geo/points",VisitsApi.obj("track",track,"points",batch));
                queue.delete("samples","track=? AND at<=?",new String[]{track,Long.toString(last)});
            }
            if(ending) {
                VisitsApi.post(base,token,"worker/geo/close",VisitsApi.obj("track",track)); clear();
            } else status("Включено · координаты передаются в МОК");
        } catch(VisitsApi.Failure e) {
            if(e.status==401 || e.status==409)clear();
            else status("Сервер не принял координаты · "+e.getMessage());
        } catch(Exception e){status(ending?"GPS остановлен · ожидаем сеть":"Нет связи · координаты сохраняются на телефоне");}
    }
    private void clear(){
        queue.delete("samples","track=?",new String[]{track});
        VisitsApi.prefs(this).edit().remove("track").remove("openedAt").remove("opened").remove("closing").commit();
        ui.post(this::stopSelf);
    }
    @Override public void onDestroy(){
        running=false;ending=true;
        if(manager!=null)manager.removeUpdates(this);
        if(io!=null){io.execute(()->queue.close());io.shutdown();}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onProviderDisabled(String p) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onStatusChanged(String p,int status,Bundle extras) {}
}
