package ru.mok.zamer;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

public class MainActivity extends Activity {
    DB db; LinearLayout root; int currentOrder=-1;
    final String[] systems={"Рулонные MINI","UNI-1","UNI-2","Открытая система","Кассетная система","День-ночь","Горизонтальные","Вертикальные","Плиссе","Другая система"};
    final String[] controlSides={"Не выбрано","Слева","Справа"};
    final String[] mountTypes={"На оконный проём","На створки"};
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
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id,room,system,width,height,control_side,mount_type FROM windows WHERE order_id=? ORDER BY id",new String[]{""+id})){
            while(c.moveToNext()){final int wid=c.getInt(0);String side=c.getString(5);
                label("Окно №"+wid+" · "+c.getString(1)+" · "+c.getString(2)+" · "+c.getInt(3)+" × "+c.getInt(4)+" мм"+"\nУправление: "+(side==null||side.isEmpty()?"Не выбрано":side)+"\nМонтаж: "+(c.getString(6)==null?"Не указан":c.getString(6)));
                root.addView(new WindowDrawing(this,c.getInt(3),c.getInt(4)));
                showSections(wid);
                action("Конструктор створок и форточек №"+wid,()->editSections(id,wid));
                action("Изменить окно №"+wid,()->editWindow(id,wid));
                action("Удалить окно №"+wid,()->confirmDeleteWindow(id,wid));}
        }action("К списку заказов",this::showOrders);
    }

    final String[] sectionTypes={"Поворотная","Поворотно-откидная","Откидная","Глухая"};
    static class Section {
        int width, ventHeight;
        String type;
        Section(int w,String t,int v){width=w;type=t;ventHeight=v;}
    }
    ArrayList<Section> loadSections(int windowId){
        ArrayList<Section> result=new ArrayList<>();
        try(Cursor c=db.getReadableDatabase().rawQuery(
            "SELECT width,type,vent_height FROM sections WHERE window_id=? ORDER BY position",
            new String[]{""+windowId})){
            while(c.moveToNext())result.add(new Section(c.getInt(0),c.getString(1),c.getInt(2)));
        }
        return result;
    }
    void showSections(int windowId){
        ArrayList<Section> sections=loadSections(windowId);
        if(sections.isEmpty())return;
        StringBuilder summary=new StringBuilder("Конструкция: ");
        for(int i=0;i<sections.size();i++){
            Section sec=sections.get(i);
            if(i>0)summary.append(" | ");
            summary.append(i+1).append(": ").append(sec.type).append(" ").append(sec.width).append(" мм");
            if(sec.ventHeight>0)summary.append(", форточка ").append(sec.ventHeight).append(" мм");
        }
        label(summary.toString());
        root.addView(new SectionsDrawing(this,sections));
    }
    void editSections(int orderId,int windowId){
        ArrayList<Section> sections=loadSections(windowId);
        if(sections.isEmpty()){
            int width=1000;
            try(Cursor c=db.getReadableDatabase().rawQuery("SELECT width FROM windows WHERE id=?",new String[]{""+windowId})){
                if(c.moveToFirst())width=c.getInt(0);
            }
            sections.add(new Section(width,"Поворотная",0));
        }
        renderSectionEditor(orderId,windowId,sections);
    }
    void renderSectionEditor(int orderId,int windowId,ArrayList<Section> sections){
        currentOrder=orderId;
        screen("Конструктор окна №"+windowId);
        label("Вид из комнаты. Добавьте секции слева направо. Форточка располагается сверху выбранной секции.");
        root.addView(new SectionsDrawing(this,sections));
        ArrayList<EditText> widths=new ArrayList<>();
        ArrayList<EditText> vents=new ArrayList<>();
        ArrayList<Spinner> types=new ArrayList<>();
        for(int i=0;i<sections.size();i++){
            Section sec=sections.get(i);
            label("Секция "+(i+1));
            Spinner type=spinner(sectionTypes,sec.type);types.add(type);
            EditText width=field("Ширина секции, мм");width.setInputType(2);width.setText(""+sec.width);widths.add(width);
            EditText vent=field("Высота верхней форточки, мм (0 = нет)");vent.setInputType(2);vent.setText(""+sec.ventHeight);vents.add(vent);
        }
        action("+ Добавить створку / секцию",()->{
            if(!readSections(sections,widths,types,vents))return;
            if(sections.size()>=8){message("Максимум 8 секций");return;}
            sections.add(new Section(600,"Поворотная",0));
            renderSectionEditor(orderId,windowId,sections);
        });
        if(sections.size()>1)action("Удалить последнюю секцию",()->{
            if(!readSections(sections,widths,types,vents))return;
            sections.remove(sections.size()-1);
            renderSectionEditor(orderId,windowId,sections);
        });
        action("Сохранить конструкцию",()->{
            if(!readSections(sections,widths,types,vents))return;
            SQLiteDatabase database=db.getWritableDatabase();
            database.beginTransaction();
            try{
                database.delete("sections","window_id=?",new String[]{""+windowId});
                for(int i=0;i<sections.size();i++){
                    Section sec=sections.get(i);
                    ContentValues v=new ContentValues();
                    v.put("window_id",windowId);v.put("position",i);
                    v.put("width",sec.width);v.put("type",sec.type);v.put("vent_height",sec.ventHeight);
                    if(database.insertOrThrow("sections",null,v)<0)throw new RuntimeException("Insert failed");
                }
                database.setTransactionSuccessful();
                message("Конструкция сохранена");
            }catch(Exception e){message("Ошибка сохранения конструкции");return;}
            finally{database.endTransaction();}
            showOrder(orderId);
        });
        action("Назад к заказу",()->showOrder(orderId));
    }
    boolean readSections(ArrayList<Section> sections,ArrayList<EditText> widths,ArrayList<Spinner> types,ArrayList<EditText> vents){
        for(int i=0;i<sections.size();i++){
            try{
                int w=Integer.parseInt(widths.get(i).getText().toString().trim());
                String vtext=vents.get(i).getText().toString().trim();
                int v=vtext.isEmpty()?0:Integer.parseInt(vtext);
                if(w<1||w>20000||v<0||v>20000){message("Проверьте размеры секции "+(i+1));return false;}
                sections.get(i).width=w;
                sections.get(i).ventHeight=v;
                sections.get(i).type=types.get(i).getSelectedItem().toString();
            }catch(Exception e){message("Проверьте размеры секции "+(i+1));return false;}
        }
        return true;
    }
    static class SectionsDrawing extends View{
        final ArrayList<Section> sections;
        final Paint paint=new Paint(3);
        SectionsDrawing(Context c,ArrayList<Section> s){super(c);sections=new ArrayList<>(s);}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),(int)(200*getResources().getDisplayMetrics().density));}
        @Override protected void onDraw(Canvas canvas){
            float d=getResources().getDisplayMetrics().density;
            float total=0;for(Section sec:sections)total+=sec.width;
            if(total<=0)return;
            float left=12*d,top=15*d,right=getWidth()-12*d,bottom=getHeight()-35*d;
            float x=left;
            for(int i=0;i<sections.size();i++){
                Section sec=sections.get(i);
                float next=x+(right-left)*sec.width/total;
                paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(224,239,248));
                canvas.drawRect(x,top,next,bottom,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*d);paint.setColor(Color.rgb(30,75,112));
                canvas.drawRect(x,top,next,bottom,paint);
                if(sec.ventHeight>0){
                    float split=top+(bottom-top)*0.32f;
                    canvas.drawLine(x,split,next,split,paint);
                    canvas.drawLine(x,top,next,split,paint);
                }
                paint.setStyle(Paint.Style.FILL);paint.setTextSize(11*d);paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(""+(i+1), (x+next)/2,(top+bottom)/2,paint);
                canvas.drawText(sec.width+" мм",(x+next)/2,bottom+17*d,paint);
                x=next;
            }
        }
    }
    static class WindowDrawing extends View {
        final int widthMm, heightMm;
        final Paint p = new Paint(3);
        WindowDrawing(Context context, int w, int h) {
            super(context);
            widthMm = w;
            heightMm = h;
            setMinimumHeight((int)(240 * getResources().getDisplayMetrics().density));
        }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int h = (int)(250 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(w, h);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            canvas.drawColor(Color.rgb(248,250,253));
            if (widthMm <= 0 || heightMm <= 0) return;
            float availableW = w - 112*density;
            float availableH = h - 94*density;
            float scale = Math.min(availableW/widthMm, availableH/heightMm);
            float rw = widthMm*scale, rh = heightMm*scale;
            float left = (w-rw)/2f - 8*density;
            float top = (h-rh)/2f + 8*density;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(223,237,247));
            canvas.drawRect(left,top,left+rw,top+rh,p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3*density);
            p.setColor(Color.rgb(28,83,124));
            canvas.drawRect(left,top,left+rw,top+rh,p);
            p.setStrokeWidth(density);
            canvas.drawLine(left+rw/2,top,left+rw/2,top+rh,p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(14*density);
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(widthMm+" мм",w/2,top-15*density,p);
            p.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(heightMm+" мм",left+rw+9*density,top+rh/2,p);
            p.setTextSize(11*density);
            p.setColor(Color.DKGRAY);
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Схема окна · вид из комнаты",w/2,h-10*density,p);
        }
    }
    void label(String value){TextView t=new TextView(this);t.setText(value);t.setTextSize(17);t.setPadding(0,14,0,10);root.addView(t);}
    Spinner spinner(String[] options,String selected){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,options));root.addView(s);
        if(selected!=null){for(int i=0;i<options.length;i++){if(options[i].equals(selected)){s.setSelection(i);break;}}}return s;}
    Spinner systemSpinner(String selected){return spinner(systems,selected);}
    Spinner controlSpinner(String selected){label("Сторона управления (вид из комнаты лицом к окну)");return spinner(controlSides,selected);}
    boolean validSizes(EditText width,EditText height){try{int w=Integer.parseInt(width.getText().toString().trim());int h=Integer.parseInt(height.getText().toString().trim());
        if(w<=0||h<=0||w>20000||h>20000){message("Проверьте размеры");return false;}return true;
        }catch(Exception ex){message("Введите ширину и высоту в мм");return false;}}
    boolean validSide(Spinner side){if(side.getSelectedItemPosition()==0){message("Выберите сторону управления");return false;}return true;}
    void newWindow(int orderId){currentOrder=orderId;screen("Новое окно · заказ №"+orderId);EditText room=field("Помещение / название окна");Spinner system=systemSpinner(null);
        label("Где будут установлены жалюзи?");Spinner mount=spinner(mountTypes,null);
        label("Размеры изделия (мм)");
        EditText width=field("Ширина, мм");width.setInputType(2);EditText height=field("Высота, мм");height.setInputType(2);
        Spinner side=controlSpinner(null);
        action("Сохранить окно",()->{if(!validSizes(width,height)||!validSide(side))return;
            ContentValues v=new ContentValues();v.put("order_id",orderId);v.put("mount_type",mount.getSelectedItem().toString());v.put("mount_type",mount.getSelectedItem().toString());v.put("room",room.getText().toString());v.put("system",system.getSelectedItem().toString());v.put("width",Integer.parseInt(width.getText().toString().trim()));v.put("height",Integer.parseInt(height.getText().toString().trim()));v.put("control_side",side.getSelectedItem().toString());
            if(db.getWritableDatabase().insert("windows",null,v)>0)showOrder(orderId);else message("Ошибка сохранения");});action("Назад",()->showOrder(orderId));
    }
    void editWindow(int orderId,int windowId){
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT room,system,width,height,control_side,mount_type FROM windows WHERE id=? AND order_id=?",new String[]{""+windowId,""+orderId})){
            if(!c.moveToFirst()){message("Окно не найдено");showOrder(orderId);return;}
            currentOrder=orderId;screen("Изменить окно №"+windowId);
            EditText room=field("Помещение / название окна");room.setText(c.getString(0));
            Spinner system=systemSpinner(c.getString(1));
            label("Где будут установлены жалюзи?");Spinner mount=spinner(mountTypes,c.getString(5));
            label("Размеры изделия (мм)");
            EditText width=field("Ширина, мм");width.setInputType(2);width.setText(String.valueOf(c.getInt(2)));
            EditText height=field("Высота, мм");height.setInputType(2);height.setText(String.valueOf(c.getInt(3)));
            Spinner side=controlSpinner(c.getString(4));
            action("Сохранить изменения",()->{if(!validSizes(width,height)||!validSide(side))return;
                ContentValues v=new ContentValues();v.put("room",room.getText().toString());v.put("system",system.getSelectedItem().toString());v.put("width",Integer.parseInt(width.getText().toString().trim()));v.put("height",Integer.parseInt(height.getText().toString().trim()));v.put("control_side",side.getSelectedItem().toString());
                int changed=db.getWritableDatabase().update("windows",v,"id=? AND order_id=?",new String[]{""+windowId,""+orderId});
                if(changed>0){message("Изменения сохранены");showOrder(orderId);}else message("Не удалось сохранить изменения");});
            action("Отмена",()->showOrder(orderId));
        }
    }
    void confirmDeleteWindow(int orderId,int windowId){new AlertDialog.Builder(this).setTitle("Удалить окно №"+windowId+"?")
        .setMessage("Окно будет удалено из заказа. Отменить удаление нельзя.")
        .setNegativeButton("Отмена",(d,w)->d.dismiss())
        .setPositiveButton("Удалить",(d,w)->{db.getWritableDatabase().delete("sections","window_id=?",new String[]{""+windowId});int deleted=db.getWritableDatabase().delete("windows","id=? AND order_id=?",new String[]{""+windowId,""+orderId});
            if(deleted>0)message("Окно удалено");else message("Окно не найдено");showOrder(orderId);}).show();}
    @Override public void onBackPressed(){if(currentOrder>0)showOrders();else super.onBackPressed();}
    static class DB extends SQLiteOpenHelper{
        DB(Context c){super(c,"mok_zamer.db",null,4);}
        @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT,client TEXT NOT NULL,phone TEXT,address TEXT)");d.execSQL("CREATE TABLE windows(id INTEGER PRIMARY KEY AUTOINCREMENT,order_id INTEGER NOT NULL,room TEXT,system TEXT,width INTEGER NOT NULL,height INTEGER NOT NULL,control_side TEXT,mount_type TEXT,FOREIGN KEY(order_id) REFERENCES orders(id))");d.execSQL("CREATE TABLE sections(id INTEGER PRIMARY KEY AUTOINCREMENT,window_id INTEGER NOT NULL,position INTEGER NOT NULL,width INTEGER NOT NULL,type TEXT NOT NULL,vent_height INTEGER NOT NULL DEFAULT 0)");}
        @Override public void onUpgrade(SQLiteDatabase d,int oldV,int newV){if(oldV<2)d.execSQL("ALTER TABLE windows ADD COLUMN control_side TEXT");if(oldV<3)d.execSQL("ALTER TABLE windows ADD COLUMN mount_type TEXT");if(oldV<4)d.execSQL("CREATE TABLE sections(id INTEGER PRIMARY KEY AUTOINCREMENT,window_id INTEGER NOT NULL,position INTEGER NOT NULL,width INTEGER NOT NULL,type TEXT NOT NULL,vent_height INTEGER NOT NULL DEFAULT 0)");}
    }
}
