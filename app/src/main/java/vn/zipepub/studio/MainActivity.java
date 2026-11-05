package vn.zipepub.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int OPEN_ZIPS = 101;
    private static final int OPEN_COVER = 102;
    private static final int OPEN_BACKGROUND = 103;
    private static final int SAVE_EPUB = 104;

    private static final int NAVY = Color.rgb(29, 42, 73);
    private static final int BLUE = Color.rgb(63, 90, 203);
    private static final int TEXT = Color.rgb(31, 43, 67);
    private static final int MUTED = Color.rgb(108, 121, 144);
    private static final int BORDER = Color.rgb(222, 229, 239);
    private static final int BACKGROUND = Color.rgb(246, 248, 253);

    private final ArrayList<EpubExporter.Chapter> chapters = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy = false;
    private Uri coverUri;
    private Uri backgroundUri;
    private Uri lastResult;

    private LinearLayout root, chapterList;
    private EditText titleInput, authorInput;
    private RadioButton pageMode, scrollMode;
    private CheckBox rtlCheck;
    private TextView coverName, backgroundName, counter, status;
    private ProgressBar progress;
    private Button selectZipsBtn, generateBtn, sortBtn, chooseCoverBtn, chooseBgBtn, removeCoverBtn, removeBgBtn;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(BACKGROUND);
        renderScreen();
    }

    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density * n + 0.5f); }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextColor(color); t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout vertical() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private void spacing(LinearLayout v, int px) {
        View spacer = new View(this);
        v.addView(spacer, new LinearLayout.LayoutParams(1, dp(px)));
    }
    private LinearLayout section(String heading, String description) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(17), dp(17), dp(17), dp(18));
        panel.setBackground(shape(Color.WHITE, 18, BORDER));
        TextView title = text(heading, 19, TEXT, true);
        panel.addView(title);
        if (!description.isEmpty()) {
            TextView desc = text(description, 13, MUTED, false);
            desc.setPadding(0, dp(5), 0, dp(12));
            panel.addView(desc);
        } else spacing(panel, 12);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        spacing(root, 14);
        return panel;
    }
    private Button button(String value, boolean primary) {
        Button btn = new Button(this);
        btn.setText(value);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setMinHeight(dp(43));
        btn.setMinimumHeight(dp(43));
        btn.setBackground(shape(primary ? BLUE : Color.rgb(237, 241, 252), 11, primary ? 0 : BORDER));
        btn.setTextColor(primary ? Color.WHITE : BLUE);
        btn.setPadding(dp(12), dp(5), dp(12), dp(5));
        return btn;
    }
    private EditText edit(String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setTextColor(TEXT);
        e.setHint(hint);
        e.setText(value);
        e.setSelectAllOnFocus(false);
        e.setPadding(dp(13), 0, dp(13), 0);
        e.setBackground(shape(Color.WHITE, 10, BORDER));
        e.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(49)));
        return e;
    }
    private TextView fieldLabel(String name) {
        TextView l = text(name, 13, TEXT, true);
        l.setPadding(0, dp(7), 0, dp(7));
        return l;
    }
    private void renderScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        root = vertical();
        int regularPadding = dp(15);
        root.setPadding(regularPadding, dp(20), regularPadding, dp(38));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(regularPadding, dp(20) + bars.top, regularPadding, dp(38) + bars.bottom);
                return insets;
            });
        }
        scroll.addView(root);
        setContentView(scroll);

        TextView brand = text("ZIP  →  EPUB", 13, BLUE, true);
        brand.setLetterSpacing(0.12f);
        root.addView(brand);
        spacing(root, 7);
        TextView hero = text("ZIP to EPUB Studio", 28, NAVY, true);
        root.addView(hero);
        TextView description = text("Tạo sách ảnh EPUB trực tiếp trên điện thoại • Không cần mạng • Không giảm độ phân giải ảnh", 14, MUTED, false);
        description.setPadding(0, dp(8), 0, dp(22));
        root.addView(description);

        LinearLayout info = section("1. Thông tin truyện", "Tên truyện dùng làm tên file EPUB và trong thư viện đọc.");
        info.addView(fieldLabel("Tên truyện"));
        titleInput = edit("Tên truyện", "Truyện ảnh của tôi"); info.addView(titleInput);
        spacing(info, 7);
        info.addView(fieldLabel("Tác giả (tùy chọn)"));
        authorInput = edit("Tác giả", ""); info.addView(authorInput);

        LinearLayout files = section("2. Chương truyện", "Chọn nhiều ZIP/CBZ; đổi tên, xóa hoặc sắp xếp thứ tự từng chương.");
        LinearLayout fileButtons = horizontal();
        selectZipsBtn = button("+ Thêm ZIP / CBZ", true);
        sortBtn = button("Sắp xếp theo tên", false);
        fileButtons.addView(selectZipsBtn, new LinearLayout.LayoutParams(0, dp(47), 1.1f));
        LinearLayout.LayoutParams sortP = new LinearLayout.LayoutParams(0, dp(47), 1f);
        sortP.leftMargin=dp(8); fileButtons.addView(sortBtn, sortP);
        files.addView(fileButtons);
        spacing(files, 13);
        chapterList = vertical(); files.addView(chapterList);
        counter = text("Chưa có chương nào", 13, MUTED, true);
        counter.setPadding(0, dp(12), 0, 0);
        files.addView(counter);

        LinearLayout pictures = section("3. Ảnh bìa và ảnh nền", "Giữ nguyên ảnh gốc. Ảnh nền được đặt phía sau các trang truyện.");
        pictures.addView(fieldLabel("Ảnh bìa EPUB"));
        coverName = text("Chưa chọn ảnh bìa", 13, MUTED, false); pictures.addView(coverName);
        LinearLayout coverButtons = horizontal();
        chooseCoverBtn=button("Chọn ảnh bìa", false);
        removeCoverBtn=button("Xóa", false);
        coverButtons.addView(chooseCoverBtn);
        LinearLayout.LayoutParams rm1=new LinearLayout.LayoutParams(-2, dp(42)); rm1.leftMargin=dp(9);
        coverButtons.addView(removeCoverBtn,rm1);
        pictures.addView(coverButtons);
        spacing(pictures, 17);
        pictures.addView(fieldLabel("Ảnh nền các trang đọc"));
        backgroundName=text("Chưa chọn ảnh nền", 13, MUTED, false); pictures.addView(backgroundName);
        LinearLayout bgButtons=horizontal();
        chooseBgBtn=button("Chọn ảnh nền", false); removeBgBtn=button("Xóa", false);
        bgButtons.addView(chooseBgBtn);
        LinearLayout.LayoutParams rm2=new LinearLayout.LayoutParams(-2,dp(42));rm2.leftMargin=dp(9);
        bgButtons.addView(removeBgBtn,rm2);
        pictures.addView(bgButtons);
        TextView backgroundHelp=text("Nếu chưa có bìa, ảnh nền sẽ được dùng làm bìa. Một số app đọc EPUB có thể không hiển thị ảnh nền CSS.",12,MUTED,false);
        backgroundHelp.setPadding(0,dp(14),0,0);
        pictures.addView(backgroundHelp);

        LinearLayout reading = section("4. Kiểu đọc", "Chọn kiểu trình bày phù hợp manga hoặc webtoon.");
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        pageMode = new RadioButton(this); pageMode.setText("Từng trang (manga)"); pageMode.setTextColor(TEXT);
        scrollMode = new RadioButton(this); scrollMode.setText("Cuộn dọc theo chương (webtoon)"); scrollMode.setTextColor(TEXT);
        group.addView(pageMode); group.addView(scrollMode); pageMode.setChecked(true);
        reading.addView(group);
        rtlCheck = new CheckBox(this); rtlCheck.setText("Đọc từ phải sang trái (manga Nhật)");
        rtlCheck.setTextColor(TEXT); reading.addView(rtlCheck);

        LinearLayout export = section("5. Xuất EPUB", "Ảnh xử lý lần lượt; phần mềm kiểm tra số ảnh từng ZIP trước khi xuất.");
        generateBtn=button("TẠO FILE EPUB", true);
        generateBtn.setTextSize(16);
        generateBtn.setEnabled(false);
        export.addView(generateBtn,new LinearLayout.LayoutParams(-1,dp(54)));
        spacing(export, 12);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(BLUE));
        progress.setVisibility(View.GONE);
        export.addView(progress, new LinearLayout.LayoutParams(-1,dp(8)));
        status = text("Chọn ZIP để bắt đầu.", 13, MUTED, false);
        status.setPadding(0, dp(9),0,0);
        export.addView(status);
        TextView limitInfo = text("Không đặt giới hạn cứng cho số ZIP hoặc trang. Giới hạn thực tế: bộ nhớ trống để sao chép ZIP đang xử lý, dung lượng EPUB và khả năng mở sách của ứng dụng đọc.",12,MUTED,false);
        limitInfo.setPadding(0,dp(14),0,0);
        export.addView(limitInfo);

        selectZipsBtn.setOnClickListener(v -> openFiles(OPEN_ZIPS, "*/*", true));
        chooseCoverBtn.setOnClickListener(v -> openFiles(OPEN_COVER, "image/*", false));
        chooseBgBtn.setOnClickListener(v -> openFiles(OPEN_BACKGROUND, "image/*", false));
        removeCoverBtn.setOnClickListener(v -> {coverUri=null;updatePictures();});
        removeBgBtn.setOnClickListener(v -> {backgroundUri=null;updatePictures();});
        sortBtn.setOnClickListener(v -> {chapters.sort((a,b)->compareNatural(EpubExporter.titleForFile(a.fileName),EpubExporter.titleForFile(b.fileName)));renderChapters();});
        generateBtn.setOnClickListener(v -> requestSave());
        renderChapters();updatePictures();setBusy(false);
    }

    private static int compareNatural(String a, String b) {
        int i=0,j=0;
        while (i<a.length() && j<b.length()) {
            char x=a.charAt(i), y=b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int startI=i,startJ=j;
                while(i<a.length() && Character.isDigit(a.charAt(i)))i++;
                while(j<b.length() && Character.isDigit(b.charAt(j)))j++;
                String s=a.substring(startI,i).replaceFirst("^0+(?!$)","");
                String t=b.substring(startJ,j).replaceFirst("^0+(?!$)","");
                if(s.length()!=t.length())return Integer.compare(s.length(),t.length());
                int n=s.compareTo(t);if(n!=0)return n;
            }else{
                int n=Character.compare(Character.toLowerCase(x),Character.toLowerCase(y));
                if(n!=0)return n;
                i++;j++;
            }
        }
        return Integer.compare(a.length()-i,b.length()-j);
    }

    private void renderChapters() {
        if(chapterList==null) return;
        chapterList.removeAllViews();
        for(int i=0;i<chapters.size();i++) {
            final int index=i;
            EpubExporter.Chapter chapter=chapters.get(i);
            LinearLayout row = vertical();
            row.setPadding(dp(11),dp(11),dp(11),dp(9));
            row.setBackground(shape(Color.rgb(249,250,254),11,BORDER));
            TextView label = text("CHƯƠNG " + (i+1) + " · " + chapter.pageCount + " trang",11, BLUE,true);
            row.addView(label);
            EditText name=edit("Tên chương",chapter.title);
            name.setEnabled(!busy);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1,dp(41));
            ep.topMargin=dp(6);row.addView(name,ep);
            name.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                @Override public void onTextChanged(CharSequence s,int start,int before,int count){chapter.title=s.toString();}
                @Override public void afterTextChanged(Editable e){}
            });
            TextView filename=text(chapter.fileName,11,MUTED,false);
            filename.setSingleLine(true);
            filename.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            filename.setPadding(0,dp(8),0,dp(8));row.addView(filename);
            LinearLayout buttons=horizontal();
            Button up=button("↑",false),down=button("↓",false),remove=button("Xóa",false);
            up.setEnabled(!busy&&i>0); down.setEnabled(!busy&&i<chapters.size()-1); remove.setEnabled(!busy);
            buttons.addView(up);buttons.addView(down);
            LinearLayout.LayoutParams rmp=new LinearLayout.LayoutParams(-2,dp(40));rmp.leftMargin=dp(7);
            buttons.addView(remove,rmp);
            row.addView(buttons);
            up.setOnClickListener(v->{if(!busy&&index>0){java.util.Collections.swap(chapters,index,index-1);renderChapters();}});
            down.setOnClickListener(v->{if(!busy&&index<chapters.size()-1){java.util.Collections.swap(chapters,index,index+1);renderChapters();}});
            remove.setOnClickListener(v->{if(!busy){chapters.remove(chapter);renderChapters();}});
            chapterList.addView(row,new LinearLayout.LayoutParams(-1,-2));
            spacing(chapterList,9);
        }
        int pages=0;for(EpubExporter.Chapter c:chapters)pages+=c.pageCount;
        counter.setText(chapters.size()+" ZIP / chương · "+pages+" trang ảnh");
        if (chapters.isEmpty()) counter.setText("Chưa chọn ZIP/CBZ nào");
        generateBtn.setEnabled(!busy && !chapters.isEmpty());
    }

    private void updatePictures() {
        if(coverName!=null)coverName.setText(coverUri==null?"Chưa chọn ảnh bìa":EpubExporter.displayName(this,coverUri));
        if(backgroundName!=null)backgroundName.setText(backgroundUri==null?"Chưa chọn ảnh nền":EpubExporter.displayName(this,backgroundUri));
        if(removeCoverBtn!=null)removeCoverBtn.setVisibility(coverUri==null?View.GONE:View.VISIBLE);
        if(removeBgBtn!=null)removeBgBtn.setVisibility(backgroundUri==null?View.GONE:View.VISIBLE);
    }

    private void setBusy(boolean value) {
        busy=value;
        selectZipsBtn.setEnabled(!value);sortBtn.setEnabled(!value);
        chooseCoverBtn.setEnabled(!value);chooseBgBtn.setEnabled(!value);
        removeCoverBtn.setEnabled(!value);removeBgBtn.setEnabled(!value);
        generateBtn.setEnabled(!value&&!chapters.isEmpty());
        titleInput.setEnabled(!value);authorInput.setEnabled(!value);
        pageMode.setEnabled(!value);scrollMode.setEnabled(!value);rtlCheck.setEnabled(!value);
        renderChapters();
    }

    private void report(String text, boolean error) {
        ui.post(()->{
            status.setText(text);
            status.setTextColor(error?Color.rgb(183,49,55):MUTED);
        });
    }

    private void openFiles(int request, String type, boolean multiple) {
        if(busy)return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if(multiple)i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        startActivityForResult(i,request);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||busy)return;
        if(request==OPEN_ZIPS) {
            ArrayList<Uri> picked=new ArrayList<>();
            ClipData clips=data.getClipData();
            if(clips!=null){for(int i=0;i<clips.getItemCount();i++)picked.add(clips.getItemAt(i).getUri());}
            else if(data.getData()!=null)picked.add(data.getData());
            if(!picked.isEmpty())importZips(picked,data.getFlags());
        } else if(request==OPEN_COVER || request==OPEN_BACKGROUND) {
            Uri u=data.getData();
            if(u==null)return;
            persistRead(u,data.getFlags());
            if(request==OPEN_COVER)coverUri=u;else backgroundUri=u;
            updatePictures();
        } else if(request==SAVE_EPUB) {
            if(data.getData()!=null)export(data.getData());
        }
    }

    private void persistRead(Uri uri,int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch(Exception ignored) {} // Permission still valid for the current picker session.
    }

    private void importZips(List<Uri> picked,int flags) {
        setBusy(true);
        progress.setVisibility(View.VISIBLE);progress.setProgress(0);
        report("Đang quét ZIP...",false);
        worker.execute(()->{
            int good=0;ArrayList<String> errors=new ArrayList<>();
            for(int i=0;i<picked.size();i++) {
                Uri uri=picked.get(i);
                String name=EpubExporter.displayName(this,uri);
                if(!name.toLowerCase(Locale.ROOT).matches(".*\\.(zip|cbz)$")) {
                    errors.add(name+": không phải .zip hoặc .cbz");continue;
                }
                persistRead(uri,flags);
                try {
                    int pages=EpubExporter.countImages(this,uri);
                    if(pages==0)throw new IOException("Không có ảnh hỗ trợ trong ZIP");
                    EpubExporter.Chapter ch=new EpubExporter.Chapter(uri,name,EpubExporter.titleForFile(name),pages);
                    good++;
                    ui.post(()->{chapters.add(ch);renderChapters();});
                }catch(Exception e){errors.add(name+": "+e.getMessage());}
                final int current=i+1,total=picked.size();
                ui.post(()->{progress.setProgress(100*current/total);status.setText("Đã kiểm tra "+current+"/"+total+" ZIP");});
            }
            final int added=good;
            ui.post(()->{
                progress.setVisibility(View.GONE);
                setBusy(false);
                String result="Đã thêm "+added+" ZIP.";
                if(!errors.isEmpty())result+="\nLỗi: "+String.join("; ",errors);
                report(result,!errors.isEmpty());
            });
        });
    }

    private void requestSave() {
        if(busy||chapters.isEmpty())return;
        String name=titleInput.getText().toString().trim();
        if(name.isEmpty())name="Truyen_anh";
        name=name.replaceAll("[\\\\/:*?\"<>|]","_");
        if(name.length()>100)name=name.substring(0,100);
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/epub+zip");
        intent.putExtra(Intent.EXTRA_TITLE,name+".epub");
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent,SAVE_EPUB);
    }

    private void export(Uri out) {
        ArrayList<EpubExporter.Chapter> snapshot=new ArrayList<>();
        for(EpubExporter.Chapter c:chapters)
            snapshot.add(new EpubExporter.Chapter(c.uri,c.fileName,c.title.trim().isEmpty()?c.fileName:c.title,c.pageCount));
        final String title=titleInput.getText().toString().trim().isEmpty()?"Truyện ảnh":titleInput.getText().toString().trim();
        final String author=authorInput.getText().toString().trim();
        final boolean scroll=scrollMode.isChecked();
        final boolean rtl=rtlCheck.isChecked();
        final Uri cover=coverUri,background=backgroundUri;
        setBusy(true);
        progress.setProgress(0); progress.setVisibility(View.VISIBLE);
        report("Đang xuất EPUB...",false);
        worker.execute(()->{
            try {
                EpubExporter.export(this,out,snapshot,cover,background,title,author,scroll,rtl,
                    (done,total,message)->ui.post(()->{
                        progress.setProgress((int)(100.0*done/Math.max(1,total)));
                        status.setText(message);
                    }));
                lastResult=out;
                ui.post(()->{
                    progress.setProgress(100);setBusy(false);
                    int total=0;for(EpubExporter.Chapter c:snapshot)total+=c.pageCount;
                    status.setText("Hoàn tất: "+snapshot.size()+" chương / "+total+" trang. EPUB đã lưu.");
                    new AlertDialog.Builder(this)
                        .setTitle("Đã tạo EPUB")
                        .setMessage("Đã ghép đủ "+total+" ảnh từ "+snapshot.size()+" ZIP. Bạn có thể mở file bằng ứng dụng đọc EPUB.")
                        .setPositiveButton("Mở EPUB",(d,w)->openEpub(out))
                        .setNegativeButton("Đóng",null)
                        .show();
                });
            } catch(Exception e) {
                ui.post(()->{
                    setBusy(false);progress.setVisibility(View.GONE);
                    report("Không thể tạo EPUB: "+e.getMessage()+". Hãy xóa file EPUB chưa hoàn chỉnh nếu có.",true);
                });
            }
        });
    }

    private void openEpub(Uri uri) {
        Intent i=new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri,"application/epub+zip");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {startActivity(i);}catch(ActivityNotFoundException e){
            new AlertDialog.Builder(this).setMessage("Chưa có ứng dụng đọc EPUB phù hợp. Hãy mở file bằng ứng dụng đọc sách của bạn.").setPositiveButton("OK",null).show();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}
