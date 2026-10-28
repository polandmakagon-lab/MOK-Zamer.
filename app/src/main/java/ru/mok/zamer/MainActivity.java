package ru.mok.zamer;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfDocument;
import android.provider.MediaStore;
import android.net.Uri;
import java.io.OutputStream;
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
    void screen(String title){ScrollView s=new ScrollView(this);root=vertical();root.setPadding(22,56,22,18);s.addView(root);setContentView(s);TextView t=new TextView(this);t.setText(title);t.setTextSize(24);t.setTextColor(Color.rgb(20,55,95));root.addView(t);}
    EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);root.addView(e);return e;}
    void action(String label,Runnable r){Button b=new Button(this);b.setText(label);root.addView(b);b.setOnClickListener(v->r.run());}
    void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void showOrders(){currentOrder=-1;screen("МОК Замер · Заказы");action("+ Новый заказ",this::newOrder);
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id,client,phone FROM orders ORDER BY id DESC",null)){
            while(c.moveToNext()){final int id=c.getInt(0);action("№"+id+" · "+c.getString(1)+" · "+c.getString(2),()->showOrder(id));}
        }
    }
    void newOrder(){screen("Новый заказ");EditText client=field("Клиент / компания");EditText phone=field("Телефон");EditText address=field("Адрес объекта");
        action("Далее: конструктор окна",()->{if(client.getText().toString().trim().isEmpty()){message("Введите имя клиента");return;}
            ContentValues v=new ContentValues();v.put("client",client.getText().toString().trim());v.put("phone",phone.getText().toString());v.put("address",address.getText().toString());
            long id=db.getWritableDatabase().insert("orders",null,v);if(id>0)startWindow((int)id);else message("Ошибка сохранения");});action("Назад",this::showOrders);
    }
    void showOrder(int id){currentOrder=id;try(Cursor c=db.getReadableDatabase().rawQuery("SELECT client,phone,address FROM orders WHERE id=?",new String[]{""+id})){
            if(!c.moveToFirst()){showOrders();return;}screen("Заказ №"+id);label("Клиент: "+c.getString(0));label("Телефон: "+c.getString(1));label("Адрес: "+c.getString(2));}
        action("+ Добавить окно",()->startWindow(id));
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id,room,system,width,height,control_side,mount_type FROM windows WHERE order_id=? ORDER BY id",new String[]{""+id})){
            while(c.moveToNext()){final int wid=c.getInt(0);String side=c.getString(5);
                label("Окно №"+wid+" · "+c.getString(1)+" · "+c.getString(2)+" · "+c.getInt(3)+" × "+c.getInt(4)+" мм"+"\nУправление: "+(side==null||side.isEmpty()?"Не выбрано":side)+"\nМонтаж: "+(c.getString(6)==null?"Не указан":c.getString(6)));
                ArrayList<Section> orderSections=loadSections(wid);
                if(!orderSections.isEmpty()){
                    label("Чертёж замеров · вид из комнаты");
                    root.addView(new TapSectionsDrawing(this,orderSections,loadPartSizes(wid),loadPartSides(wid),
                        index->measurePart(id,wid,index+1)));
                }else{
                    label("Конструкция окна пока не создана");
                }
                String mountType=c.getString(6);
                if(mountType!=null&&mountType.equals(mountTypes[0])){
                    label("Замер всего окна: "+savedMeasurement(wid,0));
                }
                action("Сохранить / Поделиться",()->new AlertDialog.Builder(this)
                    .setTitle("Чертёж окна №"+wid)
                    .setItems(new String[]{"Сохранить PNG в галерею","Сохранить PDF","Поделиться PNG","Поделиться PDF"},
                        (dialog,which)->exportDrawing(wid,which==1||which==3,which>=2))
                    .show());
                action("Открыть окно: конструкция → монтаж → замеры №"+wid,()->editSections(id,wid));
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
        action("Далее: способ монтажа",()->{
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
            chooseMount(orderId,windowId);
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

    void startWindow(int orderId){
        ContentValues v=new ContentValues();
        v.put("order_id",orderId);v.put("room","Окно");v.put("system",systems[0]);
        v.put("width",1000);v.put("height",1200);
        v.put("control_side","Не выбрано");
        long id=db.getWritableDatabase().insert("windows",null,v);
        if(id>0)editSections(orderId,(int)id);else message("Не удалось создать окно");
    }
    void chooseMount(int orderId,int windowId){
        currentOrder=orderId;
        screen("Шаг 4 · Способ монтажа");
        label("Куда устанавливаем жалюзи?");
        String previous=null;
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT mount_type FROM windows WHERE id=?",new String[]{""+windowId})){
            if(c.moveToFirst())previous=c.getString(0);
        }
        Spinner mount=spinner(mountTypes,previous);
        action("Далее: замер",()->{
            ContentValues v=new ContentValues();v.put("mount_type",mount.getSelectedItem().toString());
            db.getWritableDatabase().update("windows",v,"id=?",new String[]{""+windowId});
            measureWindow(orderId,windowId);
        });
        action("Назад к конструктору",()->editSections(orderId,windowId));
    }
    void measureWindow(int orderId,int windowId){
        currentOrder=orderId;
        String mount=mountTypes[0],controlSide=controlSides[0];
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT mount_type,control_side FROM windows WHERE id=?",new String[]{""+windowId})){
            if(c.moveToFirst()){if(c.getString(0)!=null)mount=c.getString(0);if(c.getString(1)!=null)controlSide=c.getString(1);}
        }
        final boolean bySections=mount.equals(mountTypes[1]);
        ArrayList<Section> sections=loadSections(windowId);
        screen("Шаг 5 · Замеры");
        label("Монтаж: "+mount+" · размеры по ширине и высоте");
        if(bySections && !sections.isEmpty()){
            label("Нажмите на створку на чертеже, затем введите ширину и высоту изделия.");
            root.addView(new TapSectionsDrawing(this,sections,loadPartSizes(windowId),loadPartSides(windowId),index->measurePart(orderId,windowId,index+1)));
            label("Нажмите на секцию для ввода ширины и высоты. Для форточки используйте кнопку под схемой.");
            for(int i=0;i<sections.size();i++){
                final int index=i+1;
                label("Створка "+index+": "+savedMeasurement(windowId,index));
                if(sections.get(i).ventHeight>0){
                    final int ventKey=100+index;
                    action("Форточка "+index+" · "+savedMeasurement(windowId,ventKey),()->measurePart(orderId,windowId,ventKey));
                }
            }
        }else{
            label("Нажмите на окно для ввода общего размера.");
            WindowDrawing drawing=new WindowDrawing(this,1000,1200);
            drawing.setOnClickListener(v->measurePart(orderId,windowId,0));
            root.addView(drawing);
            label("Всё окно: "+savedMeasurement(windowId,0));
            action("Измерить всё окно",()->measurePart(orderId,windowId,0));
        }
        action("Изменить конструкцию",()->editSections(orderId,windowId));
        action("Изменить способ монтажа",()->chooseMount(orderId,windowId));
        action("Готово · к заказу",()->showOrder(orderId));
    }
    java.util.HashMap<Integer,int[]> loadPartSizes(int windowId){
        java.util.HashMap<Integer,int[]> sizes=new java.util.HashMap<>();
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT part,width,height FROM measurements WHERE window_id=?",new String[]{""+windowId})){
            while(c.moveToNext())sizes.put(c.getInt(0),new int[]{c.getInt(1),c.getInt(2)});
        }
        return sizes;
    }
    java.util.HashMap<Integer,String> loadPartSides(int windowId){
        java.util.HashMap<Integer,String> sides=new java.util.HashMap<>();
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT part,control_side FROM measurements WHERE window_id=?",new String[]{""+windowId})){
            while(c.moveToNext())sides.put(c.getInt(0),c.getString(1));
        }
        return sides;
    }
    String savedMeasurement(int windowId,int part){
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT width,height FROM measurements WHERE window_id=? AND part=?",
                new String[]{""+windowId,""+part})){
            if(c.moveToFirst())return c.getInt(0)+" × "+c.getInt(1)+" мм";
        }
        return "не измерено";
    }
    void measurePart(int orderId,int windowId,int part){
        String title=part==0?"Всё окно":part>100?"Форточка "+(part-100):"Створка "+part;
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(32,12,32,8);
        EditText width=new EditText(this);width.setHint("Ширина изделия, мм");width.setInputType(2);layout.addView(width);
        EditText height=new EditText(this);height.setHint("Высота изделия, мм");height.setInputType(2);layout.addView(height);
        TextView sideLabel=new TextView(this);sideLabel.setText("Управление жалюзи");layout.addView(sideLabel);
        Spinner side=new Spinner(this);side.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,controlSides));layout.addView(side);
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT width,height,control_side FROM measurements WHERE window_id=? AND part=?",
                new String[]{""+windowId,""+part})){
            if(c.moveToFirst()){width.setText(""+c.getInt(0));height.setText(""+c.getInt(1));String oldSide=c.getString(2);for(int i=0;i<controlSides.length;i++)if(controlSides[i].equals(oldSide))side.setSelection(i);}
        }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(layout)
            .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            int w,h;
            try{w=Integer.parseInt(width.getText().toString());h=Integer.parseInt(height.getText().toString());}
            catch(Exception e){message("Введите оба размера в миллиметрах");return;}
            if(w<=0||h<=0||w>20000||h>20000){message("Проверьте размеры");return;}
            ContentValues values=new ContentValues();
            values.put("window_id",windowId);values.put("part",part);values.put("width",w);values.put("height",h);values.put("control_side",side.getSelectedItem().toString());
            db.getWritableDatabase().insertWithOnConflict("measurements",null,values,SQLiteDatabase.CONFLICT_REPLACE);
            dialog.dismiss();measureWindow(orderId,windowId);
        }));
        dialog.show();
    }
    interface PartClick {void selected(int index);}
    static class TapSectionsDrawing extends SectionsDrawing{
        final PartClick callback;
        final java.util.HashMap<Integer,int[]> sizes;
        final java.util.HashMap<Integer,String> sides;
        int selected=-1;
        TapSectionsDrawing(Context c,ArrayList<Section> sections,java.util.HashMap<Integer,int[]> sizes,java.util.HashMap<Integer,String> sides,PartClick callback){
            super(c,sections);this.sizes=sizes;this.sides=sides;this.callback=callback;
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float d=getResources().getDisplayMetrics().density;
            float total=0;for(Section sec:sections)total+=sec.width;
            if(total<=0)return;
            float left=12*d,right=getWidth()-12*d,top=15*d,bottom=getHeight()-35*d;
            float x=left;
            for(int i=0;i<sections.size();i++){
                float next=x+(right-left)*sections.get(i).width/total;
                int[] size=sizes.get(i+1);
                paint.setStyle(Paint.Style.FILL);
                if(size!=null){
                    paint.setColor(Color.argb(85,55,168,98));
                    canvas.drawRect(x+2*d,top+2*d,next-2*d,bottom-2*d,paint);
                }
                if(i==selected){
                    paint.setColor(Color.argb(110,255,190,55));
                    canvas.drawRect(x+2*d,top+2*d,next-2*d,bottom-2*d,paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(Color.rgb(231,119,12));paint.setStrokeWidth(4*d);
                    canvas.drawRect(x+2*d,top+2*d,next-2*d,bottom-2*d,paint);
                }
                if(size!=null){
                    paint.setColor(Color.rgb(14,65,104));
                    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d);
                    float dimY=top+27*d;
                    canvas.drawLine(x+5*d,dimY,next-5*d,dimY,paint);
                    canvas.drawLine(x+5*d,dimY-4*d,x+5*d,dimY+4*d,paint);
                    canvas.drawLine(next-5*d,dimY-4*d,next-5*d,dimY+4*d,paint);
                    float dimX=next-10*d;
                    canvas.drawLine(dimX,top+39*d,dimX,bottom-7*d,paint);
                    canvas.drawLine(dimX-4*d,top+39*d,dimX+4*d,top+39*d,paint);
                    canvas.drawLine(dimX-4*d,bottom-7*d,dimX+4*d,bottom-7*d,paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(Math.min(11*d,Math.max(7*d,(next-x)/9)));
                    canvas.drawText(size[0]+" мм",(x+next)/2,top+21*d,paint);
                    canvas.save();
                    canvas.rotate(-90,dimX-5*d,(top+bottom)/2);
                    canvas.drawText(size[1]+" мм",dimX-5*d,(top+bottom)/2,paint);
                    canvas.restore();
                }
                String controlSide=sides.get(i+1);
                if(controlSide!=null&&!controlSide.equals("Не выбрано")){
                    float ovalX=controlSide.equals("Слева")?x+12*d:next-12*d;
                    float ovalY=top+(bottom-top)*0.76f;
                    paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(215,32,44));
                    canvas.drawOval(ovalX-5*d,ovalY-9*d,ovalX+5*d,ovalY+9*d,paint);
                }
                x=next;
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getAction()==MotionEvent.ACTION_DOWN)return true;
            if(event.getAction()==MotionEvent.ACTION_UP){
                float d=getResources().getDisplayMetrics().density;
                float x=event.getX(),left=12*d,right=getWidth()-12*d;
                if(x<left||x>right)return true;
                float total=0;for(Section sec:sections)total+=sec.width;
                float pos=(x-left)/(right-left)*total,acc=0;
                for(int i=0;i<sections.size();i++){
                    acc+=sections.get(i).width;
                    if(pos<=acc){selected=i;invalidate();performClick();callback.selected(i);break;}
                }
                return true;
            }
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
    String safe(String value){return value==null||value.trim().isEmpty()?"—":value.trim();}
    float drawWrapped(Canvas canvas,Paint paint,String text,float x,float y,float maxWidth,float lineHeight){
        String remaining=text==null?"":text;
        while(!remaining.isEmpty()){
            int count=paint.breakText(remaining,true,maxWidth,null);
            if(count<=0)break;
            if(count<remaining.length()){
                int space=remaining.lastIndexOf(' ',count);
                if(space>0)count=space;
            }
            canvas.drawText(remaining.substring(0,count).trim(),x,y,paint);
            remaining=remaining.substring(count).trim();
            y+=lineHeight;
        }
        return y;
    }
    Bitmap orderSheet(int windowId){
        ArrayList<Section> sections=loadSections(windowId);
        if(sections.isEmpty())return null;
        String[] data=new String[7];
        try(Cursor c=db.getReadableDatabase().rawQuery(
            "SELECT o.id,o.client,o.phone,o.address,w.room,w.system,w.mount_type FROM windows w JOIN orders o ON o.id=w.order_id WHERE w.id=?",
            new String[]{""+windowId})){
            if(!c.moveToFirst())return null;
            for(int i=0;i<data.length;i++)data[i]=c.getString(i);
        }
        int width=1200;
        Bitmap result=Bitmap.createBitmap(width,1450,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(result);canvas.drawColor(Color.WHITE);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(28,61,91));paint.setFakeBoldText(true);paint.setTextSize(52);
        canvas.drawText("МОК ЗАМЕР",45,83,paint);
        paint.setTextSize(32);paint.setFakeBoldText(false);paint.setColor(Color.DKGRAY);
        canvas.drawText("Заказ №"+safe(data[0])+"   |   Окно №"+windowId,45,145,paint);
        paint.setColor(Color.LTGRAY);paint.setStrokeWidth(2);canvas.drawLine(45,171,1155,171,paint);
        paint.setColor(Color.BLACK);paint.setTextSize(31);
        float y=220;
        y=drawWrapped(canvas,paint,"Заказчик: "+safe(data[1]),45,y,1100,43);
        y=drawWrapped(canvas,paint,"Телефон: "+safe(data[2]),45,y+9,1100,43);
        y=drawWrapped(canvas,paint,"Адрес: "+safe(data[3]),45,y+9,1100,43);
        y=drawWrapped(canvas,paint,"Комната / окно: "+safe(data[4]),45,y+9,1100,43);
        y=drawWrapped(canvas,paint,"Система жалюзи: "+safe(data[5]),45,y+9,1100,43);
        y=drawWrapped(canvas,paint,"Монтаж: "+safe(data[6]),45,y+9,1100,43);
        paint.setFakeBoldText(true);paint.setColor(Color.rgb(28,61,91));paint.setTextSize(36);
        canvas.drawText("Чертёж замера",45,y+40,paint);
        TapSectionsDrawing drawing=new TapSectionsDrawing(this,sections,loadPartSizes(windowId),loadPartSides(windowId),i->{});
        int drawWidth=1110,drawHeight=620;
        drawing.measure(View.MeasureSpec.makeMeasureSpec(drawWidth,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(drawHeight,View.MeasureSpec.EXACTLY));
        drawing.layout(0,0,drawWidth,drawHeight);
        canvas.save();canvas.translate(45,y+65);drawing.draw(canvas);canvas.restore();
        return result;
    }
    void exportDrawing(int windowId,boolean pdf,boolean share){
        Bitmap bitmap=orderSheet(windowId);
        if(bitmap==null){message("Сначала создайте окно и чертёж");return;}
        Uri uri=null;
        try{
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,"MOK_Zamer_window_"+windowId+(pdf?".pdf":".png"));
            String mime=pdf?"application/pdf":"image/png";
            values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,pdf?"Download/MOK Zamer":"Pictures/MOK Zamer");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            uri=getContentResolver().insert(pdf?MediaStore.Downloads.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new Exception("Не удалось создать файл");
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new Exception("Не удалось открыть файл");
                if(pdf){
                    PdfDocument doc=new PdfDocument();
                    try{
                        PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(1240,1500,1).create());
                        Canvas pc=page.getCanvas();pc.drawColor(Color.WHITE);
                        pc.drawBitmap(bitmap,20,20,new Paint(Paint.FILTER_BITMAP_FLAG));
                        doc.finishPage(page);doc.writeTo(out);
                    }finally{doc.close();}
                }else if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new Exception("Ошибка PNG");
            }
            ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
            getContentResolver().update(uri,ready,null,null);
            if(share){
                Intent send=new Intent(Intent.ACTION_SEND);send.setType(pdf?"application/pdf":"image/png");
                send.putExtra(Intent.EXTRA_STREAM,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send,"Отправить лист замера"));
            }else message(pdf?"PDF сохранён в Загрузки":"Лист замера сохранён в Галерею");
        }catch(Exception e){
            if(uri!=null)getContentResolver().delete(uri,null,null);
            message("Ошибка сохранения: "+e.getMessage());
        }finally{bitmap.recycle();}
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
        .setPositiveButton("Удалить",(d,w)->{db.getWritableDatabase().delete("sections","window_id=?",new String[]{""+windowId});db.getWritableDatabase().delete("measurements","window_id=?",new String[]{""+windowId});int deleted=db.getWritableDatabase().delete("windows","id=? AND order_id=?",new String[]{""+windowId,""+orderId});
            if(deleted>0)message("Окно удалено");else message("Окно не найдено");showOrder(orderId);}).show();}
    @Override public void onBackPressed(){if(currentOrder>0)showOrders();else super.onBackPressed();}
    static class DB extends SQLiteOpenHelper{
        DB(Context c){super(c,"mok_zamer.db",null,6);}
        @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT,client TEXT NOT NULL,phone TEXT,address TEXT)");d.execSQL("CREATE TABLE windows(id INTEGER PRIMARY KEY AUTOINCREMENT,order_id INTEGER NOT NULL,room TEXT,system TEXT,width INTEGER NOT NULL,height INTEGER NOT NULL,control_side TEXT,mount_type TEXT,FOREIGN KEY(order_id) REFERENCES orders(id))");d.execSQL("CREATE TABLE sections(id INTEGER PRIMARY KEY AUTOINCREMENT,window_id INTEGER NOT NULL,position INTEGER NOT NULL,width INTEGER NOT NULL,type TEXT NOT NULL,vent_height INTEGER NOT NULL DEFAULT 0)");d.execSQL("CREATE TABLE measurements(window_id INTEGER NOT NULL,part INTEGER NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,control_side TEXT,PRIMARY KEY(window_id,part))");}
        @Override public void onUpgrade(SQLiteDatabase d,int oldV,int newV){if(oldV<2)d.execSQL("ALTER TABLE windows ADD COLUMN control_side TEXT");if(oldV<3)d.execSQL("ALTER TABLE windows ADD COLUMN mount_type TEXT");if(oldV<4)d.execSQL("CREATE TABLE sections(id INTEGER PRIMARY KEY AUTOINCREMENT,window_id INTEGER NOT NULL,position INTEGER NOT NULL,width INTEGER NOT NULL,type TEXT NOT NULL,vent_height INTEGER NOT NULL DEFAULT 0)");if(oldV<5)d.execSQL("CREATE TABLE measurements(window_id INTEGER NOT NULL,part INTEGER NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,control_side TEXT,PRIMARY KEY(window_id,part))");if(oldV>=5&&oldV<6)d.execSQL("ALTER TABLE measurements ADD COLUMN control_side TEXT");}
    }
}
