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
    final String[] systems={"Рулонные MINI","UNI-1","UNI-2","Открытая система","Кассетная система","День-ночь","Горизонтальные","Вертикальные","Плиссе","Другая система"};
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
            while(c.moveToNext()){final int wid=c.getInt(0);label("Окно №"+wid+" · "+c.getString(1)+" · "+c.getString(2)+" · "+c.getInt(3)+" × "+c.getInt(4)+" мм");
                action("Изменить окно №"+wid,()->editWindow(id,wid));
                action("Удалить окно №"+wid,()->confirmDeleteWindow(id,wid));}
        }action("К списку заказов",this::showOrders);
    }
    void label(String value){TextView t=new TextView(this);t.setText(value);t.setTextSize(17);t.setPadding(0,14,0,10);root.addView(t);}
    Spinner systemSpinner(String selected){Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,systems));root.addView(spinner);
        if(selected!=null){for(int i=0;i<systems.length;i++){if(systems[i].equals(selected)){spinner.setSelection(i);break;}}}return spinner;}
    boolean validSizes(EditText width,EditText height){try{int w=Integer.parseInt(width.getText().toString().trim());int h=Integer.parseInt(height.getText().toString().trim());
        if(w<=0||h<=0||w>20000||h>20000){message("Проверьте размеры");return false;}return true;
        }catch(Exception ex){message("Введите ширину и высоту в мм");return false;}}
    void newWindow(int orderId){currentOrder=orderId;screen("Новое окно · заказ №"+orderId);EditText room=field("Помещение / название окна");Spinner system=systemSpinner(null);
        EditText width=field("Ширина, мм");width.setInputType(2);EditText height=field("Высота, мм");height.setInputType(2);
        action("Сохранить окно",()->{if(!validSizes(width,height))return;
            ContentValues v=new ContentValues();v.put("order_id",orderId);v.put("room",room.getText().toString());v.put("system",system.getSelectedItem().toString());v.put("width",Integer.parseInt(width.getText().toString().trim()));v.put("height",Integer.parseInt(height.getText().toString().trim()));
            if(db.getWritableDatabase().insert("windows",null,v)>0)showOrder(orderId);else message("Ошибка сохранения");});action("Назад",()->showOrder(orderId));
    }
    void editWindow(int orderId,int windowId){
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT room,system,width,height FROM windows WHERE id=? AND order_id=?",new String[]{""+windowId,""+orderId})){
            if(!c.moveToFirst()){message("Окно не найдено");showOrder(orderId);return;}
            currentOrder=orderId;screen("Изменить окно №"+windowId);
            EditText room=field("Помещение / название окна");room.setText(c.getString(0));
            Spinner system=systemSpinner(c.getString(1));
            EditText width=field("Ширина, мм");width.setInputType(2);width.setText(String.valueOf(c.getInt(2)));
            EditText height=field("Высота, мм");height.setInputType(2);height.setText(String.valueOf(c.getInt(3)));
            action("Сохранить изменения",()->{if(!validSizes(width,height))return;
                ContentValues v=new ContentValues();v.put("room",room.getText().toString());v.put("system",system.getSelectedItem().toString());v.put("width",Integer.parseInt(width.getText().toString().trim()));v.put("height",Integer.parseInt(height.getText().toString().trim()));
                int changed=db.getWritableDatabase().update("windows",v,"id=? AND order_id=?",new String[]{""+windowId,""+orderId});
                if(changed>0){message("Изменения сохранены");showOrder(orderId);}else message("Не удалось сохранить изменения");});
            action("Отмена",()->showOrder(orderId));
        }
    }
    void confirmDeleteWindow(int orderId,int windowId){new AlertDialog.Builder(this).setTitle("Удалить окно №"+windowId+"?")
        .setMessage("Окно будет удалено из заказа. Отменить удаление нельзя.")
        .setNegativeButton("Отмена",(d,w)->d.dismiss())
        .setPositiveButton("Удалить",(d,w)->{int deleted=db.getWritableDatabase().delete("windows","id=? AND order_id=?",new String[]{""+windowId,""+orderId});
            if(deleted>0)message("Окно удалено");else message("Окно не найдено");showOrder(orderId);}).show();}
    @Override public void onBackPressed(){if(currentOrder>0)showOrders();else super.onBackPressed();}
    static class DB extends SQLiteOpenHelper{
        DB(Context c){super(c,"mok_zamer.db",null,1);}
        @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT,client TEXT NOT NULL,phone TEXT,address TEXT)");d.execSQL("CREATE TABLE windows(id INTEGER PRIMARY KEY AUTOINCREMENT,order_id INTEGER NOT NULL,room TEXT,system TEXT,width INTEGER NOT NULL,height INTEGER NOT NULL,FOREIGN KEY(order_id) REFERENCES orders(id))");}
        @Override public void onUpgrade(SQLiteDatabase d,int oldV,int newV){}
    }
}
