package ru.mok.zamer;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    DB db; LinearLayout root; int currentOrder=-1;
    @Override public void onCreate(Bundle b){super.onCreate(b);db=new DB(this);showOrders();}
    LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(22,18,22,18);return l;}
    void screen(String title){ScrollView s=new ScrollView(this);root=vertical();s.addView(root);setContentView(s);TextView t=new TextView(this);t.setText(title);t.setTextSize(24);t.setTextColor(Color.rgb(20,55,95));root.addView(t);}
    EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);root.addView(e);return e;}
    void action(String label,Runnable r){Button b=new Button(this);b.setText(label);root.addView(b);b.setOnClickListener(v->r.run());}
    void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void showOrders(){currentOrder=-1;screen("МОК Замер · Заказы");action("+ Новый заказ",this::newOrder);
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id,client,phone FROM orders ORDER BY id DESC",null)){
            while(c.moveToNext()){final int id=c.getInt(0);action("№"+id+" · "+c.getString(1)+" · "+c.getString(2),()->showOrder(id));}
        }
    }
    void newOrder(){screen("Новый заказ");EditText client=field("Клиент / компания");EditText phone=field("Телефон");EditText address=field("Адрес объекта");
        action("Сохранить и добавить окно",()->{if(client.getText().toString().trim().isEmpty()){message("Введите имя клиента");return;}
            ContentValues v=new ContentValues();v.put("client",client.getText().toString().trim());v.put("phone",phone.getText().toString());v.put("address",address.getText().toString());
            long id=db.getWritableDatabase().insert("orders",null,v);if(id>0)showOrder((int)id);else message("Ошибка сохранения");});action("Назад",this::showOrders);
    }
    void showOrder(int id){currentOrder=id;try(Cursor c=db.getReadableDatabase().rawQuery("SELECT client,phone,address FROM orders WHERE id=?",new String[]{""+id})){
            if(!c.moveToFirst()){showOrders();return;}screen("Заказ №"+id);label("Клиент: "+c.getString(0));label("Телефон: "+c.getString(1));label("Адрес: "+c.getString(2));}
        action("+ Добавить окно",()->newWindow(id));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id,room,system,width,height FROM windows WHERE order_id=? ORDER BY id",new String[]{""+id})){
            while(c.moveToNext()){int wid=c.getInt(0);label("Окно №"+wid+" · "+c.getString(1)+" · "+c.getString(2)+" · "+c.getInt(3)+" × "+c.getInt(4)+" мм");}
        }action("К списку заказов",this::showOrders);
    }
    void label(String value){TextView t=new TextView(this);t.setText(value);t.setTextSize(17);t.setPadding(0,14,0,10);root.addView(t);}
    void newWindow(int orderId){screen("Новое окно · заказ №"+orderId);EditText room=field("Помещение / название окна");
        Spinner system=new Spinner(this);String[] systems={"Рулонные MINI","UNI-1","UNI-2","Открытая система","Кассетная система","День-ночь","Горизонтальные","Вертикальные","Плиссе","Другая система"};
        system.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,systems));root.addView(system);
        EditText width=field("Ширина, мм");width.setInputType(2);EditText height=field("Высота, мм");height.setInputType(2);
        action("Сохранить окно",()->{int w,h;try{w=Integer.parseInt(width.getText().toString());h=Integer.parseInt(height.getText().toString());}catch(Exception ex){message("Введите ширину и высоту в мм");return;}
            if(w<=0||h<=0||w>20000||h>20000){message("Проверьте размеры");return;}
            ContentValues v=new ContentValues();v.put("order_id",orderId);v.put("room",room.getText().toString());v.put("system",system.getSelectedItem().toString());v.put("width",w);v.put("height",h);
            if(db.getWritableDatabase().insert("windows",null,v)>0)showOrder(orderId);else message("Ошибка сохранения");});action("Назад",()->showOrder(orderId));
    }
    @Override public void onBackPressed(){if(currentOrder>0)showOrders();else super.onBackPressed();}
    static class DB extends SQLiteOpenHelper{
        DB(Context c){super(c,"mok_zamer.db",null,1);}
        @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT,client TEXT NOT NULL,phone TEXT,address TEXT)");d.execSQL("CREATE TABLE windows(id INTEGER PRIMARY KEY AUTOINCREMENT,order_id INTEGER NOT NULL,room TEXT,system TEXT,width INTEGER NOT NULL,height INTEGER NOT NULL,FOREIGN KEY(order_id) REFERENCES orders(id))");}
        @Override public void onUpgrade(SQLiteDatabase d,int oldV,int newV){}
    }
}
