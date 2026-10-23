package com.jvust.flowsave;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** FlowSave 0.3. Native UI, explicit network consent, no background service. */
public class FlowActivity extends Activity {
    private final int BG=0xff0b1519, PANEL=0xff14242a, INPUT=0xff0f1d22,
            LINE=0xff2d4249, INK=0xffeff8f4, MUTED=0xff9cb0b3, MINT=0xffadf0ce;
    private final String[] QIDS={"best","2160","1080","720","compat","audio"};
    private final String[] QTEXT={"最高可用\n不限定分辨率","4K 上限\n≤ 2160P","1080P 上限\n高清优先","720P 上限\n节省空间","兼容 MP4\nH.264 / AAC","仅音频\n保留可用音轨"};
    private final String[] MODES={"video","images","cover"};
    private final String[] MODETEXT={"视频 / 音频","图片 / 图集","视频封面"};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<Uri> savedUris=new ArrayList<>();
    private final List<String> savedNames=new ArrayList<>();
    private final TextView[] qualityChips=new TextView[6], modeChips=new TextView[3];
    private JSONObject config=new JSONObject();
    private EditText link;
    private TextView title, detail, status, networkLabel, modeHint;
    private LinearLayout qualityCard;
    private ProgressBar progress;
    private Button analyze, download, cancel, open, network, cookies;
    private String quality="best", media="video";
    private volatile boolean busy=false, cancelled=false;
    private static final int PICK_COOKIES=301, STORAGE=302;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        String warning=null;
        try {config=SecureSettings.load(this);} catch(Exception e){warning="无法读取已加密的网络设置，请重新配置";}
        render();
        accept(getIntent());
        if(state!=null){link.setText(state.getString("url",""));quality=state.getString("quality","best");media=state.getString("media","video");refreshChips();}
        if(warning!=null) status.setText(warning);
        if((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0 && getIntent().getBooleanExtra("selftest",false)) selfTest();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);accept(intent);}
    @Override protected void onSaveInstanceState(Bundle b){b.putString("url",link.getText().toString());b.putString("quality",quality);b.putString("media",media);super.onSaveInstanceState(b);}
    private void accept(Intent intent){if(intent!=null && Intent.ACTION_SEND.equals(intent.getAction())){String s=intent.getStringExtra(Intent.EXTRA_TEXT);if(s!=null)link.setText(s);}}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(dp(2),1);return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams gap(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(17),dp(18),dp(17),dp(18));c.setBackground(shape(PANEL,20,LINE));return c;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(14);b.setTextColor(primary?BG:MINT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(shape(primary?MINT:INPUT,13,primary?0:LINE));b.setMinHeight(dp(48));b.setPadding(dp(10),0,dp(10),0);return b;}
    private EditText input(String hint,boolean password){EditText e=new EditText(this);e.setTextSize(14);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setHint(hint);e.setSingleLine();e.setPadding(dp(12),dp(14),dp(12),dp(14));e.setBackground(shape(INPUT,12,LINE));e.setInputType(InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_URI));return e;}
    private void weighted(LinearLayout row,View view,int left,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(height),1);p.leftMargin=dp(left);row.addView(view,p);}

    private void render(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout body=column();body.setPadding(dp(20),dp(24),dp(20),dp(32));scroll.addView(body);setContentView(scroll);
        LinearLayout top=row();TextView brand=text("FLOW / SAVE",14,MINT,true);brand.setLetterSpacing(.18f);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView version=text("0.3  ·  测试版",11,MUTED,false);top.addView(version);version.setOnLongClickListener(v->{if(!busy)selfTest();return true;});body.addView(top);
        body.addView(text("保存喜欢，\n不减画质。",32,INK,true),gap(22));
        body.addView(text("视频、图集与封面，各自好好收藏。\n保存平台提供的可用内容，不承诺上传源文件。",12,MUTED,false),gap(10));
        LinearLayout sites=row();String[] labels={"YouTube / X","TikTok / IG","抖音 / B站"};for(int i=0;i<3;i++){TextView t=text(labels[i],11,MINT,false);t.setGravity(Gravity.CENTER);t.setBackground(shape(0xff1a3434,9,0));weighted(sites,t,i==0?0:6,31);}body.addView(sites,gap(18));
        LinearLayout modeCard=card();body.addView(modeCard,gap(20));modeCard.addView(text("01   想保存什么",15,INK,true));LinearLayout modeRow=row();
        for(int i=0;i<3;i++){final int x=i;TextView t=text(MODETEXT[i],12,INK,true);t.setGravity(Gravity.CENTER);modeChips[i]=t;weighted(modeRow,t,i==0?0:6,47);t.setOnClickListener(v->{if(!busy){media=MODES[x];refreshChips();}});}modeCard.addView(modeRow,gap(13));
        modeHint=text("",11,MUTED,false);modeCard.addView(modeHint,gap(10));
        LinearLayout linkCard=card();body.addView(linkCard,gap(12));linkCard.addView(text("02   粘贴分享链接",15,INK,true));
        link=input("链接或完整分享文案",false);linkCard.addView(link,gap(13));Button paste=button("从剪贴板粘贴",false);linkCard.addView(paste,gap(10));paste.setOnClickListener(v->{if(busy)return;ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cb!=null&&cb.hasPrimaryClip()){ClipData c=cb.getPrimaryClip();if(c!=null&&c.getItemCount()>0)link.setText(c.getItemAt(0).coerceToText(this));}});
        qualityCard=card();body.addView(qualityCard,gap(12));qualityCard.addView(text("03   画质偏好",15,INK,true));qualityCard.addView(text("最高可用保留源编码；兼容 MP4 才优先兼容性。",11,MUTED,false),gap(7));
        for(int r=0;r<3;r++){LinearLayout chips=row();for(int c=0;c<2;c++){final int x=r*2+c;TextView t=text(QTEXT[x],12,INK,true);t.setGravity(Gravity.CENTER);qualityChips[x]=t;weighted(chips,t,c==0?0:8,61);t.setOnClickListener(v->{if(!busy){quality=QIDS[x];refreshChips();}});}qualityCard.addView(chips,gap(10));}
        LinearLayout auth=card();body.addView(auth,gap(12));auth.addView(text("连接与登录",15,INK,true));networkLabel=text("",12,MUTED,false);auth.addView(networkLabel,gap(8));LinearLayout ar=row();network=button("网络设置",false);cookies=button("登录 Cookies",false);weighted(ar,network,0,48);weighted(ar,cookies,8,48);auth.addView(ar,gap(12));network.setOnClickListener(v->{if(!busy)networkDialog();});cookies.setOnClickListener(v->{if(!busy)cookiesDialog();});
        LinearLayout actions=row();analyze=button("解析详情",false);download=button("开始保存  ↓",true);weighted(actions,analyze,0,55);weighted(actions,download,9,55);body.addView(actions,gap(20));analyze.setOnClickListener(v->start(false));download.setOnClickListener(v->start(true));
        LinearLayout output=card();body.addView(output,gap(17));output.addView(text("保存工作台",14,MINT,true));title=text("等待一个好内容",19,INK,true);title.setMaxLines(3);output.addView(title,gap(13));detail=text("解析后显示实际分辨率、编码或图片数量",12,MUTED,false);detail.setMaxLines(8);output.addView(detail,gap(8));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(ColorStateList.valueOf(MINT));progress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));output.addView(progress,gap(17));status=text("默认直连 · 不含免费代理或代下载服务",12,MUTED,false);status.setMaxLines(8);output.addView(status,gap(9));
        LinearLayout controls=row();open=button("查看已保存",false);open.setEnabled(false);cancel=button("取消任务",false);cancel.setVisibility(View.GONE);weighted(controls,open,0,46);weighted(controls,cancel,8,46);output.addView(controls,gap(14));open.setOnClickListener(v->openSaved());cancel.setOnClickListener(v->{cancelled=true;status.setText("正在取消；已保存的文件不会删除");});
        body.addView(text("只保存你拥有或获授权的内容。下载时请保持本页开启；登录、地区、验证码与 DRM 限制仍然适用。",11,MUTED,false),gap(20));refreshChips();refreshNetwork();
    }
    private void refreshChips(){for(int i=0;i<6;i++){boolean s=QIDS[i].equals(quality);qualityChips[i].setBackground(shape(s?0xff213e36:INPUT,12,s?MINT:LINE));qualityChips[i].setTextColor(s?MINT:INK);}for(int i=0;i<3;i++){boolean s=MODES[i].equals(media);modeChips[i].setBackground(shape(s?0xff213e36:INPUT,12,s?MINT:LINE));modeChips[i].setTextColor(s?MINT:INK);}qualityCard.setVisibility(media.equals("video")?View.VISIBLE:View.GONE);modeHint.setText(media.equals("images")?"单帖图集：Instagram、TikTok、X；抖音实验性适配。保留图片原字节，不改存封面。":media.equals("cover")?"只保存视频提供的最高可用封面，不是图文正文。":"支持分轨下载与无损合并；最高可用不等于作者上传原件。");}
    private File cookieFile(){return new File(getNoBackupFilesDir(),"cookies.txt");}
    private void refreshNetwork(){String n=config.optString("network","direct");networkLabel.setText((n.equals("proxy")?"自定义代理":n.equals("server")?"自建服务器 · 不上传 Cookies":"直连 · 使用手机当前网络")+"\n"+(cookieFile().exists()?"已导入 Cookies，仅本机保存":"未导入 Cookies"));}
    private void setBusy(boolean value){busy=value;for(Button b:new Button[]{analyze,download,network,cookies})b.setEnabled(!value);link.setEnabled(!value);cancel.setVisibility(value?View.VISIBLE:View.GONE);if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    public boolean isCancelled(){return cancelled;}
    public void onProgress(int percent,String message){runOnUiThread(()->{progress.setIndeterminate(false);progress.setProgress(Math.max(0,Math.min(100,percent)));status.setText(message);});}

    private void networkDialog(){
        LinearLayout content=column();content.setPadding(dp(18),dp(14),dp(18),dp(8));content.setBackgroundColor(BG);
        RadioGroup modes=new RadioGroup(this);String[] names={"直连（默认）","自定义 HTTP / SOCKS5 代理","自建下载服务器（HTTPS）"};String[] ids={"direct","proxy","server"};
        for(int i=0;i<3;i++){RadioButton r=new RadioButton(this);r.setText(names[i]);r.setTextColor(INK);r.setButtonTintList(ColorStateList.valueOf(MINT));r.setId(400+i);modes.addView(r);if(config.optString("network","direct").equals(ids[i]))modes.check(400+i);}content.addView(modes);
        EditText proxy=input("代理地址，含协议与端口",true);proxy.setText(config.optString("proxy"));content.addView(proxy,gap(10));EditText endpoint=input("https://你的服务器域名",false);endpoint.setText(config.optString("server"));content.addView(endpoint,gap(10));EditText token=input("服务器令牌（至少32字符）",true);token.setText(config.optString("token"));content.addView(token,gap(10));
        TextView note=text("没有内置节点或已部署服务器。代理需自己配置；自建服务器可看到链接及下载文件，但不会收到手机 Cookies。令牌在本机加密保存。",12,MUTED,false);content.addView(note,gap(13));
        Runnable show=()->{int selected=modes.getCheckedRadioButtonId()-400;proxy.setVisibility(selected==1?View.VISIBLE:View.GONE);endpoint.setVisibility(selected==2?View.VISIBLE:View.GONE);token.setVisibility(selected==2?View.VISIBLE:View.GONE);};modes.setOnCheckedChangeListener((g,id)->show.run());show.run();
        ScrollView sc=new ScrollView(this);sc.addView(content);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("网络与隐私").setView(sc).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(btn->{try{int selected=modes.getCheckedRadioButtonId()-400;if(selected<0)selected=0;JSONObject next=new JSONObject();next.put("network",ids[selected]);next.put("proxy",proxy.getText().toString().trim());next.put("server",endpoint.getText().toString().trim());next.put("token",token.getText().toString().trim());if(selected==1){Uri p=Uri.parse(next.getString("proxy"));String scheme=p.getScheme();if(!("http".equals(scheme)||"https".equals(scheme)||"socks5".equals(scheme)||"socks5h".equals(scheme))||p.getHost()==null||p.getPort()<1)throw new Exception("代理需包含协议、主机、端口");}if(selected==2){Uri p=Uri.parse(next.getString("server"));if(!"https".equals(p.getScheme())||p.getHost()==null||p.getUserInfo()!=null||p.getQuery()!=null||p.getFragment()!=null)throw new Exception("请使用不含凭据和查询参数的 HTTPS 地址");if(next.getString("token").length()<32)throw new Exception("令牌至少需要 32 个字符");}SecureSettings.save(this,next);config=next;refreshNetwork();dialog.dismiss();}catch(Exception e){note.setText(e.getMessage()==null?"设置保存失败":e.getMessage());}}));dialog.show();
    }
    private void cookiesDialog(){new AlertDialog.Builder(this).setTitle("登录 Cookies").setMessage("导入你自己导出的 Netscape cookies.txt。仅保存在应用私有目录，不自动上传至自建服务器。请勿分享此文件。")
            .setPositiveButton("导入文件",(d,w)->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_COOKIES);})
            .setNeutralButton("清除",(d,w)->{boolean ok=!cookieFile().exists()||cookieFile().delete();status.setText(ok?"已清除本机 Cookies":"清除失败，请在系统设置中清除应用数据");refreshNetwork();})
            .setNegativeButton("取消",null).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=PICK_COOKIES||result!=RESULT_OK||data==null||data.getData()==null||busy)return;File tmp=new File(getNoBackupFilesDir(),"cookies.tmp");try(InputStream in=getContentResolver().openInputStream(data.getData());FileOutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("无法读取文件");byte[] b=new byte[8192];int total=0,n;while((n=in.read(b))!=-1){total+=n;if(total>2*1024*1024)throw new IOException("Cookies 文件超过 2 MB");out.write(b,0,n);}out.flush();try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(tmp),StandardCharsets.UTF_8))){String first=reader.readLine();if(first==null||!(first.startsWith("# Netscape HTTP Cookie File")||first.startsWith("# HTTP Cookie File")))throw new IOException("请选择 Netscape 格式 cookies.txt");}if(!tmp.renameTo(cookieFile()))throw new IOException("无法保存 Cookies");status.setText("已导入本机 Cookies");}catch(Exception e){status.setText("导入失败："+e.getMessage());}finally{tmp.delete();refreshNetwork();}}

    private void start(boolean downloading){
        if(busy)return;String raw=link.getText().toString().trim();if(!raw.contains("http")){status.setText("请粘贴完整链接或分享文案");return;}
        if(downloading&&Build.VERSION.SDK_INT<=28&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE);return;}
        final String input=raw, chosenMedia=media, chosenQuality=quality, cfg=config.toString();cancelled=false;setBusy(true);progress.setIndeterminate(true);status.setText("正在准备媒体引擎…");
        if(downloading){savedUris.clear();savedNames.clear();open.setEnabled(false);}
        worker.execute(()->{File stage=null;try{
            File root=getExternalFilesDir("incoming");if(root==null)root=new File(getCacheDir(),"incoming");stage=new File(root,UUID.randomUUID().toString());if(!stage.mkdirs())throw new IOException("无法创建临时目录");
            if(!Python.isStarted())Python.start(new AndroidPlatform(getApplicationContext()));String nativeDir=getApplicationInfo().nativeLibraryDir;
            JSONObject response=new JSONObject(Python.getInstance().getModule("engine_v3").callAttr("execute",downloading?"download":"probe",input,chosenMedia,chosenQuality,stage.getAbsolutePath(),cfg,cookieFile().exists()?cookieFile().getAbsolutePath():"",nativeDir+"/libqjs.so",nativeDir+"/libffmpeg.so",this).toString());
            if(cancelled)throw new IOException("已取消");if(response.has("error"))throw new IOException(response.getString("error"));
            final String summary=response.optString("summary",""), mediaTitle=response.optString("title","媒体");runOnUiThread(()->{title.setText(mediaTitle);detail.setText(summary);});
            if(!downloading){onProgress(0,"解析完成。图集解析仅统计图片；下载仍可能受登录或网络限制。");return;}
            JSONArray paths=response.optJSONArray("paths"),warnings=response.optJSONArray("warnings");if(paths==null||paths.length()==0)throw new IOException("没有完整文件可保存");List<Uri> uris=new ArrayList<>();List<String> names=new ArrayList<>();List<String> errors=new ArrayList<>();
            for(int i=0;i<paths.length();i++){if(cancelled)break;File file=new File(paths.getString(i));if(!file.getCanonicalPath().startsWith(stage.getCanonicalPath()+File.separator)||!file.isFile())throw new IOException("无效的输出文件");try{Uri uri=publish(file);uris.add(uri);names.add(file.getName());}catch(IOException e){errors.add("第 "+(i+1)+" 个文件保存失败");}}
            if(names.isEmpty())throw new IOException(cancelled?"已取消":"文件保存失败");if(warnings!=null)for(int i=0;i<warnings.length();i++)errors.add(warnings.optString(i));
            final String message=(cancelled?"任务已取消；":"完成：")+"已保存 "+names.size()+" 个文件"+(errors.isEmpty()?"":"，另有 "+errors.size()+" 项提醒");final String more=summary+(errors.isEmpty()?"":"\n"+android.text.TextUtils.join("\n",errors));
            runOnUiThread(()->{savedUris.addAll(uris);savedNames.addAll(names);open.setEnabled(true);open.setText("查看已保存 ("+names.size()+")");detail.setText(more);});onProgress(cancelled?0:100,message+"\n图片：Pictures/FlowSave；音视频：Download/FlowSave");
        }catch(Exception e){String message=cancelled?"已取消任务":"未完成："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());runOnUiThread(()->status.setText(message));}
        finally{remove(stage);runOnUiThread(()->{progress.setIndeterminate(false);setBusy(false);});}});
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==STORAGE){if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)start(true);else status.setText("需要存储权限才能保存到公共目录");}}
    private String mime(String name){String n=name.toLowerCase(Locale.ROOT);if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return "image/jpeg";if(n.endsWith(".png"))return "image/png";if(n.endsWith(".webp"))return "image/webp";if(n.endsWith(".gif"))return "image/gif";if(n.endsWith(".avif"))return "image/avif";if(n.endsWith(".heic"))return "image/heic";if(n.endsWith(".mkv"))return "video/x-matroska";if(n.endsWith(".mka"))return "audio/x-matroska";if(n.endsWith(".mp4"))return "video/mp4";if(n.endsWith(".webm"))return "video/webm";if(n.endsWith(".m4a"))return "audio/mp4";if(n.endsWith(".mp3"))return "audio/mpeg";if(n.endsWith(".opus")||n.endsWith(".ogg"))return "audio/ogg";return "application/octet-stream";}
    private Uri publish(File source)throws IOException{String type=mime(source.getName());boolean image=type.startsWith("image/");String directory=image?Environment.DIRECTORY_PICTURES:Environment.DIRECTORY_DOWNLOADS;
        if(Build.VERSION.SDK_INT>=29){ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,source.getName());values.put(MediaStore.MediaColumns.MIME_TYPE,type);values.put(MediaStore.MediaColumns.RELATIVE_PATH,directory+"/FlowSave");values.put(MediaStore.MediaColumns.IS_PENDING,1);Uri uri=getContentResolver().insert(image?MediaStore.Images.Media.EXTERNAL_CONTENT_URI:MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("无法创建输出文件");try{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("输出目录不可写");copy(in,out);}ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,done,null,null);return uri;}catch(Exception e){getContentResolver().delete(uri,null,null);throw new IOException(e);}}
        File folder=new File(Environment.getExternalStoragePublicDirectory(directory),"FlowSave");if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法创建公共目录");File file=new File(folder,System.currentTimeMillis()+"-"+source.getName());try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(file)){copy(in,out);}catch(IOException e){file.delete();throw e;}MediaScannerConnection.scanFile(this,new String[]{file.getAbsolutePath()},new String[]{type},null);return null;
    }
    private void copy(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(cancelled)throw new IOException("已取消");out.write(buffer,0,n);}}
    private static void remove(File file){if(file==null||!file.exists())return;if(file.isDirectory()){File[] files=file.listFiles();if(files!=null)for(File f:files)remove(f);}file.delete();}
    private void openSaved(){if(savedNames.isEmpty())return;new AlertDialog.Builder(this).setTitle("已保存的文件").setItems(savedNames.toArray(new String[0]),(d,index)->{Uri uri=savedUris.get(index);if(uri==null){status.setText("请在文件管理器中打开 Pictures/FlowSave 或 Download/FlowSave");return;}Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(uri,mime(savedNames.get(index)));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(intent);}catch(Exception e){status.setText("没有兼容的查看器，请从系统文件管理器打开");}}).setNegativeButton("关闭",null).show();}
    private void selfTest(){if(busy)return;cancelled=false;setBusy(true);status.setText("正在自检本机引擎（不联网）…");worker.execute(()->{try{File folder=new File(getFilesDir(),"selftest");folder.mkdirs();for(String n:new String[]{"video.webm","audio.webm"}){try(InputStream in=getAssets().open("selftest/"+n);OutputStream out=new FileOutputStream(new File(folder,n))){copy(in,out);}catch(IOException ignored){}}if(!Python.isStarted())Python.start(new AndroidPlatform(getApplicationContext()));String lib=getApplicationInfo().nativeLibraryDir;String result=Python.getInstance().getModule("engine_v3").callAttr("self_test",lib+"/libffmpeg.so",lib+"/libqjs.so",folder.getAbsolutePath()).toString();try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"selftest.json"))){out.write(result.getBytes(StandardCharsets.UTF_8));}runOnUiThread(()->{title.setText("本机引擎自检");detail.setText(result);status.setText("自检完成；这不是远端平台下载成功证明");});}catch(Exception e){runOnUiThread(()->status.setText("自检失败："+e.getMessage()));}finally{runOnUiThread(()->setBusy(false));}});}
    @Override public void onDestroy(){cancelled=true;worker.shutdownNow();super.onDestroy();}
}
