package ru.mok.zamer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class VisitsActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout layout;
    private TextView feedback;
    private Button shiftLocation;
    private long chosenShiftEnd;
    private final SharedPreferences.OnSharedPreferenceChangeListener shiftChanges =
        (settings, key) -> updateShiftLocation();
    private final ExecutorService net=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private JSONObject selected;
    private String pendingAlias="", page="";
    private int screenVersion;
    private boolean refreshing;
    private final Runnable tick=new Runnable(){public void run(){if(page.equals("queue"))refresh(false);handler.postDelayed(this,20000);}};
    interface Result {void done(JSONObject data) throws Exception;}
    private static final TimeZone MOSCOW=TimeZone.getTimeZone("Europe/Moscow");
    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=VisitsApi.prefs(this);
        if(state!=null)chosenShiftEnd=state.getLong("chosenShiftEnd");
        if(prefs.getString("token","").isEmpty())login();else queue();
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private void screen(String name,String title){
        shiftLocation=null;
        page=name;screenVersion++;refreshing=false;
        ScrollView scroll=new ScrollView(this);layout=new LinearLayout(this);layout.setOrientation(1);
        layout.setPadding(dp(18),dp(24),dp(18),dp(24));scroll.addView(layout);setContentView(scroll);
        TextView h=new TextView(this);h.setText(title);h.setTextSize(26);h.setTextColor(Color.rgb(19,64,62));layout.addView(h);
        feedback=label("");feedback.setTextColor(Color.rgb(143,66,19));feedback.setVisibility(View.GONE);
    }
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(16);t.setPadding(0,dp(5),0,dp(5));layout.addView(t);return t;}
    private void note(String s){feedback.setText(s);feedback.setVisibility(s.isEmpty()?View.GONE:View.VISIBLE);}
    private EditText input(String hint,int type,String val){EditText v=new EditText(this);v.setSingleLine();v.setInputType(type);v.setHint(hint);v.setText(val);layout.addView(v);return v;}
    private Button button(String s,Runnable fn){Button b=new Button(this);b.setAllCaps(false);b.setText(s);layout.addView(b);b.setOnClickListener(v->fn.run());return b;}
    private void request(String action,JSONObject data,Result done){
        String base=prefs.getString("base",""), token=prefs.getString("token","");int generation=screenVersion;
        net.execute(()->{
            try{
                JSONObject res=VisitsApi.post(base,token,action,data);
                runOnUiThread(()->{if(!isDestroyed() && screenVersion==generation){try{done.done(res);}catch(Exception e){note("Ошибка данных ответа");}}});
            }catch(Exception e){runOnUiThread(()->{
                if(isDestroyed()||screenVersion!=generation)return;refreshing=false;
                if(e instanceof VisitsApi.Failure && ((VisitsApi.Failure)e).status==401 && !action.endsWith("/login")){
                    if(WorkLocation.running)WorkLocation.stop(this);
                    prefs.edit().remove("token").apply();login();
                }
                note(e instanceof VisitsApi.Failure?e.getMessage():"Нет связи с сервером МОК");
            });}
        });
    }
    private void login(){
        screen("login","МОК Замер · вход");
        EditText server=input("HTTPS-адрес кабинета МОК",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI,prefs.getString("base",""));
        EditText login=input("Логин замерщика",InputType.TYPE_CLASS_TEXT,prefs.getString("login",""));
        EditText pw=input("Пароль",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,"");
        button("Войти",()->{
            String b;try{b=VisitsApi.base(server.getText().toString());}catch(Exception e){note(e.getMessage());return;}
            String user=login.getText().toString().trim();String oldBase=prefs.getString("base","");
            if(WorkLocation.pending(this) && (!oldBase.equals(b)||!user.equalsIgnoreCase(prefs.getString("login","")))){
                note("Сначала войдите в прежнюю учётную запись и завершите передачу геоданных");return;}
            prefs.edit().putString("base",b).apply();
            request("worker/login",VisitsApi.obj("login",user,"password",pw.getText().toString()),j->{
                prefs.edit().putString("token",j.getString("token")).putString("login",user).putString("name",j.getString("name")).commit();
                queue();
            });
        });
    }
    private void queue(){
        selected=null;screen("queue","Мои выезды");
        label(prefs.getString("name",""));geoControls();
        button("Обновить очередь",()->refresh(true));button("Выйти",this::logout);
        label("Загрузка назначений…");refresh(true);
    }
    private void logout(){
        if(WorkLocation.pending(this)){note("Сначала завершите передачу геоданных и дождитесь отправки");return;}
        request("logout",new JSONObject(),r->{prefs.edit().remove("token").remove("name").apply();login();});
    }
    private void geoControls(){
        shiftLocation=new Button(this,null,android.R.attr.borderlessButtonStyle);
        shiftLocation.setAllCaps(false);
        shiftLocation.setTextSize(14);
        shiftLocation.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        shiftLocation.setMinHeight(dp(48));
        layout.addView(shiftLocation,LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        shiftLocation.setOnClickListener(v->{
            if(prefs.getBoolean("closing",false) || (WorkLocation.pending(this) && WorkLocation.running)){
                WorkLocation.stop(this);
            }else if(WorkLocation.pending(this)){
                new AlertDialog.Builder(this).setTitle("Геолокация смены приостановлена")
                    .setPositiveButton("Продолжить",(dialog,which)->chooseShiftEnd())
                    .setNegativeButton("Завершить смену",(dialog,which)->WorkLocation.stop(this)).show();
            }else chooseShiftEnd();
        });
        updateShiftLocation();
    }
    private void updateShiftLocation(){
        if(shiftLocation==null)return;
        String text="Геолокация выключена · Начать смену";
        if(prefs.getBoolean("closing",false)){
            text="Геолокация выключена · Завершить отправку";
        }else if(WorkLocation.pending(this)){
            text=WorkLocation.running ? "Геолокация до "+WorkLocation.endTime(this)+" · Остановить"
                                      : "Геолокация приостановлена · Управление";
        }
        shiftLocation.setText(text);
    }
    private void chooseShiftEnd(){
        Calendar suggested=Calendar.getInstance();
        suggested.add(Calendar.HOUR_OF_DAY,8);
        TimePickerDialog picker=new TimePickerDialog(this,(view,hour,minute)->{
            Calendar end=Calendar.getInstance();
            end.set(Calendar.HOUR_OF_DAY,hour);
            end.set(Calendar.MINUTE,minute);
            end.set(Calendar.SECOND,0);
            end.set(Calendar.MILLISECOND,0);
            if(end.getTimeInMillis()<=System.currentTimeMillis())end.add(Calendar.DATE,1);
            chosenShiftEnd=end.getTimeInMillis();
            enableGeo();
        },suggested.get(Calendar.HOUR_OF_DAY),suggested.get(Calendar.MINUTE),true);
        picker.setTitle("До какого времени показывать маршрут администратору?");
        picker.show();
    }
    private void enableGeo(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},10);return;}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean("askedNotice",false)){prefs.edit().putBoolean("askedNotice",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);return;}
        NotificationManager manager=getSystemService(NotificationManager.class);
        NotificationChannel channel=manager.getNotificationChannel(WorkLocation.CHANNEL);
        if(!manager.areNotificationsEnabled() || (channel!=null && channel.getImportance()==NotificationManager.IMPORTANCE_NONE)){
            new AlertDialog.Builder(this).setMessage("Включите уведомления о рабочей геолокации. Из уведомления можно остановить передачу.")
                .setNegativeButton("Отмена",null).setPositiveButton("Настройки уведомлений",(dialog,which)->{
                    Intent settings=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    settings.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());
                    startActivity(settings);
                }).show();
            return;
        }
        if(chosenShiftEnd<=System.currentTimeMillis()){note("Выберите время окончания смены");return;}
        try{WorkLocation.start(this,chosenShiftEnd);chosenShiftEnd=0;note("Включаем передачу координат");}
        catch(RuntimeException e){note("Проверьте доступ к геолокации");}
    }
    private void refresh(boolean explicit){
        if(refreshing)return;refreshing=true;
        request("worker/jobs",new JSONObject(),j->{
            refreshing=false;screen("queue","Мои выезды");label(prefs.getString("name",""));geoControls();
            JSONArray jobs=j.getJSONArray("jobs");
            if(jobs.length()>0)label(j.optBoolean("has_location")?"Сначала ближайшие по прямой":"Без свежих координат · по времени встречи и заявки");
            else label("Назначенных замеров пока нет");
            for(int i=0;i<jobs.length();i++){
                JSONObject job=jobs.getJSONObject(i);String meters=job.isNull("meters")?"":String.format(Locale.forLanguageTag("ru")," · %.1f км",job.getDouble("meters")/1000);
                String st=!job.isNull("started")?"Замер начат":!job.isNull("appointment")?stamp(job.getLong("appointment")):"Позвонить";
                button(job.getString("address")+meters+"\n"+job.getString("name")+" · "+st,()->{details(job);if(job.isNull("appointment"))call(job);});
            }
            button("Обновить очередь",()->refresh(true));button("Выйти",this::logout);
        });
    }
    private String stamp(long t){
        SimpleDateFormat f=new SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.forLanguageTag("ru"));f.setTimeZone(MOSCOW);return f.format(new Date(t*1000))+" мск";
    }
    private void details(JSONObject job){
        selected=job;screen("job",job.optString("name"));label(job.optString("address"));
        if(!job.isNull("appointment"))label("Встреча · "+stamp(job.optLong("appointment")));
        if(!job.isNull("started"))label("Замер начат · "+stamp(job.optLong("started")));
        button("Позвонить клиенту",()->call(job));
        if(job.isNull("started"))button(job.isNull("appointment")?"Назначить встречу":"Изменить время встречи",()->schedule(job));
        if(!job.isNull("appointment"))button(job.isNull("started")?"Начать замер":"Открыть замер",()->startJob(job));
        button("К очереди",this::queue);
    }
    private void call(JSONObject job){
        request("worker/call",VisitsApi.obj("id",job.optString("id")),r->{
            String alias=r.getString("dial");
            if(!alias.matches("\\+[1-9][0-9]{6,14}")){note("Сервер не выдал номер АТС");return;}
            pendingAlias=alias;
            if(checkSelfPermission(Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED)dial();
            else requestPermissions(new String[]{Manifest.permission.CALL_PHONE},11);
        });
    }
    private void dial(){
        String alias=pendingAlias;pendingAlias="";
        if(alias.isEmpty())return;
        try{startActivity(new Intent(Intent.ACTION_CALL,Uri.fromParts("tel",alias,null)));}
        catch(RuntimeException e){note("Звонок не начался. Проверьте SIM-карту и доступ к звонкам");}
    }
    private void schedule(JSONObject job){
        Calendar cal=Calendar.getInstance(MOSCOW);if(!job.isNull("appointment"))cal.setTimeInMillis(job.optLong("appointment")*1000);
        new DatePickerDialog(this,(view,y,m,d)->{
            new TimePickerDialog(this,(time,h,min)->{
                cal.set(y,m,d,h,min,0);cal.set(Calendar.MILLISECOND,0);
                request("worker/appointment",VisitsApi.obj("id",job.optString("id"),"at",cal.getTimeInMillis()/1000),r->{
                    job.put("appointment",r.getLong("appointment"));details(job);note("Время отправлено администратору");
                });
            },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show();
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void startJob(JSONObject job){
        request("worker/start",VisitsApi.obj("id",job.optString("id")),r->{
            job.put("started",r.optLong("started",System.currentTimeMillis()/1000));details(job);
            Intent i=new Intent(this,MainActivity.class);i.putExtra("visit_ref",r.getString("id"));
            i.putExtra("visit_name",r.getString("name"));i.putExtra("visit_address",r.getString("address"));startActivity(i);
        });
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] results){
        super.onRequestPermissionsResult(code,names,results);
        if(code==10){if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)enableGeo();else note("Для передачи координат нужен доступ к геолокации");}
        if(code==12)enableGeo();
        if(code==11){if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)dial();else{pendingAlias="";note("Для звонка нужен доступ к телефону");}}
    }
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putLong("chosenShiftEnd",chosenShiftEnd);}
    @Override public void onResume(){super.onResume();prefs.registerOnSharedPreferenceChangeListener(shiftChanges);updateShiftLocation();handler.removeCallbacks(tick);handler.postDelayed(tick,20000);}
    @Override public void onPause(){prefs.unregisterOnSharedPreferenceChangeListener(shiftChanges);handler.removeCallbacks(tick);super.onPause();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);net.shutdown();super.onDestroy();}
    @Override public void onBackPressed(){if(page.equals("job"))queue();else super.onBackPressed();}
}
